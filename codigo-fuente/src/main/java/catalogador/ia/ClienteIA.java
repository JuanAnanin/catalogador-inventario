package catalogador.ia;

import catalogador.util.Json;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Habla con un servicio de IA por HTTPS usando solo la biblioteca estándar de
 * Java ({@code java.net.http}). Envía una consulta (instrucciones + datos) y
 * devuelve el texto de la respuesta. Se encarga de:
 * <ul>
 *   <li>los tres formatos de API: OpenAI (y compatibles), Anthropic y Ollama;</li>
 *   <li>pedir la respuesta en JSON del modo que admite cada servicio (salida
 *       estructurada con esquema, «modo JSON» o solo en las instrucciones);</li>
 *   <li>pedir poco «razonamiento» a los modelos que piensan antes de responder
 *       (más rápido y barato, y suficiente para elegir entre candidatos);</li>
 *   <li>si el servicio rechaza un parámetro opcional (temperatura, formato JSON,
 *       nivel de razonamiento…), repetir la consulta sin él y no volver a enviarlo;</li>
 *   <li>reintentar si el servicio pide esperar (429 con «Retry-After» o con el
 *       tiempo dentro del mensaje) o falla un momento (500, 502, 503, 529);</li>
 *   <li>traducir los errores a mensajes claros ({@link ErrorIA}).</li>
 * </ul>
 * Usa el proxy configurado en el sistema (habitual en redes de hospital) y,
 * además de los certificados de Java, los del sistema operativo.
 */
public final class ClienteIA {

    /** Un fallo al hablar con la IA, con un mensaje pensado para el usuario. */
    public static final class ErrorIA extends IOException {
        /** Tipo de fallo; los primeros obligan a parar y revisar la configuración. */
        public enum Tipo {
            CLAVE, SALDO, CUOTA, MODELO, PETICION, DEMASIADO_GRANDE, LIMITE, SERVIDOR, RED, RESPUESTA, CANCELADO
        }

        private final Tipo tipo;
        private final int estado;
        private final long esperaSegundos;

        public ErrorIA(Tipo tipo, int estado, String mensaje) {
            this(tipo, estado, mensaje, -1);
        }

        ErrorIA(Tipo tipo, int estado, String mensaje, long esperaSegundos) {
            super(mensaje);
            this.tipo = tipo;
            this.estado = estado;
            this.esperaSegundos = esperaSegundos;
        }

        public Tipo getTipo() {
            return tipo;
        }

        /** Código HTTP (0 si no hubo respuesta). */
        public int getEstado() {
            return estado;
        }

        /** Segundos que el servicio pide esperar (−1 si no lo dice). */
        public long getEsperaSegundos() {
            return esperaSegundos;
        }

        /** true si no tiene sentido seguir preguntando hasta cambiar la configuración. */
        public boolean detieneLaIA() {
            return tipo == Tipo.CLAVE || tipo == Tipo.SALDO || tipo == Tipo.CUOTA || tipo == Tipo.MODELO
                    || tipo == Tipo.RED;
        }
    }

    /** Lo que devuelve una consulta. */
    public static final class Respuesta {
        private final String texto;
        private final long tokensEntrada;
        private final long tokensSalida;
        private final long milisegundos;
        private final boolean cortada;

        Respuesta(String texto, long tokensEntrada, long tokensSalida, long milisegundos, boolean cortada) {
            this.texto = texto;
            this.tokensEntrada = tokensEntrada;
            this.tokensSalida = tokensSalida;
            this.milisegundos = milisegundos;
            this.cortada = cortada;
        }

        public String getTexto() {
            return texto;
        }

        public long getTokensEntrada() {
            return tokensEntrada;
        }

        public long getTokensSalida() {
            return tokensSalida;
        }

        public long getMilisegundos() {
            return milisegundos;
        }

        /** El servicio dejó de escribir por el límite de tokens de salida. */
        public boolean isCortada() {
            return cortada;
        }
    }

    /** Lo que se sabe de un modelo de Ollama (de /api/show). */
    static final class InfoOllama {
        final List<String> capacidades = new ArrayList<>();
        final List<Object> valoresThink = new ArrayList<>();
        boolean tieneMetadatosThink;
        /** Su plantilla usa niveles de razonamiento (gpt-oss): «think» no se puede apagar, solo bajar a «low». */
        boolean nivelesThink;
        int contexto;
    }

    static final String VERSION_ANTHROPIC = "2023-06-01";
    static final String VERSION = "3.1";
    private static final int MAX_REINTENTOS = 4;
    /** Si el servicio pide esperar más que esto, se trata como cuota agotada (no se espera). */
    static final int MAX_ESPERA_SEGUNDOS = 90;
    private static final int CONTEXTO_MINIMO_OLLAMA = 8192;
    private static final int CONTEXTO_MAXIMO_OLLAMA = 32768;

    /** Esquema de la prueba de conexión. */
    static final Map<String, Object> ESQUEMA_PRUEBA = esquemaPrueba();

    private final ProveedorIA proveedor;
    private final String url;
    private final String clave;
    private final String modelo;
    private final HttpClient http;
    /** Parámetros opcionales que este servicio/modelo ha rechazado: no se vuelven a enviar. */
    private final Set<String> quitados = ConcurrentHashMap.newKeySet();
    private volatile InfoOllama infoOllama;
    /** El modelo de Ollama ha respondido vacío con «think: false»: se le pide razonamiento «low». */
    private volatile boolean thinkBajo;
    private final AtomicLong tokensEntrada = new AtomicLong();
    private final AtomicLong tokensSalida = new AtomicLong();
    private final AtomicLong consultas = new AtomicLong();

    public ClienteIA(ProveedorIA proveedor, String url, String clave, String modelo) {
        this.proveedor = proveedor;
        this.url = normalizarUrl(proveedor, url);
        this.clave = clave == null ? "" : clave.trim();
        this.modelo = modelo == null ? "" : modelo.trim();
        boolean local = proveedor.isLocal() || esDireccionLocal(this.url);
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(local ? 5 : 20))
                .proxy(local ? HttpClient.Builder.NO_PROXY : ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL);
        SSLContext ssl = local ? null : Certificados.contexto();
        if (ssl != null) {
            b.sslContext(ssl);
        }
        this.http = b.build();
    }

    /** Cliente con lo que haya en la configuración para ese servicio. */
    public static ClienteIA desde(ConfigIA c, ProveedorIA p) {
        return new ClienteIA(p, c.getUrl(p), c.getClave(p), c.getModelo(p));
    }

    /** Quita barras finales y, en Ollama, el «/v1» o «/api» que a veces se escribe. */
    static String normalizarUrl(ProveedorIA p, String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (p.getProtocolo() == ProveedorIA.Protocolo.OLLAMA) {
            u = u.replaceFirst("(?i)/(v1|api)$", "");
        }
        return u;
    }

    private static boolean esDireccionLocal(String u) {
        String s = u.toLowerCase(Locale.ROOT);
        return s.startsWith("http://localhost") || s.startsWith("http://127.") || s.startsWith("http://[::1]");
    }

    public ProveedorIA getProveedor() {
        return proveedor;
    }

    public String getModelo() {
        return modelo;
    }

    public String getUrl() {
        return url;
    }

    /** «Groq · llama-3.3-70b-versatile» */
    public String descripcion() {
        return proveedor.getNombreCorto() + " · " + modelo;
    }

    public long getTokensEntrada() {
        return tokensEntrada.get();
    }

    public long getTokensSalida() {
        return tokensSalida.get();
    }

    public long getConsultas() {
        return consultas.get();
    }

    /** Parámetros que el servicio ha rechazado en esta sesión (para las pruebas y el diagnóstico). */
    public Set<String> getParametrosQuitados() {
        return Collections.unmodifiableSet(quitados);
    }

    // ------------------------------------------------------------------ lista de modelos

    /** Pide al servicio los modelos disponibles (solo los que sirven para conversar). */
    public List<String> modelos() throws ErrorIA {
        String ruta;
        switch (proveedor.getProtocolo()) {
            case OLLAMA:
                ruta = url + "/api/tags";
                break;
            case ANTHROPIC:
                ruta = url + "/models?limit=1000";
                break;
            default:
                // Cohere no da la lista en su API compatible: se pide a la suya
                ruta = proveedor.getId().equals("cohere")
                        ? url.replaceFirst("/compatibility/v1$", "/v1") + "/models?endpoint=chat&page_size=1000"
                        : url + "/models";
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(ruta)).timeout(Duration.ofSeconds(30)).GET();
        cabeceras(b);
        HttpResponse<String> r = enviar(b.build());
        if (r.statusCode() / 100 != 2) {
            if (r.statusCode() == 404 || r.statusCode() == 405) {
                throw new ErrorIA(ErrorIA.Tipo.PETICION, r.statusCode(), "Este servicio no da la lista de modelos (error "
                        + r.statusCode() + "). Escribe el nombre del modelo a mano.");
            }
            throw error(r);
        }
        Set<String> ids = new LinkedHashSet<>();
        try {
            Object j = Json.leer(r.body());
            Object datos = Json.ruta(j, "data");
            if (datos == null) {
                datos = Json.ruta(j, "models");
            }
            if (datos instanceof List) {
                for (Object m : (List<?>) datos) {
                    Object id = m instanceof Map ? ((Map<?, ?>) m).get("id") : m;
                    if (id == null && m instanceof Map) {
                        id = ((Map<?, ?>) m).get("name");
                    }
                    if (id == null) {
                        continue;
                    }
                    String s = String.valueOf(id).replaceFirst("^models/", "");
                    String familia = Json.texto(Json.ruta(m, "details", "family")).toLowerCase(Locale.ROOT);
                    if (!pareceNoConversacional(s) && !familia.contains("bert")) {
                        ids.add(s);
                    }
                }
            }
        } catch (Json.ErrorJson e) {
            throw new ErrorIA(ErrorIA.Tipo.RESPUESTA, r.statusCode(), "La lista de modelos no tiene el formato esperado.");
        }
        List<String> l = new ArrayList<>(ids);
        Collections.sort(l);
        return l;
    }

    /** Modelos de imagen, voz, vectores o moderación: no sirven para catalogar. */
    static boolean pareceNoConversacional(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        return s.contains("embed") || s.contains("whisper") || s.contains("tts") || s.contains("transcribe")
                || s.contains("image") || s.contains("dall-e") || s.contains("moderation") || s.contains("veo-")
                || s.contains("rerank") || s.contains("audio") || s.contains("realtime") || s.contains("guard")
                || s.contains("bge-") || s.contains("minilm") || s.contains("mxbai") || s.contains("orpheus")
                || s.contains("-ocr") || s.startsWith("sora") || s.contains("live-");
    }

    // ------------------------------------------------------------------ consulta

    /** Consulta sin esquema (se pide JSON en las instrucciones). */
    public Respuesta completar(String sistema, String usuario) throws ErrorIA {
        return completar(sistema, usuario, null, 1500);
    }

    /**
     * Envía unas instrucciones y un mensaje y devuelve la respuesta, pidiéndola
     * en JSON (con el esquema dado, si el servicio admite salida estructurada).
     *
     * @param maxSalida tokens de respuesta que se esperan como mucho (sin contar el razonamiento)
     */
    public Respuesta completar(String sistema, String usuario, Map<String, Object> esquema, int maxSalida)
            throws ErrorIA {
        long t0 = System.nanoTime();
        int reintento = 0;
        int ajustes = 0;
        boolean jsonReintentado = false;
        boolean vaciaReintentada = false;
        while (true) {
            comprobarCancelado();
            Map<String, Object> cuerpo;
            String ruta;
            Set<String> opcionales = new LinkedHashSet<>();
            switch (proveedor.getProtocolo()) {
                case ANTHROPIC:
                    cuerpo = cuerpoAnthropic(sistema, usuario, maxSalida, opcionales);
                    ruta = url + "/messages";
                    break;
                case OLLAMA:
                    cuerpo = cuerpoOllama(sistema, usuario, esquema, maxSalida, opcionales);
                    ruta = url + "/api/chat";
                    break;
                default:
                    cuerpo = cuerpoOpenAI(sistema, usuario, esquema, maxSalida, opcionales);
                    ruta = url + "/chat/completions";
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(uri(ruta))
                    .timeout(Duration.ofSeconds(proveedor.isLocal() || esDireccionLocal(url) ? 600 : 180))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.escribir(cuerpo), StandardCharsets.UTF_8));
            cabeceras(b);
            consultas.incrementAndGet();
            HttpResponse<String> r = enviar(b.build());
            int e = r.statusCode();
            if (e / 100 == 2) {
                Respuesta res;
                try {
                    res = leerRespuesta(r.body(), (System.nanoTime() - t0) / 1_000_000);
                } catch (ErrorIA vacia) {
                    if (vacia.getTipo() == ErrorIA.Tipo.RESPUESTA && Boolean.FALSE.equals(cuerpo.get("think"))
                            && !thinkBajo) {
                        // Ollama: hay modelos que piensan siempre (gpt-oss y otros con niveles); con
                        // «think: false» responden vacío. Se repite pidiendo el razonamiento mínimo.
                        thinkBajo = true;
                        continue;
                    }
                    if (vacia.getTipo() == ErrorIA.Tipo.RESPUESTA && !vaciaReintentada) {
                        vaciaReintentada = true; // DeepSeek y otros devuelven a veces una respuesta vacía
                        continue;
                    }
                    throw vacia;
                }
                tokensEntrada.addAndGet(res.tokensEntrada);
                tokensSalida.addAndGet(res.tokensSalida);
                if (res.cortada && !jsonCompleto(res.texto)) {
                    throw new ErrorIA(ErrorIA.Tipo.DEMASIADO_GRANDE, e, "La respuesta de la IA se ha cortado antes de "
                            + "terminar (límite de tokens de salida).");
                }
                return res;
            }
            String texto = r.body() == null ? "" : r.body().toLowerCase(Locale.ROOT);
            if ((e == 400 || e == 422) && ajustes < 10) {
                // Groq: el modelo no consiguió escribir JSON válido; se repite una vez y luego sin «modo JSON»
                if (texto.contains("json_validate_failed") || texto.contains("failed to generate json")) {
                    ajustes++;
                    if (!jsonReintentado) {
                        jsonReintentado = true;
                    } else {
                        quitados.add("json_schema");
                        quitados.add("response_format");
                    }
                    continue;
                }
                // Un parámetro opcional que este servicio o modelo no admite: se quita y se repite
                String rechazado = parametroRechazado(textoError(r.body()), opcionales);
                if (rechazado != null) {
                    quitados.add(rechazado);
                    ajustes++;
                    continue;
                }
            }
            ErrorIA err = error(r);
            boolean reintentable = err.getTipo() == ErrorIA.Tipo.LIMITE || err.getTipo() == ErrorIA.Tipo.SERVIDOR;
            if (!reintentable || reintento >= MAX_REINTENTOS) {
                throw err;
            }
            long espera = err.getEsperaSegundos() > 0 ? Math.min(MAX_ESPERA_SEGUNDOS, err.getEsperaSegundos())
                    : Math.min(MAX_ESPERA_SEGUNDOS, 2L << reintento); // 2, 4, 8, 16…
            dormir(espera * 1000);
            reintento++;
        }
    }

    /** Prueba rápida de la configuración: devuelve un texto como «Responde en 0,8 s». */
    public String probar() throws ErrorIA {
        if (proveedor.getProtocolo() == ProveedorIA.Protocolo.OLLAMA) {
            List<String> instalados = modelos();
            if (!contieneModeloOllama(instalados, modelo)) {
                throw new ErrorIA(ErrorIA.Tipo.MODELO, 404, "Ollama está funcionando, pero el modelo «" + modelo
                        + "» no está descargado. Abre una terminal y ejecuta:  ollama pull " + modelo
                        + (instalados.isEmpty() ? "" : "\nModelos descargados: " + String.join(", ", instalados) + "."));
            }
        }
        Respuesta r = completar("Eres un comprobador de conexión. Responde solo con un objeto JSON.",
                "Devuelve exactamente este objeto JSON: {\"ok\": true}", ESQUEMA_PRUEBA, 50);
        String segundos = String.format(Locale.ROOT, "%.1f", r.getMilisegundos() / 1000.0).replace('.', ',');
        if (!tieneJson(r.getTexto())) {
            return "Conecta (" + segundos + " s), pero el modelo no ha devuelto JSON: puede dar respuestas poco fiables.";
        }
        return "Conexión correcta: «" + modelo + "» responde en " + segundos + " s.";
    }

    /** «qwen3:8b» está en la lista; «mistral» equivale a «mistral:latest». */
    static boolean contieneModeloOllama(List<String> instalados, String m) {
        String buscado = m.contains(":") ? m : m + ":latest";
        for (String s : instalados) {
            String x = s.contains(":") ? s : s + ":latest";
            if (x.equalsIgnoreCase(buscado)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ cuerpo de las consultas

    private Map<String, Object> cuerpoOpenAI(String sistema, String usuario, Map<String, Object> esquema,
                                             int maxSalida, Set<String> opcionales) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("model", modelo);
        List<Object> mensajes = new ArrayList<>();
        if (quitados.contains("system")) {
            mensajes.add(mensaje("user", sistema + "\n\n" + usuario));
        } else {
            mensajes.add(mensaje("system", sistema));
            mensajes.add(mensaje("user", usuario));
            opcionales.add("system");
        }
        c.put("messages", mensajes);
        if (usaTemperatura()) {
            poner(c, "temperature", 0, opcionales);
        }
        ProveedorIA.FormatoJson f = proveedor.getFormatoJson();
        boolean lmstudio = proveedor.getId().equals("lmstudio");
        if (f == ProveedorIA.FormatoJson.ESQUEMA && esquema != null && !quitados.contains("json_schema")
                && !quitados.contains("response_format")) {
            Map<String, Object> js = new LinkedHashMap<>();
            js.put("name", "respuesta");
            js.put("strict", true);
            js.put("schema", esquema);
            Map<String, Object> rf = new LinkedHashMap<>();
            rf.put("type", "json_schema");
            rf.put("json_schema", js);
            c.put("response_format", rf);
            opcionales.add("json_schema");
        } else if (f != ProveedorIA.FormatoJson.NINGUNO && !lmstudio && !quitados.contains("response_format")) {
            Map<String, Object> rf = new LinkedHashMap<>();
            rf.put("type", "json_object");
            c.put("response_format", rf);
            opcionales.add("response_format");
        }
        String ps = parametroSalida();
        if (ps != null) {
            int n = maxSalida + margenRazonamiento();
            if (!quitados.contains(ps)) {
                c.put(ps, n);
                opcionales.add(ps);
            } else if (ps.equals("max_completion_tokens") && !quitados.contains("max_tokens")) {
                c.put("max_tokens", n);
                opcionales.add("max_tokens");
            }
        }
        for (Map.Entry<String, Object> x : extrasRazonamiento().entrySet()) {
            poner(c, x.getKey(), x.getValue(), opcionales);
        }
        return c;
    }

    private Map<String, Object> cuerpoAnthropic(String sistema, String usuario, int maxSalida, Set<String> opcionales) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("model", modelo);
        // El razonamiento («adaptive thinking») cuenta dentro de max_tokens
        c.put("max_tokens", Math.max(4096, Math.min(32000, maxSalida * 2 + 2000)));
        c.put("system", sistema);
        List<Object> mensajes = new ArrayList<>();
        mensajes.add(mensaje("user", usuario));
        c.put("messages", mensajes);
        Map<String, Object> oc = new LinkedHashMap<>();
        oc.put("effort", "low");
        poner(c, "output_config", oc, opcionales);
        return c;
    }

    private Map<String, Object> cuerpoOllama(String sistema, String usuario, Map<String, Object> esquema, int maxSalida,
                                             Set<String> opcionales) throws ErrorIA {
        InfoOllama info = infoOllama();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("model", modelo);
        List<Object> mensajes = new ArrayList<>();
        mensajes.add(mensaje("system", sistema));
        mensajes.add(mensaje("user", usuario));
        c.put("messages", mensajes);
        c.put("stream", false);
        if (esquema != null && !quitados.contains("format_esquema") && !quitados.contains("format")) {
            c.put("format", esquema);
            opcionales.add("format_esquema");
        } else if (!quitados.contains("format")) {
            c.put("format", "json");
            opcionales.add("format");
        }
        Object think = thinkBajo ? "low" : valorThink(info);
        if (think != null) {
            poner(c, "think", think, opcionales);
        }
        boolean piensaSiempre = think instanceof String; // gpt-oss: no se puede apagar, solo bajar
        int prediccion = maxSalida + (piensaSiempre ? 2500 : 0);
        // Ollama recorta en silencio lo que no cabe en el contexto (4.096 tokens por defecto en
        // ordenadores sin una tarjeta gráfica grande): se pide el que hace falta.
        int estimado = (sistema.length() + usuario.length()) / 3 + 64;
        int necesario = estimado + prediccion + 256;
        int tope = CONTEXTO_MAXIMO_OLLAMA;
        if (info.contexto > 0) {
            tope = Math.min(tope, info.contexto);
        }
        if (necesario > tope) {
            throw new ErrorIA(ErrorIA.Tipo.DEMASIADO_GRANDE, 0, "La consulta no cabe en el contexto del modelo ("
                    + tope + " tokens).");
        }
        int ctx = Math.max(CONTEXTO_MINIMO_OLLAMA, (necesario + 2047) / 2048 * 2048);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("temperature", 0);
        o.put("num_ctx", Math.min(ctx, tope));
        o.put("num_predict", prediccion);
        c.put("options", o);
        c.put("keep_alive", "30m");
        return c;
    }

    /**
     * El valor de «think» que conviene enviar a este modelo de Ollama (null = no enviarlo): false para
     * apagar el razonamiento (qwen3, deepseek-r1, gemma4…); «low» para los que solo admiten niveles
     * (gpt-oss, cuya plantilla usa «ThinkLevel»: con false responden vacío).
     */
    static Object valorThink(InfoOllama info) {
        if (info.tieneMetadatosThink && !info.valoresThink.isEmpty()) {
            if (info.valoresThink.contains(Boolean.FALSE)) {
                return info.valoresThink.size() == 1 ? null : Boolean.FALSE; // [false] = no piensa
            }
            if (info.valoresThink.contains("low")) {
                return "low";
            }
            return info.valoresThink.get(0) instanceof String ? info.valoresThink.get(0) : null;
        }
        if (info.nivelesThink) {
            return "low";
        }
        return info.capacidades.contains("thinking") ? Boolean.FALSE : null;
    }

    /** Lee (una vez) las capacidades del modelo de Ollama: si piensa, cuánto contexto admite… */
    InfoOllama infoOllama() throws ErrorIA {
        InfoOllama i = infoOllama;
        if (i != null) {
            return i;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("model", modelo);
        c.put("name", modelo); // versiones antiguas de Ollama
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(url + "/api/show")).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.escribir(c), StandardCharsets.UTF_8));
        cabeceras(b);
        HttpResponse<String> r = enviar(b.build());
        i = new InfoOllama();
        if (r.statusCode() == 404) {
            throw new ErrorIA(ErrorIA.Tipo.MODELO, 404, "El modelo «" + modelo + "» no está descargado en Ollama. "
                    + "Abre una terminal y ejecuta:  ollama pull " + modelo + "\n(o elige otro con «Cargar modelos» en IA → Modo de trabajo)");
        }
        if (r.statusCode() / 100 == 2) {
            try {
                Object j = Json.leer(r.body());
                Object caps = Json.ruta(j, "capabilities");
                if (caps instanceof List) {
                    for (Object x : (List<?>) caps) {
                        i.capacidades.add(Json.texto(x).toLowerCase(Locale.ROOT));
                    }
                }
                Object valores = Json.ruta(j, "thinking", "values");
                if (valores instanceof List) {
                    i.tieneMetadatosThink = true;
                    i.valoresThink.addAll((List<?>) valores);
                }
                // gpt-oss y los modelos parecidos razonan por niveles («Reasoning: low/medium/high»)
                String familia = (Json.texto(Json.ruta(j, "details", "family")) + " "
                        + Json.texto(Json.ruta(j, "model_info", "general.architecture"))).toLowerCase(Locale.ROOT);
                i.nivelesThink = Json.texto(Json.ruta(j, "template")).contains("ThinkLevel")
                        || familia.contains("gpt-oss") || familia.contains("gptoss");
                Object mi = Json.ruta(j, "model_info");
                if (mi instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) mi).entrySet()) {
                        if (String.valueOf(e.getKey()).endsWith(".context_length")) {
                            i.contexto = Json.entero(e.getValue(), 0);
                        }
                    }
                }
            } catch (Json.ErrorJson ignorado) {
                // Ollama antiguo: se sigue sin esos datos
            }
            if (!i.capacidades.isEmpty() && !i.capacidades.contains("completion")) {
                throw new ErrorIA(ErrorIA.Tipo.MODELO, 400, "El modelo «" + modelo + "» no sirve para conversar ("
                        + String.join(", ", i.capacidades) + "). Elige otro en IA → Modo de trabajo.");
            }
        }
        infoOllama = i;
        return i;
    }

    private void poner(Map<String, Object> c, String clave, Object valor, Set<String> opcionales) {
        if (!quitados.contains(clave)) {
            c.put(clave, valor);
            opcionales.add(clave);
        }
    }

    private String modeloMin() {
        return modelo.toLowerCase(Locale.ROOT);
    }

    /** Modelos que «razonan» antes de responder y no admiten (o no necesitan) temperatura 0. */
    private boolean esRazonador() {
        String m = modeloMin();
        return m.contains("gpt-oss") || m.matches("^(openai/)?(o[1-9]|gpt-[5-9]).*") || m.contains("reasoner")
                || m.contains("-r1") || m.contains("thinking") || m.startsWith("grok-4");
    }

    private boolean usaTemperatura() {
        String id = proveedor.getId();
        if (id.equals("openai") || id.equals("gemini")) {
            return false; // los modelos actuales solo admiten la de por defecto (Gemini 3 la recomienda)
        }
        return !esRazonador();
    }

    /** Nombre del parámetro con el máximo de tokens de salida (null = no se envía). */
    private String parametroSalida() {
        switch (proveedor.getId()) {
            case "openai":
            case "groq":
            case "cerebras":
                return "max_completion_tokens";
            case "gemini":         // el razonamiento cuenta dentro: mejor no limitarlo
            case "lmstudio":
            case "personalizado":
                return null;
            default:
                return "max_tokens";
        }
    }

    private int margenRazonamiento() {
        String id = proveedor.getId();
        if (id.equals("openai") || id.equals("xai")) {
            return 4000;
        }
        if (id.equals("deepseek") || id.equals("mistral") || id.equals("cohere")) {
            return 0; // se les apaga el razonamiento
        }
        if (esRazonador()) {
            return id.equals("groq") ? 1500 : 3000; // en Groq gratis cuenta para el tope por minuto
        }
        return 0;
    }

    /** Parámetros para pedir poco (o ningún) razonamiento, según el servicio y el modelo. */
    private Map<String, Object> extrasRazonamiento() {
        Map<String, Object> x = new LinkedHashMap<>();
        String m = modeloMin();
        switch (proveedor.getId()) {
            case "openai":
            case "gemini":
            case "xai":
                x.put("reasoning_effort", "low");
                break;
            case "groq":
                if (m.contains("gpt-oss")) {
                    x.put("reasoning_effort", "low");
                    x.put("include_reasoning", false);
                } else if (m.contains("qwen")) {
                    x.put("reasoning_effort", "none");
                }
                break;
            case "cerebras":
            case "huggingface":
                if (m.contains("gpt-oss")) {
                    x.put("reasoning_effort", "low");
                }
                break;
            case "openrouter":
            case "openrouter-gratis":
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("effort", "low");
                r.put("exclude", true);
                x.put("reasoning", r);
                break;
            case "mistral":
            case "cohere":
                x.put("reasoning_effort", "none");
                break;
            case "deepseek":
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("type", "disabled");
                x.put("thinking", t);
                break;
            default:
                break;
        }
        return x;
    }

    /**
     * Si el error 400 se refiere a uno de los parámetros opcionales enviados,
     * devuelve cuál hay que dejar de enviar (null si no es eso).
     */
    static String parametroRechazado(String texto, Set<String> enviados) {
        String[][] pistas = {
                {"json_schema", "json_schema", "structured output", "schema", "response_format"},
                {"response_format", "response_format", "json_object", "json mode", "response format"},
                {"format_esquema", "format", "schema"},
                {"format", "format"},
                {"include_reasoning", "include_reasoning"},
                {"reasoning_effort", "reasoning_effort", "reasoning effort", "reasoning"},
                {"reasoning", "reasoning"},
                {"thinking", "thinking"},
                {"think", "think"},
                {"output_config", "output_config", "effort"},
                {"temperature", "temperature"},
                {"max_completion_tokens", "max_completion_tokens"},
                {"max_tokens", "max_tokens"},
                {"system", "system role", "system message", "role 'system'", "role \"system\"", "system prompt",
                        "roles must alternate", "system is not supported"},
        };
        for (String[] p : pistas) {
            if (!enviados.contains(p[0])) {
                continue;
            }
            for (int k = 1; k < p.length; k++) {
                if (texto.contains(p[k])) {
                    // «max_tokens is not supported… use max_completion_tokens»: se quita max_tokens
                    if (p[0].equals("max_completion_tokens") && texto.contains("use 'max_completion_tokens'")) {
                        continue;
                    }
                    return p[0];
                }
            }
        }
        return null;
    }

    private void cabeceras(HttpRequest.Builder b) {
        b.header("Accept", "application/json");
        b.header("User-Agent", "Catalogador/" + VERSION + " (Java " + System.getProperty("java.version") + ")");
        if (proveedor.getProtocolo() == ProveedorIA.Protocolo.ANTHROPIC) {
            b.header("x-api-key", clave);
            b.header("anthropic-version", VERSION_ANTHROPIC);
        } else if (!clave.isEmpty()) {
            b.header("Authorization", "Bearer " + clave);
        }
        if (proveedor.getId().startsWith("openrouter") || url.contains("openrouter.ai")) {
            b.header("X-OpenRouter-Title", "Catalogador de inventario");
            b.header("X-Title", "Catalogador de inventario");
        }
    }

    private static Map<String, Object> mensaje(String rol, String texto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", rol);
        m.put("content", texto);
        return m;
    }

    private static URI uri(String ruta) {
        try {
            return URI.create(ruta);
        } catch (IllegalArgumentException e) {
            return URI.create("http://direccion-no-valida/");
        }
    }

    private HttpResponse<String> enviar(HttpRequest req) throws ErrorIA {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ErrorIA(ErrorIA.Tipo.PETICION, 0, "La dirección del servicio no es válida: «" + url + "».");
        }
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrorIA(ErrorIA.Tipo.CANCELADO, 0, "Cancelado.");
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw sinConexion(e);
        } catch (HttpTimeoutException e) {
            throw new ErrorIA(ErrorIA.Tipo.SERVIDOR, 0, proveedor.isLocal()
                    ? proveedor.getNombreCorto() + " ha tardado demasiado en responder. Prueba un modelo más pequeño "
                    + "o envía menos grupos por consulta."
                    : "El servicio ha tardado demasiado en responder.");
        } catch (SSLException e) {
            throw new ErrorIA(ErrorIA.Tipo.RED, 0, "No se puede establecer una conexión segura con " + url
                    + " (certificado no reconocido). Si la red del hospital inspecciona el tráfico cifrado, pide a "
                    + "informática que instale su certificado en el sistema: el Catalogador también usa los "
                    + "certificados de Windows y de macOS.\n(" + e.getMessage() + ")");
        } catch (IOException e) {
            Throwable c = e.getCause();
            if (c instanceof UnknownHostException || c instanceof java.nio.channels.UnresolvedAddressException
                    || c instanceof ConnectException) {
                throw sinConexion(e);
            }
            if (c instanceof SSLException) {
                throw new ErrorIA(ErrorIA.Tipo.RED, 0, "No se puede establecer una conexión segura con " + url
                        + " (certificado no reconocido). Si la red del hospital inspecciona el tráfico cifrado, pide a "
                        + "informática que instale su certificado en el sistema.\n(" + c.getMessage() + ")");
            }
            throw new ErrorIA(ErrorIA.Tipo.RED, 0, "No se pudo hablar con " + proveedor.getNombre() + ": "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        } catch (IllegalArgumentException e) {
            throw new ErrorIA(ErrorIA.Tipo.PETICION, 0, "La dirección del servicio no es válida: «" + url + "».");
        }
    }

    private ErrorIA sinConexion(Exception e) {
        if (proveedor.isLocal() || esDireccionLocal(url)) {
            String que = proveedor.getProtocolo() == ProveedorIA.Protocolo.OLLAMA
                    ? "¿Está instalado y abierto Ollama? (en Windows y macOS se abre desde su icono; en Linux: «ollama serve»)"
                    : "¿Está abierto el programa, con un modelo cargado y el servidor local activado?";
            return new ErrorIA(ErrorIA.Tipo.RED, 0, "No se puede conectar con " + proveedor.getNombreCorto() + " en " + url
                    + ". " + que);
        }
        return new ErrorIA(ErrorIA.Tipo.RED, 0, "No hay conexión con " + url + ". Comprueba la conexión a Internet "
                + "o, en la red del hospital, que el proxy permita salir a ese servicio.");
    }

    // ------------------------------------------------------------------ respuestas

    private Respuesta leerRespuesta(String cuerpo, long ms) throws ErrorIA {
        Object j;
        try {
            j = Json.leer(cuerpo);
        } catch (Json.ErrorJson e) {
            throw new ErrorIA(ErrorIA.Tipo.RESPUESTA, 200, "La respuesta del servicio no es JSON válido.");
        }
        String texto;
        long ent;
        long sal;
        boolean cortada;
        switch (proveedor.getProtocolo()) {
            case ANTHROPIC: {
                StringBuilder b = new StringBuilder();
                Object partes = Json.ruta(j, "content");
                if (partes instanceof List) {
                    for (Object p : (List<?>) partes) {
                        if ("text".equals(Json.ruta(p, "type"))) {
                            b.append(Json.texto(Json.ruta(p, "text")));
                        }
                    }
                }
                texto = b.toString();
                ent = Json.entero(Json.ruta(j, "usage", "input_tokens"), 0);
                sal = Json.entero(Json.ruta(j, "usage", "output_tokens"), 0);
                String parada = Json.texto(Json.ruta(j, "stop_reason"));
                cortada = parada.equals("max_tokens");
                if (parada.equals("refusal")) {
                    throw new ErrorIA(ErrorIA.Tipo.RESPUESTA, 200, "El modelo se ha negado a responder a esta consulta.");
                }
                break;
            }
            case OLLAMA:
                texto = Json.texto(Json.ruta(j, "message", "content"));
                ent = Json.entero(Json.ruta(j, "prompt_eval_count"), 0);
                sal = Json.entero(Json.ruta(j, "eval_count"), 0);
                cortada = "length".equals(Json.texto(Json.ruta(j, "done_reason")));
                break;
            default: {
                Object c = Json.ruta(j, "choices", 0, "message", "content");
                if (c instanceof List) {
                    StringBuilder b = new StringBuilder();
                    for (Object p : (List<?>) c) {
                        if (p instanceof Map) {
                            Object tipo = ((Map<?, ?>) p).get("type");
                            if (tipo == null || "text".equals(tipo) || "output_text".equals(tipo)) {
                                b.append(Json.texto(((Map<?, ?>) p).get("text")));
                            }
                        } else {
                            b.append(Json.texto(p));
                        }
                    }
                    texto = b.toString();
                } else {
                    texto = Json.texto(c);
                }
                Object rechazo = Json.ruta(j, "choices", 0, "message", "refusal");
                if (texto.trim().isEmpty() && rechazo instanceof String && !((String) rechazo).isEmpty()) {
                    throw new ErrorIA(ErrorIA.Tipo.RESPUESTA, 200, "El modelo se ha negado a responder: " + rechazo);
                }
                Object pe = Json.ruta(j, "usage", "prompt_tokens");
                Object ps = Json.ruta(j, "usage", "completion_tokens");
                ent = Json.entero(pe != null ? pe : Json.ruta(j, "usage", "input_tokens"), 0);
                sal = Json.entero(ps != null ? ps : Json.ruta(j, "usage", "output_tokens"), 0);
                cortada = "length".equals(Json.texto(Json.ruta(j, "choices", 0, "finish_reason")));
            }
        }
        if (texto.trim().isEmpty()) {
            if (cortada) {
                throw new ErrorIA(ErrorIA.Tipo.DEMASIADO_GRANDE, 200, "La IA ha gastado todos los tokens de salida "
                        + "antes de responder.");
            }
            throw new ErrorIA(ErrorIA.Tipo.RESPUESTA, 200, "El modelo ha devuelto una respuesta vacía.");
        }
        return new Respuesta(texto, ent, sal, ms, cortada);
    }

    /** Mensaje y códigos del error en minúsculas (sin el resto del cuerpo, que puede repetir la consulta). */
    static String textoError(String cuerpo) {
        InfoError ie = analizar(cuerpo);
        String t = (ie.mensaje + " " + ie.pistas).trim();
        return (t.isEmpty() ? (cuerpo == null ? "" : cuerpo) : t).toLowerCase(Locale.ROOT);
    }

    /** true si todo el texto (sin razonamiento ni «```json») es un JSON completo: no se ha cortado. */
    static boolean jsonCompleto(String texto) {
        String t = limpiarPensamiento(texto).trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try {
            Json.leer(t.trim());
            return true;
        } catch (Json.ErrorJson e) {
            return false;
        }
    }

    private static boolean tieneJson(String texto) {
        try {
            Json.extraer(limpiarPensamiento(texto));
            return true;
        } catch (Json.ErrorJson e) {
            return false;
        }
    }

    /** Quita el «razonamiento en voz alta» que algunos modelos escriben antes de responder. */
    public static String limpiarPensamiento(String texto) {
        if (texto == null) {
            return "";
        }
        String t = texto.replaceAll("(?is)<(think|thinking|reasoning)>.*?</\\1>", "");
        // Razonamiento sin cerrar (respuesta cortada): se quita hasta el primer JSON
        Matcher m = Pattern.compile("(?is)<(think|thinking|reasoning)>").matcher(t);
        if (m.find()) {
            int llave = t.indexOf('{', m.end());
            t = llave < 0 ? t.substring(0, m.start()) : t.substring(0, m.start()) + t.substring(llave);
        }
        return t.trim();
    }

    // ------------------------------------------------------------------ errores

    /** Lo que se puede sacar del cuerpo de un error. */
    static final class InfoError {
        String mensaje = "";
        final StringBuilder pistas = new StringBuilder();
        long esperaSegundos = -1;
        boolean porDia;
    }

    private static final Pattern REINTENTAR_EN = Pattern.compile(
            "(?:retry|try again) in\\s+(?:(\\d+)h)?\\s*(?:(\\d+)m(?!s))?\\s*(?:([\\d.]+)s)?\\s*(?:([\\d.]+)ms)?");
    private static final Pattern SEGUNDOS = Pattern.compile("^([\\d.]+)s$");

    static InfoError analizar(String cuerpo) {
        InfoError ie = new InfoError();
        if (cuerpo == null || cuerpo.trim().isEmpty()) {
            return ie;
        }
        Object j;
        try {
            j = Json.leer(cuerpo);
        } catch (Json.ErrorJson e) {
            ie.mensaje = recortar(cuerpo.trim().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " "), 300);
            buscarEspera(ie, ie.mensaje);
            return ie;
        }
        if (j instanceof List && !((List<?>) j).isEmpty()) {
            j = ((List<?>) j).get(0); // Gemini: [{"error": {...}}]
        }
        Object err = Json.ruta(j, "error");
        Object fuente = err instanceof Map ? err : j;
        for (Object[] ruta : new Object[][]{{"message"}, {"msg"}, {"detail"}, {"error_description"}}) {
            Object v = Json.ruta(fuente, ruta);
            if (v instanceof String && !((String) v).isEmpty()) {
                ie.mensaje = (String) v;
                break;
            }
        }
        if (ie.mensaje.isEmpty() && err instanceof String) {
            ie.mensaje = (String) err; // Ollama y LM Studio: {"error": "texto"}
        }
        if (ie.mensaje.isEmpty()) {
            Object detalle = Json.ruta(j, "detail", 0, "msg");
            if (detalle instanceof String) {
                ie.mensaje = (String) detalle;
            }
        }
        for (String k : new String[]{"code", "type", "status", "param"}) {
            Object v = Json.ruta(fuente, k);
            if (v != null) {
                ie.pistas.append(' ').append(Json.texto(v));
            }
        }
        Object tipo = Json.ruta(j, "type");
        if (tipo instanceof String) {
            ie.pistas.append(' ').append(tipo);
        }
        Object detalles = Json.ruta(fuente, "details");
        if (detalles instanceof List) {
            for (Object d : (List<?>) detalles) {
                Object razon = Json.ruta(d, "reason");
                if (razon != null) {
                    ie.pistas.append(' ').append(Json.texto(razon));
                }
                Object retraso = Json.ruta(d, "retryDelay");
                if (retraso instanceof String) {
                    Matcher m = SEGUNDOS.matcher(((String) retraso).trim());
                    if (m.matches()) {
                        ie.esperaSegundos = (long) Math.ceil(Double.parseDouble(m.group(1)));
                    }
                }
                Object violaciones = Json.ruta(d, "violations");
                if (violaciones instanceof List) {
                    for (Object v : (List<?>) violaciones) {
                        String q = Json.texto(Json.ruta(v, "quotaId")) + Json.texto(Json.ruta(v, "quotaMetric"));
                        ie.pistas.append(' ').append(q);
                        if (q.toLowerCase(Locale.ROOT).contains("perday")) {
                            ie.porDia = true;
                        }
                    }
                }
            }
        }
        if (ie.esperaSegundos < 0) {
            buscarEspera(ie, ie.mensaje);
        }
        return ie;
    }

    private static void buscarEspera(InfoError ie, String texto) {
        Matcher m = REINTENTAR_EN.matcher(texto.toLowerCase(Locale.ROOT));
        if (m.find() && (m.group(1) != null || m.group(2) != null || m.group(3) != null || m.group(4) != null)) {
            double s = 0;
            if (m.group(1) != null) {
                s += Integer.parseInt(m.group(1)) * 3600.0;
            }
            if (m.group(2) != null) {
                s += Integer.parseInt(m.group(2)) * 60.0;
            }
            if (m.group(3) != null) {
                s += Double.parseDouble(m.group(3));
            }
            if (m.group(4) != null) {
                s += Double.parseDouble(m.group(4)) / 1000.0;
            }
            ie.esperaSegundos = Math.max(1, (long) Math.ceil(s));
        }
    }

    /** Segundos de la cabecera Retry-After (número o fecha HTTP), o −1. */
    static long retryAfter(HttpResponse<String> r) {
        String ra = r.headers().firstValue("retry-after").orElse("").trim();
        if (ra.isEmpty()) {
            return -1;
        }
        try {
            return Math.max(1, (long) Math.ceil(Double.parseDouble(ra)));
        } catch (NumberFormatException e) {
            try {
                ZonedDateTime cuando = ZonedDateTime.parse(ra, DateTimeFormatter.RFC_1123_DATE_TIME);
                return Math.max(1, Duration.between(ZonedDateTime.now(cuando.getZone()), cuando).getSeconds());
            } catch (RuntimeException e2) {
                return -1;
            }
        }
    }

    ErrorIA error(HttpResponse<String> r) {
        return clasificar(r.statusCode(), r.body(), retryAfter(r));
    }

    /** Traduce un error HTTP del servicio a un {@link ErrorIA} con un mensaje claro. */
    ErrorIA clasificar(int e, String cuerpo, long retryAfter) {
        InfoError ie = analizar(cuerpo);
        String detalle = ie.mensaje;
        String d = (detalle + " " + ie.pistas).toLowerCase(Locale.ROOT);
        String sufijo = detalle.isEmpty() ? "" : "\n(El servicio dice: «" + recortar(detalle, 240) + "»)";
        String nombre = proveedor.getNombre();
        long espera = retryAfter > 0 ? retryAfter : ie.esperaSegundos;
        boolean ollama = proveedor.getProtocolo() == ProveedorIA.Protocolo.OLLAMA;

        if (e == 401 || d.contains("api_key_invalid") || d.contains("api key not valid") || d.contains("invalid api key")
                || d.contains("incorrect api key") || d.contains("invalid x-api-key") || d.contains("authentication_error")
                || d.contains("invalid_api_key") || d.contains("unauthorized") && e != 429) {
            return new ErrorIA(ErrorIA.Tipo.CLAVE, e, "La clave de " + nombre + " no es válida (error " + e
                    + "). Revísala en IA → Modo de trabajo." + sufijo);
        }
        if (e == 402 || d.contains("insufficient_quota") || d.contains("credit balance") || d.contains("insufficient balance")
                || d.contains("insufficient credits") || d.contains("insufficient_credits") || d.contains("negative balance")) {
            return new ErrorIA(ErrorIA.Tipo.SALDO, e, "No queda saldo en la cuenta de " + nombre + " (error " + e
                    + "). Recarga saldo o elige un servicio gratuito." + sufijo);
        }
        if (e == 403) {
            return new ErrorIA(ErrorIA.Tipo.CLAVE, e, "La clave de " + nombre + " no tiene permiso para usar este "
                    + "servicio o modelo (error 403). Revísala en IA → Modo de trabajo." + sufijo);
        }
        if (d.contains("requires more system memory") || d.contains("out of memory") || d.contains("insufficient memory")) {
            return new ErrorIA(ErrorIA.Tipo.MODELO, e, "El modelo «" + modelo + "» necesita más memoria de la que hay "
                    + "libre en este ordenador. Elige un modelo más pequeño (por ejemplo «llama3.2:3b») o cierra otros "
                    + "programas." + sufijo);
        }
        if (e == 404) {
            if (ollama || d.contains("model")) {
                return new ErrorIA(ErrorIA.Tipo.MODELO, e, "El modelo «" + modelo + "» no existe en " + nombre
                        + (ollama ? " (no está descargado: ejecuta «ollama pull " + modelo + "» en una terminal)"
                        : ". Pulsa «Cargar modelos» en IA → Modo de trabajo y elige otro") + "." + sufijo);
            }
            return new ErrorIA(ErrorIA.Tipo.PETICION, e, "La dirección " + url + " no responde como una API de IA "
                    + "(error 404)." + sufijo);
        }
        if (e == 413 || d.contains("context length") || d.contains("context_length") || d.contains("maximum context")
                || d.contains("too many tokens") || d.contains("prompt is too long") || d.contains("request too large")
                || d.contains("reduce the length") || d.contains("input is too long") || d.contains("exceeds the context")
                || d.contains("request_too_large") || d.contains("reduce your message size")) {
            return new ErrorIA(ErrorIA.Tipo.DEMASIADO_GRANDE, e, "La consulta es demasiado grande para este modelo o "
                    + "para el límite por minuto de la cuenta (error " + e + ")." + sufijo);
        }
        if (e == 429 || d.contains("resource_exhausted")) {
            boolean diaria = ie.porDia || d.contains("per day") || d.contains("per-day") || d.contains("perday")
                    || d.contains("daily") || d.contains("(rpd)") || d.contains("(tpd)") || d.contains("free-models-per-day")
                    || d.contains("monthly") || d.contains("per month");
            if ((espera > MAX_ESPERA_SEGUNDOS) || (espera < 0 && diaria)) {
                String cuando = espera > MAX_ESPERA_SEGUNDOS ? " (se podrá seguir dentro de unas " + horas(espera) + ")" : "";
                return new ErrorIA(ErrorIA.Tipo.CUOTA, e, "Se ha agotado la cuota de " + nombre + cuando
                        + ". Espera a que se renueve, pasa al plan de pago o elige otro servicio (error " + e + ")."
                        + sufijo, espera);
            }
            return new ErrorIA(ErrorIA.Tipo.LIMITE, e, nombre + " pide ir más despacio (error " + e + ")." + sufijo, espera);
        }
        if (e == 400 || e == 422) {
            if (d.contains("usage limit") || d.contains("spend limit") || d.contains("regain access")) {
                return new ErrorIA(ErrorIA.Tipo.CUOTA, e, "Se ha alcanzado el límite de gasto de la cuenta de " + nombre
                        + ". Súbelo en la web del servicio o elige otro (error " + e + ")." + sufijo);
            }
            if (d.contains("model") && (d.contains("not found") || d.contains("does not exist") || d.contains("invalid model")
                    || d.contains("unknown model") || d.contains("decommissioned") || d.contains("no longer supported")
                    || d.contains("model_not_found") || d.contains("not a valid model") || d.contains("does not support chat"))) {
                return new ErrorIA(ErrorIA.Tipo.MODELO, e, "El servicio rechaza el modelo «" + modelo + "» (error "
                        + e + "). Elige otro en IA → Modo de trabajo." + sufijo);
            }
            return new ErrorIA(ErrorIA.Tipo.PETICION, e, "El servicio ha rechazado la consulta (error " + e + ")." + sufijo);
        }
        if (e >= 500 || e == 408 || e == 409 || e == 425) {
            return new ErrorIA(ErrorIA.Tipo.SERVIDOR, e, nombre + " tiene un problema pasajero (error " + e + ")." + sufijo,
                    espera);
        }
        return new ErrorIA(ErrorIA.Tipo.PETICION, e, "Respuesta inesperada de " + nombre + " (error " + e + ")." + sufijo);
    }

    private static String horas(long segundos) {
        if (segundos < 3600) {
            return Math.max(1, segundos / 60) + " min";
        }
        long h = Math.round(segundos / 3600.0);
        return h + (h == 1 ? " hora" : " horas");
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void dormir(long ms) throws ErrorIA {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrorIA(ErrorIA.Tipo.CANCELADO, 0, "Cancelado.");
        }
    }

    private static void comprobarCancelado() throws ErrorIA {
        if (Thread.currentThread().isInterrupted()) {
            throw new ErrorIA(ErrorIA.Tipo.CANCELADO, 0, "Cancelado.");
        }
    }

    private static Map<String, Object> esquemaPrueba() {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("type", "boolean");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("ok", ok);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "object");
        e.put("properties", props);
        List<Object> req = new ArrayList<>();
        req.add("ok");
        e.put("required", req);
        e.put("additionalProperties", false);
        return Collections.unmodifiableMap(e);
    }
}
