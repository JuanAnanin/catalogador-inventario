import catalogador.ia.AsistenteIA;
import catalogador.ia.ClienteIA;
import catalogador.ia.ConfigIA;
import catalogador.ia.PropuestaIA;
import catalogador.ia.PropuestasGuardadas;
import catalogador.ia.ProveedorIA;
import catalogador.logica.Catalogos;
import catalogador.logica.ImportadorCatalogos;
import catalogador.logica.ImportadorInventario;
import catalogador.logica.Memoria;
import catalogador.logica.Sugerencia;
import catalogador.modelo.Grupo;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TipoLista;
import catalogador.util.Json;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Pruebas del modo IA contra el servidor simulado (pruebas/mock_ia.py), que imita
 * las particularidades documentadas de cada servicio (OpenAI, Gemini, Anthropic,
 * Groq, Mistral, DeepSeek, OpenRouter, LM Studio, Cohere y la API propia de Ollama)
 * y provoca los errores habituales según la clave. Con el registro del servidor se
 * comprueba también qué parámetros se envían a cada uno.
 * <p>
 * Uso: java -cp clases:. PruebaIA catalogos.xlsx inventario.xlsx puerto [registro_del_servidor] [--anthropic-real]
 */
public class PruebaIA {

    static int fallos;
    static int correctas;
    static String raiz;
    static Path registro;
    static Catalogos cats;
    static Memoria mem;
    static List<Grupo> grupos;
    static Proyecto proyecto;

    static void comprobar(boolean ok, String que) {
        if (ok) {
            correctas++;
            System.out.println("  ✔ " + que);
        } else {
            fallos++;
            System.out.println("  ✘ " + que);
        }
    }

    public static void main(String[] a) throws Exception {
        File cat = new File(a[0]);
        File inv = new File(a[1]);
        raiz = "http://127.0.0.1:" + a[2];
        registro = a.length > 3 && !a[3].startsWith("--") ? Paths.get(a[3]) : null;
        boolean anthropicReal = Arrays.asList(a).contains("--anthropic-real");
        String url = raiz + "/v1";
        Path tmp = Files.createTempDirectory("pruebaIA");

        System.out.println("== JSON");
        Object j = Json.leer("{\"a\":[1,2.5,\"x\\n\\u00e1\",true,null],\"b\":{\"c\":\"d\"}}");
        comprobar(Json.escribir(j).equals("{\"a\":[1,2.5,\"x\\ná\",true,null],\"b\":{\"c\":\"d\"}}"), "leer y escribir JSON");
        Map<String, Object> m = Json.extraerObjeto("Claro:\n```json\n{\"resultados\": [{\"id\": \"g1\"}]}\n```\nSaludos {x}");
        comprobar(m.containsKey("resultados"), "rescatar el JSON de una respuesta con texto alrededor");
        comprobar(Json.extraer("Aquí va: [{\"id\":\"g1\"}]") instanceof List, "rescatar también una lista JSON suelta");
        comprobar(ClienteIA.limpiarPensamiento("<think>{no}</think>{\"ok\":1}").equals("{\"ok\":1}"),
                "quitar el razonamiento <think>…</think>");
        comprobar(ClienteIA.limpiarPensamiento("<think>pienso y pienso {\"ok\":1}").equals("{\"ok\":1}"),
                "quitar un razonamiento sin cerrar (respuesta cortada)");
        String motivo = AsistenteIA.limpiarMotivo("El candidato c1 corresponde al Mezclador de amalgama dental (c1), "
                + "mejor que la opción c12. Modelo Citroën C3.");
        comprobar(motivo.equals("El candidato corresponde al Mezclador de amalgama dental, mejor que la opción. "
                + "Modelo Citroën C3."), "quitar del motivo los id internos que cita el modelo (gpt-oss): «" + motivo + "»");

        System.out.println("== Proveedores y configuración");
        comprobar(ProveedorIA.TODOS.size() >= 15, "hay " + ProveedorIA.TODOS.size() + " servicios configurados");
        for (ProveedorIA.Categoria c : ProveedorIA.Categoria.values()) {
            comprobar(!ProveedorIA.deCategoria(c).isEmpty(), "categoría «" + c.titulo() + "»: "
                    + ProveedorIA.deCategoria(c).size());
        }
        boolean pasos = true;
        for (ProveedorIA p : ProveedorIA.TODOS) {
            pasos &= !p.getPasos().isEmpty() && (p.getCategoria() == ProveedorIA.Categoria.PERSONALIZADA
                    || p.getUrlClave().startsWith("https://"));
        }
        comprobar(pasos, "cada servicio tiene su página y los pasos para conseguir la clave (o instalarlo)");
        ProveedorIA ollama = ProveedorIA.porId("ollama");
        comprobar(ollama.getProtocolo() == ProveedorIA.Protocolo.OLLAMA
                        && new ClienteIA(ollama, "http://localhost:11434/v1/", "", "qwen3:8b").getUrl().equals("http://localhost:11434"),
                "Ollama usa su API propia (una dirección antigua terminada en /v1 se corrige sola)");
        ConfigIA cfg = new ConfigIA(tmp);
        comprobar(cfg.getModo() == ConfigIA.Modo.MANUAL && cfg.esPrimeraVez(), "por defecto: modo manual, primera vez");
        ProveedorIA groq = ProveedorIA.porId("groq");
        cfg.setModo(ConfigIA.Modo.IA);
        cfg.setProveedor(groq);
        cfg.setClave(groq, "  gsk_123456789abcdef  ");
        cfg.setModelo(groq, "llama-3.3-70b-versatile");
        cfg.guardar();
        ConfigIA cfg2 = new ConfigIA(tmp);
        comprobar(cfg2.getModo() == ConfigIA.Modo.IA && cfg2.getProveedor() == groq
                && cfg2.getClave(groq).equals("gsk_123456789abcdef"), "se guarda y se recupera (modo, servicio, clave)");
        comprobar(ConfigIA.ocultar("gsk_123456789abcdef").equals("gsk_…cdef"), "la clave se muestra oculta");
        comprobar(cfg2.estaListo(groq) && !cfg2.estaListo(ProveedorIA.porId("openai")), "listo solo con clave");
        comprobar(cfg2.estaListo(ollama), "Ollama no necesita clave");
        comprobar(cfg2.getLote(ollama) == 4 && cfg2.getLote(groq) == 4 && cfg2.getLote(ProveedorIA.porId("openai")) == 8,
                "grupos por consulta: 4 en los locales y en Groq gratis, 8 en los demás");
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permisos = Files.getPosixFilePermissions(cfg2.getFichero());
            comprobar(permisos.equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),
                    "ia.properties solo legible por su dueño (rw-------)");
        } catch (UnsupportedOperationException e) {
            System.out.println("  (sistema sin permisos POSIX)");
        }

        System.out.println("== Datos de prueba");
        ImportadorCatalogos.Resultado rc = ImportadorCatalogos.leer(cat);
        cats = new Catalogos(tmp.resolve("catalogos"));
        Files.createDirectories(tmp.resolve("catalogos"));
        cats.reemplazar(TipoLista.GMDN, rc.gmdn, cat.getName(), rc.hojaGmdn);
        cats.reemplazar(TipoLista.MANTENIMIENTO, rc.mantenimiento, cat.getName(), rc.hojaMantenimiento);
        mem = new Memoria(tmp);
        mem.cargar();
        proyecto = ImportadorInventario.importar(inv, ImportadorInventario.detectar(inv), true, null);
        grupos = proyecto.getGrupos();
        System.out.println("  " + grupos.size() + " grupos, catálogos GMDN " + cats.total(TipoLista.GMDN)
                + " / Mant. " + cats.total(TipoLista.MANTENIMIENTO));

        System.out.println("== Formato OpenAI genérico (servidor simulado)");
        ProveedorIA pers = ProveedorIA.porId("personalizado");
        ClienteIA cli = new ClienteIA(pers, url, "buena", "modelo-simulado");
        List<String> modelos = cli.modelos();
        comprobar(modelos.contains("modelo-simulado") && !modelos.contains("text-embedding-3-small"),
                "lista de modelos (sin los de vectores): " + modelos);
        comprobar(cli.probar().startsWith("Conexión correcta"), "probar conexión");
        AsistenteIA asis = new AsistenteIA(cli, cats, mem);
        long t0 = System.nanoTime();
        List<Grupo> lote = grupos.subList(0, Math.min(8, grupos.size()));
        AsistenteIA.Resultado r = asis.proponer(lote, "11/12/2026 10:00");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        comprobar(r.getPropuestas().size() == lote.size(), "una propuesta por grupo (" + r.getPropuestas().size()
                + " en " + ms + " ms, " + r.getConsultas() + (r.getConsultas() == 1 ? " consulta)" : " consultas)"));
        for (PropuestaIA x : r.getPropuestas()) {
            Grupo g = proyecto.getGrupo(x.getClaveGrupo());
            System.out.println("     " + g.getNombre() + "  →  " + x);
            if (x.tieneTermino()) {
                comprobar(cats.porCodigo(x.getLista(), x.getCodigo()) != null, "el término propuesto existe en el catálogo");
            }
        }
        comprobar(cli.getTokensEntrada() > 0, "se cuentan los tokens usados: " + cli.getTokensEntrada() + " + "
                + cli.getTokensSalida());

        System.out.println("== Guardar propuestas en el proyecto");
        PropuestasGuardadas pg = new PropuestasGuardadas(tmp);
        pg.guardar(r.getPropuestas());
        PropuestasGuardadas pg2 = new PropuestasGuardadas(tmp);
        pg2.cargar();
        comprobar(pg2.tamano() == r.getPropuestas().size(), "se recuperan " + pg2.tamano() + " propuestas tras reabrir");
        String clave0 = lote.get(0).getClave();
        pg2.olvidar(clave0);
        PropuestasGuardadas pg3 = new PropuestasGuardadas(tmp);
        pg3.cargar();
        comprobar(!pg3.tiene(clave0) && pg3.tamano() == r.getPropuestas().size() - 1, "olvidar una propuesta");

        System.out.println("== Combinar con la memoria");
        Sugerencia memS = new Sugerencia(TipoLista.MANTENIMIENTO, "SILLA", "SILLA", 0.80, null);
        Sugerencia iaS = Sugerencia.deIA(TipoLista.MANTENIMIENTO, "SILLA", "SILLA", 90, "es una silla", "x");
        List<Sugerencia> comb = Sugerencia.combinar(Arrays.asList(memS), iaS, 3);
        comprobar(comb.size() == 1 && comb.get(0).getOrigen() == Sugerencia.Origen.MEMORIA_E_IA
                && comb.get(0).porcentaje() == 92, "memoria e IA coinciden → una sola sugerencia al 92 %");
        Sugerencia iaOtra = Sugerencia.deIA(TipoLista.GMDN, "10003", "Faja", 95, "", "x");
        comb = Sugerencia.combinar(Arrays.asList(memS), iaOtra, 3);
        comprobar(comb.size() == 2 && comb.get(0).esDeIA(), "no coinciden → se ordenan por porcentaje");

        System.out.println("== Cada servicio con sus particularidades (simulados según su documentación)");
        servicio("OpenAI", "openai", "/openai/v1", "gpt-6-luna",
                "response_format=json_schema strict=true max_completion_tokens sin:temperature sin:max_tokens reasoning_effort=low");
        servicio("OpenAI (modelo grande)", "openai", "/openai/v1", "gpt-6-astra",
                "response_format=json_schema max_completion_tokens reasoning_effort=low sin:temperature");
        servicio("Google Gemini", "gemini", "/gemini/v1beta/openai", "gemini-3.8-flash",
                "response_format=json_schema reasoning_effort=low sin:temperature sin:max_tokens sin:max_completion_tokens");
        servicio("Google Gemini (propuesto, Flash-Lite)", "gemini", "/gemini/v1beta/openai", "gemini-3.5-flash-lite",
                "response_format=json_schema reasoning_effort=low sin:temperature sin:max_tokens sin:max_completion_tokens");
        servicio("Anthropic (Claude)", "anthropic", "/anthropic/v1", "claude-haiku-5-5",
                "output_config.effort=low max_tokens anthropic-version sin:temperature sin:response_format");
        servicio("Groq · llama", "groq", "/groq/openai/v1", "llama-3.3-70b-versatile",
                "response_format=json_object temperature=0 max_completion_tokens sin:reasoning_effort");
        servicio("Groq · gpt-oss", "groq", "/groq/openai/v1", "openai/gpt-oss-120b",
                "response_format=json_object reasoning_effort=low include_reasoning=false sin:temperature");
        servicio("Groq · qwen", "groq", "/groq/openai/v1", "qwen/qwen3.8-27b",
                "reasoning_effort=none temperature=0");
        servicio("Mistral", "mistral", "/mistral/v1", "mistral-small-latest",
                "response_format=json_object reasoning_effort=none temperature=0 max_tokens");
        servicio("DeepSeek", "deepseek", "/deepseek", "deepseek-flash",
                "response_format=json_object thinking.type=disabled temperature=0");
        servicio("OpenRouter (gratis)", "openrouter-gratis", "/openrouter/api/v1", "openrouter/free",
                "response_format=json_object reasoning.effort=low x-openrouter-title");
        servicio("LM Studio", "lmstudio", "/lmstudio/v1", "qwen3-8b",
                "response_format=json_schema temperature=0");
        servicio("Cohere", "cohere", "/cohere/compatibility/v1", "command-a-plus-05-2026",
                "response_format=json_object reasoning_effort=none");
        servicio("Cerebras · gpt-oss", "cerebras", "/cerebras/v1", "gpt-oss-120b",
                "response_format=json_object reasoning_effort=low max_completion_tokens sin:temperature");
        servicio("xAI (Grok)", "xai", "/xai/v1", "grok-4.7",
                "response_format=json_object reasoning_effort=low max_tokens sin:temperature");
        servicio("Hugging Face · gpt-oss", "huggingface", "/huggingface/v1", "openai/gpt-oss-120b",
                "response_format=json_object reasoning_effort=low max_tokens sin:temperature");
        servicio("OpenRouter (de pago)", "openrouter", "/openrouter/api/v1", "openai/gpt-oss-120b",
                "response_format=json_object reasoning.effort=low max_tokens sin:temperature x-openrouter-title");
        List<String> mc = new ClienteIA(ProveedorIA.porId("cohere"), raiz + "/cohere/compatibility/v1", "buena", "x").modelos();
        comprobar(mc.equals(Arrays.asList("command-a-plus-05-2026")),
                "Cohere: la lista de modelos se pide a su API propia (sin los de vectores): " + mc);
        List<String> mg = new ClienteIA(ProveedorIA.porId("gemini"), raiz + "/gemini/v1beta/openai", "buena", "x").modelos();
        comprobar(mg.equals(Arrays.asList("gemini-3.1-flash-lite", "gemini-3.8-flash")),
                "Gemini: lista de modelos sin «models/» ni los de vídeo, voz o vectores: " + mg);
        List<String> mo = new ClienteIA(ProveedorIA.porId("openai"), raiz + "/openai/v1", "buena", "x").modelos();
        comprobar(mo.equals(Arrays.asList("gpt-6-astra", "gpt-6-luna", "gpt-6.1-sol")),
                "OpenAI: lista de modelos solo con los de texto: " + mo);

        System.out.println("== Ollama (API propia: contexto, razonamiento y salida estructurada)");
        ClienteIA ol = new ClienteIA(ollama, raiz + "/ollama", "", "qwen3:8b");
        List<String> mol = ol.modelos();
        comprobar(mol.contains("qwen3:8b") && mol.contains("llama3.1:8b") && !mol.contains("nomic-embed-text:latest"),
                "lista de modelos descargados (sin los de vectores): " + mol.size() + " modelos");
        comprobar(ol.probar().startsWith("Conexión correcta"), "probar conexión con qwen3:8b");
        servicio("Ollama · qwen3:8b (piensa; se apaga)", "ollama", "/ollama", "qwen3:8b",
                "format=esquema think=false stream=false options.temperature=0 options.num_ctx>=8192 options.num_predict");
        servicio("Ollama · gpt-oss:20b (solo baja a «low»)", "ollama", "/ollama", "gpt-oss:20b", "think=low format=esquema");
        servicio("Ollama · modelo que piensa siempre (con think=false responde vacío → se repite con «low»)",
                "ollama", "/ollama", "razonador:7b", "think=low format=esquema");
        servicio("Ollama · deepseek-r1:8b (<think> en el texto)", "ollama", "/ollama", "deepseek-r1:8b", "think=false");
        servicio("Ollama · llama3.1:8b (no piensa)", "ollama", "/ollama", "llama3.1:8b", "sin:think format=esquema");
        servicio("Ollama · qwen3.5:9b (piensa; se apaga)", "ollama", "/ollama", "qwen3.5:9b", "think=false format=esquema");
        servicio("Ollama · gemma4:e4b (piensa; se apaga)", "ollama", "/ollama", "gemma4:e4b", "think=false format=esquema");
        servicio("Ollama · gemma3:12b", "ollama", "/ollama", "gemma3:12b", "sin:think");
        servicio("Ollama · llama3.2:3b (pequeño)", "ollama", "/ollama", "llama3.2:3b", "sin:think format=esquema");
        servicio("Ollama · mistral (sin etiqueta)", "ollama", "/ollama", "mistral", "format=esquema");
        ClienteIA chico = new ClienteIA(ollama, raiz + "/ollama", "", "phi3:mini");
        AsistenteIA ach = new AsistenteIA(chico, cats, mem);
        AsistenteIA.Resultado rch = ach.proponer(lote, "x");
        comprobar(rch.getPropuestas().size() == lote.size() && rch.getConsultas() >= 2,
                "modelo con contexto de 4.096 (phi3:mini): el lote de " + lote.size() + " se reparte en " + rch.getConsultas()
                        + " consultas y nada se recorta");
        comprobar(tipoError(new ClienteIA(ollama, raiz + "/ollama", "", "qwen3:14b")) == ClienteIA.ErrorIA.Tipo.MODELO,
                "modelo no descargado → «ollama pull …»");
        comprobar(errorDeProbar(new ClienteIA(ollama, raiz + "/ollama", "", "qwen3:14b")).contains("ollama pull qwen3:14b"),
                "«Probar conexión» dice qué modelo descargar y cuáles hay");
        comprobar(tipoError(new ClienteIA(ollama, raiz + "/ollama", "", "gigante:70b")) == ClienteIA.ErrorIA.Tipo.MODELO,
                "modelo que no cabe en la memoria → aviso para elegir uno más pequeño");
        comprobar(tipoError(new ClienteIA(ollama, raiz + "/ollama", "", "nomic-embed-text")) == ClienteIA.ErrorIA.Tipo.MODELO,
                "modelo de vectores (no conversa) → aviso");
        ClienteIA apagado = new ClienteIA(ollama, "http://127.0.0.1:1", "", "qwen3:8b");
        String msg = errorDeProbar(apagado);
        comprobar(msg.contains("Ollama") && msg.contains("abierto"), "Ollama cerrado → «¿Está instalado y abierto Ollama?»");

        System.out.println("== Errores y reintentos");
        comprobar(tipoError(new ClienteIA(pers, url, "mal", "m")) == ClienteIA.ErrorIA.Tipo.CLAVE,
                "clave no válida (401) → error de clave");
        comprobar(tipoError(cliente("gemini", "/gemini/v1beta/openai", "mal", "gemini-3.8-flash")) == ClienteIA.ErrorIA.Tipo.CLAVE,
                "Gemini: clave no válida (400 API_KEY_INVALID dentro de una lista) → error de clave");
        comprobar(tipoError(cliente("mistral", "/mistral/v1", "mal", "mistral-small-latest")) == ClienteIA.ErrorIA.Tipo.CLAVE,
                "Mistral: clave no válida ({\"message\": \"Unauthorized\"}) → error de clave");
        comprobar(tipoError(cliente("anthropic", "/anthropic/v1", "mal", "claude-haiku-5-5")) == ClienteIA.ErrorIA.Tipo.CLAVE,
                "Anthropic: clave no válida → error de clave");
        comprobar(tipoError(new ClienteIA(pers, url, "sinsaldo", "m")) == ClienteIA.ErrorIA.Tipo.SALDO, "sin saldo (402)");
        comprobar(tipoError(cliente("openai", "/openai/v1", "sinsaldo", "gpt-6-luna")) == ClienteIA.ErrorIA.Tipo.SALDO,
                "OpenAI: sin saldo (429 insufficient_quota) → saldo, no se reintenta");
        comprobar(tipoError(cliente("anthropic", "/anthropic/v1", "sinsaldo", "claude-haiku-5-5")) == ClienteIA.ErrorIA.Tipo.SALDO,
                "Anthropic: «credit balance is too low» (400) → saldo");
        comprobar(tipoError(cliente("deepseek", "/deepseek", "sinsaldo", "deepseek-flash")) == ClienteIA.ErrorIA.Tipo.SALDO,
                "DeepSeek: «Insufficient Balance» (402) → saldo");
        comprobar(tipoError(new ClienteIA(pers, url, "cuota", "m")) == ClienteIA.ErrorIA.Tipo.CUOTA,
                "cuota diaria agotada (429 por día)");
        ClienteIA gq = cliente("gemini", "/gemini/v1beta/openai", "cuota", "gemini-3.8-flash");
        comprobar(tipoError(gq) == ClienteIA.ErrorIA.Tipo.CUOTA && ultimoMensaje.contains("14 horas"),
                "Gemini: cuota diaria (retryDelay 50585s) → cuota, «se podrá seguir dentro de unas 14 horas»");
        comprobar(tipoError(cliente("openrouter-gratis", "/openrouter/api/v1", "cuota", "openrouter/free")) == ClienteIA.ErrorIA.Tipo.CUOTA,
                "OpenRouter: «free-models-per-day» → cuota");
        comprobar(tipoError(new ClienteIA(pers, url, "buena", "no-existe")) == ClienteIA.ErrorIA.Tipo.MODELO,
                "modelo inexistente (404)");
        comprobar(tipoError(new ClienteIA(pers, "http://127.0.0.1:1/v1", "x", "m")) == ClienteIA.ErrorIA.Tipo.RED,
                "servicio apagado → error de conexión");
        long t1 = System.nanoTime();
        comprobar(tipoError(new ClienteIA(pers, url, "lento429", "m")) == null,
                "límite por minuto (429 + Retry-After) → espera y reintenta (" + (System.nanoTime() - t1) / 1_000_000 + " ms)");
        t1 = System.nanoTime();
        comprobar(tipoError(cliente("gemini", "/gemini/v1beta/openai", "lento429", "gemini-3.8-flash")) == null,
                "Gemini: límite por minuto (retryDelay 1s en el cuerpo) → espera y reintenta ("
                        + (System.nanoTime() - t1) / 1_000_000 + " ms)");
        t1 = System.nanoTime();
        comprobar(tipoError(cliente("groq", "/groq/openai/v1", "lento429", "llama-3.3-70b-versatile")) == null,
                "Groq: «Please try again in 1.5s» → espera y reintenta (" + (System.nanoTime() - t1) / 1_000_000 + " ms)");
        comprobar(tipoError(cliente("anthropic", "/anthropic/v1", "sobrecarga", "claude-haiku-5-5")) == null,
                "Anthropic: sobrecarga (529) → reintenta");
        comprobar(tipoError(new ClienteIA(pers, url, "fallo500", "m")) == null, "fallo pasajero (500) → reintenta");
        comprobar(tipoError(new ClienteIA(pers, url, "nojson", "m")) == null,
                "modelo sin «modo JSON» (400) → repite sin él y lee el JSON entre ``` ```");
        ClienteIA sinSystem = new ClienteIA(pers, url, "sinsystem", "m");
        comprobar(tipoError(sinSystem) == null && sinSystem.getParametrosQuitados().contains("system"),
                "modelo que no admite el rol «system» → las instrucciones van dentro del mensaje");
        comprobar(tipoError(new ClienteIA(pers, url, "piensa", "m")) == null, "respuesta con <think>…</think> delante");
        comprobar(tipoError(cliente("deepseek", "/deepseek", "vacia", "deepseek-flash")) == null,
                "DeepSeek: respuesta vacía en modo JSON → se repite una vez");
        comprobar(tipoError(cliente("groq", "/groq/openai/v1", "jsonmal", "llama-3.3-70b-versatile")) == null,
                "Groq: «json_validate_failed» → se repite");
        ClienteIA tpm = cliente("groq", "/groq/openai/v1", "tpm", "openai/gpt-oss-120b");
        AsistenteIA atpm = new AsistenteIA(tpm, cats, mem);
        AsistenteIA.Resultado rtpm = atpm.proponer(lote, "x");
        comprobar(rtpm.getPropuestas().size() == lote.size() && atpm.getLoteMaximo() < lote.size(),
                "Groq: consulta mayor que el límite por minuto (413) → se parte el lote y se recuerda el tamaño ("
                        + atpm.getLoteMaximo() + (atpm.getLoteMaximo() == 1 ? " grupo)" : " grupos)"));
        ClienteIA corta = new ClienteIA(pers, url, "corta", "m");
        AsistenteIA acorta = new AsistenteIA(corta, cats, mem);
        AsistenteIA.Resultado rcorta = acorta.proponer(lote, "x");
        comprobar(rcorta.getPropuestas().size() == lote.size(),
                "respuesta cortada por el límite de salida → se pide en lotes más pequeños (" + rcorta.getConsultas()
                        + " consultas)");
        ClienteIA antiguo = cliente("anthropic", "/anthropic/v1", "buena", "claude-antiguo");
        comprobar(tipoError(antiguo) == null && antiguo.getParametrosQuitados().contains("output_config"),
                "Anthropic: modelo que no admite «effort» → se repite sin él");

        if (anthropicReal) {
            System.out.println("== API real de Anthropic (sin clave válida)");
            ClienteIA real = new ClienteIA(ProveedorIA.porId("anthropic"), ProveedorIA.porId("anthropic").getUrlBase(),
                    "sk-ant-clave-de-prueba-no-valida", "claude-haiku-5-5");
            ClienteIA.ErrorIA.Tipo t = tipoError(real);
            comprobar(t == ClienteIA.ErrorIA.Tipo.CLAVE, "api.anthropic.com responde 401 y se muestra como error de clave");
            try {
                real.modelos();
                comprobar(false, "la lista de modelos sin clave válida debe fallar");
            } catch (ClienteIA.ErrorIA e) {
                comprobar(e.getTipo() == ClienteIA.ErrorIA.Tipo.CLAVE, "«Cargar modelos» con clave no válida → error de clave");
            }
        }

        System.out.println();
        System.out.println(fallos == 0 ? "TODO OK (" + correctas + " comprobaciones)" : fallos + " FALLOS");
        System.exit(fallos == 0 ? 0 : 1);
    }

    static ClienteIA cliente(String id, String ruta, String clave, String modelo) {
        return new ClienteIA(ProveedorIA.porId(id), raiz + ruta, clave, modelo);
    }

    /** Pide 4 grupos al servicio simulado y comprueba los parámetros enviados (según el registro). */
    static void servicio(String nombre, String id, String ruta, String modelo, String esperado) throws Exception {
        ClienteIA c = cliente(id, ruta, "buena", modelo);
        try {
            AsistenteIA.Resultado r = new AsistenteIA(c, cats, mem).proponer(grupos.subList(0, Math.min(4, grupos.size())), "x");
            int conTermino = 0;
            boolean existen = true;
            for (PropuestaIA p : r.getPropuestas()) {
                if (p.tieneTermino()) {
                    conTermino++;
                    existen &= cats.porCodigo(p.getLista(), p.getCodigo()) != null;
                }
            }
            String params = registro == null ? "" : comprobarParametros(id, modelo, esperado);
            comprobar(r.getPropuestas().size() == Math.min(4, grupos.size()) && conTermino > 0 && existen && params.isEmpty(),
                    nombre + " · " + modelo + ": " + r.getPropuestas().size() + " propuestas (" + conTermino
                            + " con término) en " + r.getConsultas() + (r.getConsultas() == 1 ? " consulta" : " consultas")
                            + (registro == null ? "" : "; parámetros: " + esperado) + (params.isEmpty() ? "" : " → FALTA " + params)
                            + (c.getParametrosQuitados().isEmpty() ? "" : "; rechazados y quitados: " + c.getParametrosQuitados()));
        } catch (ClienteIA.ErrorIA e) {
            comprobar(false, nombre + " · " + modelo + ": " + e.getMessage());
        }
    }

    /** Busca en el registro del servidor la última consulta de ese servicio y modelo y comprueba los parámetros. */
    @SuppressWarnings("unchecked")
    static String comprobarParametros(String id, String modelo, String esperado) throws Exception {
        String perfil = id.startsWith("openrouter") ? "openrouter" : id;
        Map<String, Object> ultima = null;
        for (String l : Files.readAllLines(registro, StandardCharsets.UTF_8)) {
            Map<String, Object> e = (Map<String, Object>) Json.leer(l);
            String mod = Json.texto(e.get("modelo"));
            if (perfil.equals(e.get("perfil")) && (mod.equals(modelo) || mod.equals(modelo + ":latest"))
                    && !String.valueOf(e.get("ruta")).endsWith("/api/show")) {
                ultima = e;
            }
        }
        if (ultima == null) {
            return "sin consultas en el registro";
        }
        List<String> faltan = new ArrayList<>();
        Map<String, Object> extras = (Map<String, Object>) ultima.get("extras");
        Map<String, Object> opciones = (Map<String, Object>) ultima.get("options");
        Map<String, Object> cab = (Map<String, Object>) ultima.get("cabeceras");
        for (String x : esperado.split(" ")) {
            boolean ok;
            if (x.startsWith("sin:")) {
                String k = x.substring(4);
                ok = !extras.containsKey(k) && !(k.equals("response_format") && ultima.get("response_format") != null);
            } else if (x.equals("response_format=json_schema") || x.equals("response_format=json_object")) {
                ok = x.endsWith(Json.texto(ultima.get("response_format"))) && ultima.get("response_format") != null;
            } else if (x.equals("strict=true")) {
                ok = Boolean.TRUE.equals(ultima.get("strict"));
            } else if (x.startsWith("format=")) {
                ok = x.substring(7).equals(Json.texto(ultima.get("format")));
            } else if (x.equals("anthropic-version")) {
                ok = "2023-06-01".equals(cab.get("anthropic-version"));
            } else if (x.equals("x-openrouter-title")) {
                ok = cab.get("x-openrouter-title") != null;
            } else if (x.startsWith("options.")) {
                String k = x.substring(8);
                if (k.contains(">=")) {
                    String[] p = k.split(">=");
                    ok = opciones != null && Json.entero(opciones.get(p[0]), 0) >= Integer.parseInt(p[1]);
                } else if (k.contains("=")) {
                    String[] p = k.split("=");
                    ok = opciones != null && Json.texto(opciones.get(p[0])).equals(p[1]);
                } else {
                    ok = opciones != null && opciones.containsKey(k);
                }
            } else if (x.contains(".") && x.contains("=")) {
                String[] p = x.split("=");
                String[] r = p[0].split("\\.");
                ok = Json.texto(Json.ruta(extras, (Object[]) r)).equals(p[1]);
            } else if (x.contains("=")) {
                String[] p = x.split("=");
                ok = extras.containsKey(p[0]) && Json.texto(extras.get(p[0])).equals(p[1]);
            } else {
                ok = extras.containsKey(x);
            }
            if (!ok) {
                faltan.add(x);
            }
        }
        return faltan.isEmpty() ? "" : faltan.toString();
    }

    static String ultimoMensaje = "";

    static ClienteIA.ErrorIA.Tipo tipoError(ClienteIA c) {
        try {
            AsistenteIA.Resultado r = new AsistenteIA(c, cats, mem).proponer(grupos.subList(0, 2), "x");
            return r.getPropuestas().size() == 2 ? null : ClienteIA.ErrorIA.Tipo.RESPUESTA;
        } catch (ClienteIA.ErrorIA e) {
            ultimoMensaje = e.getMessage();
            System.out.println("     «" + e.getMessage().split("\n")[0] + "»");
            return e.getTipo();
        }
    }

    static String errorDeProbar(ClienteIA c) {
        try {
            return "SIN ERROR: " + c.probar();
        } catch (ClienteIA.ErrorIA e) {
            System.out.println("     «" + e.getMessage().replace("\n", " | ") + "»");
            return e.getMessage();
        }
    }
}
