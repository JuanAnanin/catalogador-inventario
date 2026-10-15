package catalogador.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utilidades de texto: normalización (sin tildes, minúsculas, sin signos),
 * troceado en palabras, distancia de edición y trigramas.
 */
public final class Texto {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}+");
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

    /** Palabras que no aportan significado al comparar nombres. */
    public static final Set<String> PALABRAS_VACIAS = new HashSet<>(Arrays.asList(
            "de", "del", "la", "el", "los", "las", "para", "con", "y", "e", "en", "a", "por", "al",
            "un", "una", "o", "u", "sin", "the", "of", "for", "and", "with", "or"));

    private Texto() {
    }

    /** "Bomba de Infusión (VOLUM.)" → "bomba de infusion volum" */
    public static String normalizar(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = MARCAS_DIACRITICAS.matcher(n).replaceAll("");
        n = n.toLowerCase(Locale.ROOT);
        n = NO_ALFANUMERICO.matcher(n).replaceAll(" ").trim();
        return n;
    }

    public static String[] palabras(String normalizado) {
        if (normalizado == null || normalizado.isEmpty()) {
            return new String[0];
        }
        return normalizado.split(" ");
    }

    /** Palabras sin las "vacías". Si todas lo eran, devuelve las originales. */
    public static String[] palabrasSignificativas(String normalizado) {
        String[] todas = palabras(normalizado);
        List<String> utiles = new ArrayList<>();
        for (String p : todas) {
            if (!PALABRAS_VACIAS.contains(p)) {
                utiles.add(p);
            }
        }
        return utiles.isEmpty() ? todas : utiles.toArray(new String[0]);
    }

    /**
     * Distancia de edición (Levenshtein con transposiciones: "infusoin" está a
     * 1 de "infusion"). Tiene tope: si supera {@code max} devuelve max + 1 en
     * cuanto lo sabe, que es mucho más rápido.
     */
    public static int distancia(String a, String b, int max) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > max) {
            return max + 1;
        }
        int[] dosAtras = new int[lb + 1];
        int[] previa = new int[lb + 1];
        int[] actual = new int[lb + 1];
        for (int j = 0; j <= lb; j++) {
            previa[j] = j;
        }
        int minimoAnterior = 0;
        for (int i = 1; i <= la; i++) {
            actual[0] = i;
            int minimoFila = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                char cb = b.charAt(j - 1);
                int coste = ca == cb ? 0 : 1;
                int v = Math.min(Math.min(actual[j - 1] + 1, previa[j] + 1), previa[j - 1] + coste);
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    v = Math.min(v, dosAtras[j - 2] + 1);
                }
                actual[j] = v;
                if (v < minimoFila) {
                    minimoFila = v;
                }
            }
            if (minimoFila > max && minimoAnterior >= max) {
                return max + 1;
            }
            minimoAnterior = minimoFila;
            int[] t = dosAtras;
            dosAtras = previa;
            previa = actual;
            actual = t;
        }
        return Math.min(previa[lb], max + 1);
    }

    /** Palabras significativas ordenadas: "Pie de gotero" y "gotero, pie" dan "gotero pie". */
    public static String clave(String s) {
        String[] p = palabrasSignificativas(normalizar(s));
        java.util.Arrays.sort(p);
        return String.join(" ", p);
    }

    /** Conjunto de trigramas de caracteres ("  b", " bo", "bom", ...). */
    public static Set<String> trigramas(String s) {
        Set<String> r = new HashSet<>();
        if (s == null || s.isEmpty()) {
            return r;
        }
        String t = "  " + s + " ";
        for (int i = 0; i + 3 <= t.length(); i++) {
            r.add(t.substring(i, i + 3));
        }
        return r;
    }

    /** Coeficiente de Dice entre dos conjuntos: 2·|A∩B| / (|A|+|B|). */
    public static double dice(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> menor = a.size() <= b.size() ? a : b;
        Set<String> mayor = menor == a ? b : a;
        int comunes = 0;
        for (String x : menor) {
            if (mayor.contains(x)) {
                comunes++;
            }
        }
        return 2.0 * comunes / (a.size() + b.size());
    }

    /**
     * Quita los espacios de los extremos, incluidos los «invisibles» que a veces
     * vienen de Excel o de copiar y pegar (espacio duro U+00A0, U+2007, U+202F y
     * la marca U+FEFF), que {@code String.trim()} no quita.
     */
    public static String limpio(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace('\u00A0', ' ').replace('\u2007', ' ').replace('\u202F', ' ').replace('\uFEFF', ' ').trim();
    }

    public static boolean vacio(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String seguro(String s) {
        return s == null ? "" : s;
    }

    public static String recortar(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Escapa texto para meterlo dentro de una etiqueta HTML de Swing. */
    public static String html(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
}
