package catalogador.ia;

import catalogador.logica.Catalogos;
import catalogador.logica.Memoria;
import catalogador.logica.Sugerencia;
import catalogador.modelo.Grupo;
import catalogador.modelo.TerminoOficial;
import catalogador.modelo.TipoLista;
import catalogador.util.Json;
import catalogador.util.Texto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pide a la IA el término oficial de varios grupos de equipos a la vez.
 * <p>
 * Para que la IA no se invente nombres ni códigos, el programa busca primero en
 * los catálogos cargados los términos que se parecen al equipo (los
 * «candidatos») y la IA solo puede elegir uno de ellos o decir que ninguno
 * encaja. Si ninguno encaja, propone palabras para buscar otros candidatos y se
 * le pregunta una segunda vez con ellos.
 * <p>
 * A la IA solo se envían el nombre, las otras formas de escribirlo, la marca, el
 * modelo, la referencia, el tipo de activo y el número de equipos de cada grupo,
 * junto con los candidatos. Nunca el centro ni el identificador de los equipos.
 */
public final class AsistenteIA {

    /** Candidatos por catálogo: más del catálogo que corresponde al tipo de activo. */
    static final int CANDIDATOS_PRINCIPAL = 12;
    static final int CANDIDATOS_SECUNDARIO = 6;
    private static final int LARGO_DESCRIPCION = 220;
    /** Si un grupo solo no cabe, se le envían menos candidatos y descripciones más cortas. */
    private static final int CANDIDATOS_COMPACTO = 8;
    private static final int LARGO_DESCRIPCION_COMPACTO = 100;
    /** Tokens de respuesta que se reservan por grupo (un elemento de «resultados»). */
    static final int SALIDA_POR_GRUPO = 220;

    /** Forma exacta de la respuesta (para los servicios con salida estructurada). */
    public static final Map<String, Object> ESQUEMA = esquema();

    static final String INSTRUCCIONES = String.join("\n",
            "Eres un técnico experto en inventario hospitalario y en dos catálogos oficiales de un hospital español:",
            "- GMDN: productos sanitarios y equipos de electromedicina (cada término tiene código, nombre y descripción).",
            "- Mantenimiento: mobiliario, instalaciones, informática, vehículos y demás activos (cada término tiene un nombre",
            "  único y una clasificación: tipo de activo › uso › familia › subfamilia › subgrupo).",
            "",
            "Tarea: para cada grupo de equipos, elige el término oficial correcto ENTRE LOS CANDIDATOS DE ESE GRUPO.",
            "Reglas:",
            "1. «eleccion» es el id de uno de sus candidatos (por ejemplo \"c3\"), o \"\" (vacío) si ninguno es el mismo",
            "   tipo de aparato. Nunca inventes términos ni uses candidatos de otro grupo.",
            "2. tipo_activo \"E\" significa electromedicina: casi siempre va al catálogo GMDN. Los demás tipos (B mobiliario,",
            "   T instalaciones, I informática, V vehículos…) suelen ir al de Mantenimiento.",
            "3. El nombre puede traer erratas, abreviaturas (MET. = metálico, A/A = aire acondicionado, CONF. = confidente…)",
            "   o las palabras en otro orden. La marca, el modelo y la referencia ayudan a saber qué aparato es.",
            "4. «confianza» (0-100): 90-100 si es claramente el mismo aparato; 65-89 si es probable pero hay matices;",
            "   menos de 65 si dudas.",
            "5. «motivo»: una frase corta en español que lo justifique, sin citar los id (c1, g1…): nombra el término.",
            "6. Si ninguno encaja: eleccion \"\" y en «buscar» de 1 a 3 palabras en español para buscar otros candidatos.",
            "7. «marca» y «modelo»: si vienen mal escritos o con basura, escribe la forma limpia habitual; si están bien",
            "   o no lo sabes, deja \"\".",
            "Responde SOLO con un objeto JSON, sin texto alrededor, con un elemento por grupo:",
            "{\"resultados\":[{\"id\":\"g1\",\"eleccion\":\"c3\",\"confianza\":92,\"motivo\":\"…\",\"buscar\":[],"
                    + "\"marca\":\"\",\"modelo\":\"\"}]}");

    /** Un término ofrecido a la IA para un grupo. */
    static final class Candidato {
        final String id;
        final TerminoOficial termino;

        Candidato(String id, TerminoOficial termino) {
            this.id = id;
            this.termino = termino;
        }
    }

    /** Lo devuelto por una consulta. */
    public static final class Resultado {
        private final List<PropuestaIA> propuestas = new ArrayList<>();
        private final List<Grupo> sinRespuesta = new ArrayList<>();
        private int consultas;

        public List<PropuestaIA> getPropuestas() {
            return propuestas;
        }

        /** Grupos que la IA se ha saltado (se pueden volver a pedir). */
        public List<Grupo> getSinRespuesta() {
            return sinRespuesta;
        }

        public int getConsultas() {
            return consultas;
        }
    }

    private final ClienteIA cliente;
    private final Catalogos catalogos;
    private final Memoria memoria;
    /** Búsquedas de palabras sueltas ya hechas («silla», «mesa»… se repiten mucho entre grupos). */
    private final Map<String, Catalogos.Busqueda> cachePalabras = new LinkedHashMap<String, Catalogos.Busqueda>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Catalogos.Busqueda> e) {
            return size() > 3000;
        }
    };

    /** Grupos por consulta que el servicio ha demostrado aguantar (baja si responde «demasiado grande»). */
    private volatile int loteMaximo = Integer.MAX_VALUE;

    public AsistenteIA(ClienteIA cliente, Catalogos catalogos, Memoria memoria) {
        this.cliente = cliente;
        this.catalogos = catalogos;
        this.memoria = memoria;
    }

    public ClienteIA getCliente() {
        return cliente;
    }

    /**
     * Consulta a la IA por todos los grupos dados (en una sola petición, o en
     * varias si el modelo dice que es demasiado grande).
     */
    public Resultado proponer(List<Grupo> grupos, String fecha) throws ClienteIA.ErrorIA {
        Resultado res = new Resultado();
        if (grupos.isEmpty()) {
            return res;
        }
        Map<Grupo, List<Candidato>> cands = new LinkedHashMap<>();
        for (Grupo g : grupos) {
            cands.put(g, candidatos(g, Collections.<String>emptyList(), Collections.<TerminoOficial>emptySet()));
        }
        Map<Grupo, Map<String, Object>> respuestas = consultar(cands, res);

        // Segunda vuelta: los que no encajan, con los candidatos de las palabras que propone la IA.
        Map<Grupo, List<Candidato>> otraVez = new LinkedHashMap<>();
        for (Map.Entry<Grupo, Map<String, Object>> e : respuestas.entrySet()) {
            Map<String, Object> r = e.getValue();
            if (elegido(r, cands.get(e.getKey())) == null) {
                List<String> buscar = textos(r.get("buscar"));
                if (!buscar.isEmpty()) {
                    Set<TerminoOficial> yaVistos = new java.util.HashSet<>();
                    for (Candidato c : cands.get(e.getKey())) {
                        yaVistos.add(c.termino);
                    }
                    List<Candidato> nuevos = candidatos(e.getKey(), buscar, yaVistos);
                    if (nuevos.size() > cands.get(e.getKey()).size()) {
                        otraVez.put(e.getKey(), nuevos);
                    }
                }
            }
        }
        if (!otraVez.isEmpty()) {
            Map<Grupo, Map<String, Object>> segunda = consultar(otraVez, res);
            for (Map.Entry<Grupo, Map<String, Object>> e : segunda.entrySet()) {
                respuestas.put(e.getKey(), e.getValue());
                cands.put(e.getKey(), otraVez.get(e.getKey()));
            }
        }

        String fuente = cliente.descripcion();
        for (Grupo g : grupos) {
            Map<String, Object> r = respuestas.get(g);
            if (r == null) {
                res.sinRespuesta.add(g);
                continue;
            }
            Candidato c = elegido(r, cands.get(g));
            String motivo = limpiarMotivo(Json.texto(r.get("motivo")));
            if (c == null) {
                String m = motivo.isEmpty() ? "Ninguno de los términos parecidos de los catálogos encaja." : motivo;
                if (r.get("eleccion") != null && !Json.texto(r.get("eleccion")).isEmpty()) {
                    m = "La IA eligió un término que no estaba entre los candidatos; se descarta. " + m;
                }
                res.propuestas.add(PropuestaIA.sinTermino(g.getClave(), m, fuente, fecha));
                continue;
            }
            int conf = confianza(r.get("confianza"));
            TerminoOficial t = c.termino;
            String codigo = t.getLista() == TipoLista.GMDN ? t.getCodigo() : t.getNombre();
            res.propuestas.add(new PropuestaIA(g.getClave(), t.getLista(), codigo, t.getNombre(), conf, motivo,
                    limpio(Json.texto(r.get("marca")), g.getMarca()), limpio(Json.texto(r.get("modelo")), g.getModelo()),
                    fuente, fecha));
        }
        return res;
    }

    /**
     * Quita del motivo los id internos que a veces cita el modelo («El candidato c1 corresponde…»,
     * «… (c3)»): a quien revisa no le dicen nada.
     */
    public static String limpiarMotivo(String m) {
        return m.replaceAll("\\s*\\((?:id\\s*)?[cg]\\d{1,3}\\)", "")
                .replaceAll("(?i)\\b(candidat[oa]s?|t[ée]rminos?|grupos?|opci[óo]n(?:es)?)\\s+[cg]\\d{1,3}\\b", "$1")
                .replaceAll("\\s{2,}", " ").trim();
    }

    /** Grupos por consulta que se están usando (para las pruebas y el diagnóstico). */
    public int getLoteMaximo() {
        return loteMaximo;
    }

    /**
     * Hace la consulta. Si se sabe que es demasiado grande para el servicio (por
     * los tokens estimados o porque ya lo dijo antes), la parte en dos; si el
     * servicio responde que no cabe, también, y recuerda el tamaño que funciona.
     */
    private Map<Grupo, Map<String, Object>> consultar(Map<Grupo, List<Candidato>> cands, Resultado res)
            throws ClienteIA.ErrorIA {
        if (cands.size() > 1 && (cands.size() > loteMaximo
                || estimarTokens(cands, false) > cliente.getProveedor().getLimiteEntrada())) {
            return partir(cands, res);
        }
        try {
            return consultarUnaVez(cands, res, false);
        } catch (ClienteIA.ErrorIA e) {
            if (!esDemasiadoGrande(e)) {
                throw e;
            }
            if (cands.size() < 2) {
                return consultarUnaVez(cands, res, true); // un solo grupo: con menos candidatos
            }
            loteMaximo = Math.max(1, Math.min(loteMaximo, cands.size() / 2));
            return partir(cands, res);
        }
    }

    private Map<Grupo, Map<String, Object>> partir(Map<Grupo, List<Candidato>> cands, Resultado res)
            throws ClienteIA.ErrorIA {
        Map<Grupo, List<Candidato>> a = new LinkedHashMap<>();
        Map<Grupo, List<Candidato>> b = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<Grupo, List<Candidato>> x : cands.entrySet()) {
            (i++ < cands.size() / 2 ? a : b).put(x.getKey(), x.getValue());
        }
        Map<Grupo, Map<String, Object>> r = consultar(a, res);
        r.putAll(consultar(b, res));
        return r;
    }

    static boolean esDemasiadoGrande(ClienteIA.ErrorIA e) {
        return e.getTipo() == ClienteIA.ErrorIA.Tipo.DEMASIADO_GRANDE || e.getEstado() == 413
                || (e.getTipo() == ClienteIA.ErrorIA.Tipo.PETICION
                && e.getMessage().toLowerCase(Locale.ROOT).matches("(?s).*(context|too long|too large|token|length).*"));
    }

    /** Tokens aproximados de la consulta (unos 3 caracteres por token, por lo alto). */
    int estimarTokens(Map<Grupo, List<Candidato>> cands, boolean compacto) {
        return (INSTRUCCIONES.length() + mensajeUsuario(cands, new HashMap<>(), compacto).length()) / 3;
    }

    private static String mensajeUsuario(Map<Grupo, List<Candidato>> cands, Map<String, Grupo> porId, boolean compacto) {
        List<Object> equipos = new ArrayList<>();
        int n = 0;
        for (Map.Entry<Grupo, List<Candidato>> e : cands.entrySet()) {
            String id = "g" + (++n);
            porId.put(id, e.getKey());
            equipos.add(describir(id, e.getKey(), e.getValue(), compacto));
        }
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("equipos", equipos);
        return "Grupos de equipos del inventario que hay que catalogar, con sus candidatos (JSON):\n"
                + Json.escribir(datos);
    }

    private Map<Grupo, Map<String, Object>> consultarUnaVez(Map<Grupo, List<Candidato>> cands, Resultado res,
                                                            boolean compacto) throws ClienteIA.ErrorIA {
        Map<String, Grupo> porId = new HashMap<>();
        String usuario = mensajeUsuario(cands, porId, compacto);
        ClienteIA.Respuesta r = cliente.completar(INSTRUCCIONES, usuario, ESQUEMA,
                SALIDA_POR_GRUPO * cands.size() + 300);
        res.consultas++;
        Object leido;
        try {
            leido = Json.extraer(ClienteIA.limpiarPensamiento(r.getTexto()));
        } catch (Json.ErrorJson e) {
            throw new ClienteIA.ErrorIA(ClienteIA.ErrorIA.Tipo.RESPUESTA, 200,
                    "La IA no ha respondido con el formato pedido (JSON). Prueba otro modelo.");
        }
        Object lista = null;
        if (leido instanceof List) {
            lista = leido; // solo la lista de resultados
        } else {
            Map<?, ?> obj = (Map<?, ?>) leido;
            for (String k : new String[]{"resultados", "results", "resultado", "equipos", "grupos"}) {
                if (obj.get(k) instanceof List) {
                    lista = obj.get(k);
                    break;
                }
            }
            if (lista == null && obj.containsKey("eleccion")) {
                List<Object> uno = new ArrayList<>();
                uno.add(obj); // un solo resultado, sin la lista alrededor
                lista = uno;
            }
        }
        Map<Grupo, Map<String, Object>> salida = new LinkedHashMap<>();
        if (lista instanceof List) {
            int pos = 0;
            for (Object o : (List<?>) lista) {
                pos++;
                if (!(o instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                Grupo g = porId.get(Json.texto(m.get("id")).trim().toLowerCase(Locale.ROOT));
                if (g == null && ((List<?>) lista).size() == porId.size()) {
                    g = porId.get("g" + pos); // sin id, pero en el mismo orden
                }
                if (g != null) {
                    salida.put(g, m);
                }
            }
        }
        return salida;
    }

    private static Map<String, Object> describir(String id, Grupo g, List<Candidato> cands, boolean compacto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("nombre", g.getNombre());
        List<String> otras = new ArrayList<>();
        for (Map.Entry<String, Integer> v : g.getVariantesNombre().noVacias()) {
            if (!v.getKey().equals(g.getNombre()) && otras.size() < 4) {
                otras.add(v.getKey());
            }
        }
        if (!otras.isEmpty()) {
            m.put("otras_formas_de_escribirlo", otras);
        }
        m.put("marca", g.variasMarcas() ? "(varias)" : g.getMarca());
        m.put("modelo", g.variosModelos() ? "(varios)" : g.getModelo());
        if (!g.getReferencia().isEmpty()) {
            m.put("referencia", Texto.recortar(g.getReferencia(), 80));
        }
        m.put("tipo_activo", g.getTipoActivo());
        m.put("unidades", g.getEquipos());
        List<Object> lc = new ArrayList<>();
        int largo = compacto ? LARGO_DESCRIPCION_COMPACTO : LARGO_DESCRIPCION;
        for (Candidato c : cands) {
            if (compacto && lc.size() >= CANDIDATOS_COMPACTO) {
                break;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.id);
            TerminoOficial t = c.termino;
            if (t.getLista() == TipoLista.GMDN) {
                x.put("catalogo", "GMDN");
                x.put("codigo", t.getCodigo());
                x.put("nombre", t.getNombre());
                if (!t.getDescripcion().isEmpty()) {
                    x.put("descripcion", Texto.recortar(t.getDescripcion(), largo));
                }
            } else {
                x.put("catalogo", "Mantenimiento");
                x.put("nombre", t.getNombre());
                if (!t.getDescripcion().isEmpty()) {
                    x.put("clasificacion", t.getDescripcion());
                }
            }
            lc.add(x);
        }
        m.put("candidatos", lc);
        return m;
    }

    // ------------------------------------------------------------------ candidatos

    /**
     * Términos de los catálogos que se parecen al grupo: los que encuentra el
     * buscador con el nombre (y sus otras formas, y cada palabra suelta), más
     * las sugerencias de la memoria. Del catálogo que corresponde al tipo de
     * activo se envían más.
     */
    List<Candidato> candidatos(Grupo g, List<String> palabrasExtra, Set<TerminoOficial> primero) {
        TipoLista principal = g.isElectromedicina() ? TipoLista.GMDN : TipoLista.MANTENIMIENTO;
        LinkedHashSet<TerminoOficial> elegidos = new LinkedHashSet<>(primero);
        // Lo que ya se clasificó parecido (memoria)
        if (memoria != null) {
            for (Sugerencia s : memoria.sugerir(g.getNombre(), g.getMarca(), g.getModelo(), 3)) {
                TerminoOficial t = catalogos.porCodigo(s.getLista(), s.getCodigo());
                if (t != null) {
                    elegidos.add(t);
                }
            }
        }
        List<String> consultas = new ArrayList<>(palabrasExtra);
        consultas.add(g.getNombre());
        String claveNombre = Texto.clave(g.getNombre());
        for (Map.Entry<String, Integer> v : g.getVariantesNombre().noVacias()) {
            if (consultas.size() < palabrasExtra.size() + 3 && !Texto.clave(v.getKey()).equals(claveNombre)) {
                consultas.add(v.getKey()); // otras formas de escribirlo (si de verdad son distintas)
            }
        }
        for (TipoLista l : new TipoLista[]{principal, principal.otra()}) {
            int cupo = l == principal ? CANDIDATOS_PRINCIPAL : CANDIDATOS_SECUNDARIO;
            if (!palabrasExtra.isEmpty()) {
                cupo += 6;
            }
            Map<TerminoOficial, Double> puntos = new LinkedHashMap<>();
            for (int k = 0; k < consultas.size(); k++) {
                double peso = k < palabrasExtra.size() ? 1.2 : k == palabrasExtra.size() ? 1.0 : 0.8;
                sumar(puntos, catalogos.buscar(consultas.get(k), l, false, 25), peso);
            }
            // Cada palabra suelta (para nombres largos o con palabras de más)
            for (String p : Texto.palabrasSignificativas(Texto.normalizar(g.getNombre()))) {
                if (p.length() >= 4) {
                    sumar(puntos, buscarPalabra(p, l), 0.35);
                }
            }
            if (puntos.size() < 3) {
                sumar(puntos, catalogos.buscar(g.getNombre(), l, true, 10), 0.5); // también en la descripción
            }
            List<Map.Entry<TerminoOficial, Double>> orden = new ArrayList<>(puntos.entrySet());
            orden.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            int n = 0;
            for (Map.Entry<TerminoOficial, Double> e : orden) {
                if (n >= cupo) {
                    break;
                }
                if (elegidos.add(e.getKey())) {
                    n++;
                }
            }
        }
        List<Candidato> res = new ArrayList<>();
        int i = 0;
        for (TerminoOficial t : elegidos) {
            res.add(new Candidato("c" + (++i), t));
        }
        return res;
    }

    private Catalogos.Busqueda buscarPalabra(String palabra, TipoLista l) {
        // La clave incluye el catálogo cargado: si se importa otro o se crea una entrada, no se reutiliza.
        String k = System.identityHashCode(catalogos.get(l)) + "|" + l + "|" + palabra;
        synchronized (cachePalabras) {
            Catalogos.Busqueda b = cachePalabras.get(k);
            if (b == null) {
                b = catalogos.buscar(palabra, l, false, 8);
                cachePalabras.put(k, b);
            }
            return b;
        }
    }

    private static void sumar(Map<TerminoOficial, Double> puntos, Catalogos.Busqueda b, double peso) {
        for (Catalogos.Resultado r : b.getResultados()) {
            puntos.merge(r.getTermino(), r.getPuntos() * peso, Double::sum);
        }
    }

    // ------------------------------------------------------------------ lectura de la respuesta

    /** El candidato elegido (por id; si la IA escribe el código o el nombre, también se acepta). */
    static Candidato elegido(Map<String, Object> r, List<Candidato> cands) {
        Object e = r.get("eleccion");
        if (e == null) {
            e = r.get("candidato");
        }
        if (e == null || cands == null) {
            return null;
        }
        String s = Json.texto(e).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        String id = s.toLowerCase(Locale.ROOT).replace(" ", "");
        if (id.matches("\\d+")) {
            id = "c" + id;
        }
        for (Candidato c : cands) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        String norm = Texto.normalizar(s);
        for (Candidato c : cands) {
            if (c.termino.getCodigoNorm().equals(norm) || c.termino.getNombreNorm().equals(norm)) {
                return c;
            }
        }
        return null;
    }

    static int confianza(Object v) {
        if (v instanceof Number && ((Number) v).doubleValue() > 0 && ((Number) v).doubleValue() <= 1.0) {
            return (int) Math.round(((Number) v).doubleValue() * 100); // vino como 0,92
        }
        return Math.max(0, Math.min(100, Json.entero(v, 50)));
    }

    private static List<String> textos(Object v) {
        List<String> l = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                String s = Json.texto(o).trim();
                if (!s.isEmpty() && l.size() < 3) {
                    l.add(s);
                }
            }
        } else if (v instanceof String && !((String) v).trim().isEmpty()) {
            l.add(((String) v).trim());
        }
        return l;
    }

    /** La marca o el modelo propuestos, solo si son de verdad distintos del original. */
    private static String limpio(String propuesto, String original) {
        String p = propuesto == null ? "" : propuesto.trim();
        if (p.isEmpty() || p.equalsIgnoreCase("null") || p.startsWith("(var")) {
            return "";
        }
        return p.equals(original == null ? "" : original.trim()) ? "" : p;
    }
    private static Map<String, Object> esquema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", tipo("string"));
        props.put("eleccion", tipo("string"));
        props.put("confianza", tipo("integer"));
        props.put("motivo", tipo("string"));
        Map<String, Object> buscar = tipo("array");
        buscar.put("items", tipo("string"));
        props.put("buscar", buscar);
        props.put("marca", tipo("string"));
        props.put("modelo", tipo("string"));
        Map<String, Object> item = tipo("object");
        item.put("properties", props);
        item.put("required", new ArrayList<>(props.keySet()));
        item.put("additionalProperties", false);
        Map<String, Object> lista = tipo("array");
        lista.put("items", item);
        Map<String, Object> raiz = tipo("object");
        Map<String, Object> rp = new LinkedHashMap<>();
        rp.put("resultados", lista);
        raiz.put("properties", rp);
        List<Object> req = new ArrayList<>();
        req.add("resultados");
        raiz.put("required", req);
        raiz.put("additionalProperties", false);
        return Collections.unmodifiableMap(raiz);
    }

    private static Map<String, Object> tipo(String t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", t);
        return m;
    }
}
