package catalogador.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON mínimo, sin bibliotecas externas: lo justo para hablar con las API de IA.
 * <ul>
 *   <li>{@link #leer(String)} convierte el texto en {@code Map}, {@code List}, {@code String},
 *       {@code Double}/{@code Long}, {@code Boolean} o {@code null}.</li>
 *   <li>{@link #escribir(Object)} hace lo contrario.</li>
 *   <li>{@link #extraerObjeto(String)} rescata el primer objeto JSON de una respuesta
 *       con texto alrededor (algunos modelos lo envuelven en ```json … ```).</li>
 * </ul>
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Error de formato al leer JSON. */
    public static final class ErrorJson extends RuntimeException {
        ErrorJson(String mensaje) {
            super(mensaje);
        }
    }

    // ------------------------------------------------------------------ lectura

    public static Object leer(String texto) {
        Json p = new Json(texto);
        p.espacios();
        Object v = p.valor();
        p.espacios();
        if (p.i != p.s.length()) {
            throw new ErrorJson("Sobra texto después del JSON (posición " + p.i + ")");
        }
        return v;
    }

    /**
     * Busca el primer objeto o lista JSON completo dentro de un texto (con texto
     * o «```json» alrededor) y lo devuelve leído: un {@code Map} o una {@code List}.
     */
    public static Object extraer(String texto) {
        if (texto == null || texto.trim().isEmpty()) {
            throw new ErrorJson("Respuesta vacía");
        }
        String t = texto.trim();
        try {
            Object v = leer(t);
            if (v instanceof Map || v instanceof List) {
                return v;
            }
        } catch (ErrorJson e) {
            // se busca dentro
        }
        for (int inicio = 0; inicio < t.length(); inicio++) {
            char c = t.charAt(inicio);
            if (c != '{' && c != '[') {
                continue;
            }
            Json p = new Json(t);
            p.i = inicio;
            try {
                Object v = p.valor();
                if (v instanceof Map || (v instanceof List && !((List<?>) v).isEmpty()
                        && ((List<?>) v).get(0) instanceof Map)) {
                    return v;
                }
            } catch (ErrorJson | IndexOutOfBoundsException e) {
                // probar con la siguiente llave
            }
        }
        throw new ErrorJson("La respuesta no contiene JSON");
    }

    /**
     * Busca el primer objeto JSON completo dentro de un texto (por ejemplo,
     * «Aquí tienes: ```json {…} ``` Espero que sirva») y lo devuelve leído.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extraerObjeto(String texto) {
        if (texto == null) {
            throw new ErrorJson("Respuesta vacía");
        }
        String t = texto.trim();
        try {
            Object v = leer(t);
            if (v instanceof Map) {
                return (Map<String, Object>) v;
            }
        } catch (ErrorJson e) {
            // se busca dentro
        }
        for (int inicio = t.indexOf('{'); inicio >= 0; inicio = t.indexOf('{', inicio + 1)) {
            Json p = new Json(t);
            p.i = inicio;
            try {
                Object v = p.valor();
                if (v instanceof Map) {
                    return (Map<String, Object>) v;
                }
            } catch (ErrorJson | IndexOutOfBoundsException e) {
                // probar con la siguiente llave
            }
        }
        throw new ErrorJson("La respuesta no contiene un objeto JSON");
    }

    private Object valor() {
        espacios();
        if (i >= s.length()) {
            throw new ErrorJson("JSON incompleto");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return objeto();
            case '[':
                return lista();
            case '"':
                return cadena();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return numero();
                }
                throw new ErrorJson("Carácter inesperado «" + c + "» en la posición " + i);
        }
    }

    private Map<String, Object> objeto() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        espacios();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            espacios();
            if (s.charAt(i) != '"') {
                throw new ErrorJson("Se esperaba un nombre entre comillas en la posición " + i);
            }
            String k = cadena();
            espacios();
            esperar(':');
            m.put(k, valor());
            espacios();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new ErrorJson("Se esperaba «,» o «}» en la posición " + (i - 1));
            }
        }
    }

    private List<Object> lista() {
        List<Object> l = new ArrayList<>();
        i++;
        espacios();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(valor());
            espacios();
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw new ErrorJson("Se esperaba «,» o «]» en la posición " + (i - 1));
            }
        }
    }

    private String cadena() {
        i++; // comilla de apertura
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new ErrorJson("Texto sin cerrar");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(e); // \" \\ \/
            }
        }
    }

    private Object numero() {
        int inicio = i;
        if (s.charAt(i) == '-') {
            i++;
        }
        boolean decimal = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                decimal = true;
                i++;
            } else {
                break;
            }
        }
        String n = s.substring(inicio, i);
        try {
            return decimal ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        } catch (NumberFormatException e) {
            return Double.parseDouble(n);
        }
    }

    private void literal(String palabra) {
        if (!s.startsWith(palabra, i)) {
            throw new ErrorJson("Se esperaba «" + palabra + "» en la posición " + i);
        }
        i += palabra.length();
    }

    private void esperar(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw new ErrorJson("Se esperaba «" + c + "» en la posición " + i);
        }
        i++;
    }

    private void espacios() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    // ------------------------------------------------------------------ escritura

    public static String escribir(Object v) {
        StringBuilder b = new StringBuilder();
        escribir(v, b);
        return b.toString();
    }

    private static void escribir(Object v, StringBuilder b) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            comillas((String) v, b);
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map) {
            b.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) v).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                comillas(String.valueOf(e.getKey()), b);
                b.append(':');
                escribir(e.getValue(), b);
                if (it.hasNext()) {
                    b.append(',');
                }
            }
            b.append('}');
        } else if (v instanceof Iterable) {
            b.append('[');
            Iterator<?> it = ((Iterable<?>) v).iterator();
            while (it.hasNext()) {
                escribir(it.next(), b);
                if (it.hasNext()) {
                    b.append(',');
                }
            }
            b.append(']');
        } else if (v instanceof Object[]) {
            List<Object> l = new ArrayList<>();
            for (Object o : (Object[]) v) {
                l.add(o);
            }
            escribir(l, b);
        } else {
            comillas(v.toString(), b);
        }
    }

    private static void comillas(String s, StringBuilder b) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------------ ayudas para leer respuestas

    /** Sigue una ruta de claves y posiciones: {@code ruta(json, "choices", 0, "message", "content")}. */
    public static Object ruta(Object json, Object... pasos) {
        Object v = json;
        for (Object p : pasos) {
            if (v == null) {
                return null;
            }
            if (p instanceof Integer && v instanceof List) {
                List<?> l = (List<?>) v;
                int k = (Integer) p;
                v = k < l.size() ? l.get(k) : null;
            } else if (v instanceof Map) {
                v = ((Map<?, ?>) v).get(String.valueOf(p));
            } else {
                return null;
            }
        }
        return v;
    }

    public static String texto(Object v) {
        return v == null ? "" : v instanceof Double && ((Double) v) == Math.rint((Double) v)
                ? String.valueOf(((Double) v).longValue()) : String.valueOf(v);
    }

    public static int entero(Object v, int porDefecto) {
        if (v instanceof Number) {
            return (int) Math.round(((Number) v).doubleValue());
        }
        if (v instanceof String) {
            try {
                return (int) Math.round(Double.parseDouble(((String) v).replace("%", "").replace(',', '.').trim()));
            } catch (NumberFormatException e) {
                return porDefecto;
            }
        }
        return porDefecto;
    }
}
