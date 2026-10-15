package catalogador.logica;

import catalogador.util.Texto;

import java.util.Arrays;

/**
 * "Huella" de un nombre para compararlo rápido con otros. La similitud
 * combina dos medidas:
 * <ul>
 *   <li>palabras en común, tolerando faltas de ortografía ("bonba" ≈ "bomba")
 *       y abreviaturas ("volum" ≈ "volumetrica"), sin importar el orden;</li>
 *   <li>trigramas de letras en común (parecido "visual" del texto).</li>
 * </ul>
 * El resultado va de 0 (nada que ver) a 1 (idénticos).
 * <p>
 * Los trigramas se guardan como números ordenados (no como textos) para que
 * la memoria pueda tener decenas de miles de aparatos sin ocupar demasiado.
 */
public final class Huella {

    private final String normalizado;
    private final String[] palabras;
    private final int[] trigramas;

    public Huella(String texto) {
        normalizado = Texto.normalizar(texto);
        palabras = Texto.palabrasSignificativas(normalizado);
        String[] ordenadas = palabras.clone();
        Arrays.sort(ordenadas);
        trigramas = codificar(String.join(" ", ordenadas));
    }

    /** Trigramas de caracteres ("  b", " bo", "bom"...) como enteros ordenados y sin repetir. */
    static int[] codificar(String s) {
        if (s == null || s.isEmpty()) {
            return new int[0];
        }
        String t = "  " + s + " ";
        int[] r = new int[t.length() - 2];
        for (int i = 0; i + 3 <= t.length(); i++) {
            r[i] = codigo(t.charAt(i), t.charAt(i + 1), t.charAt(i + 2));
        }
        Arrays.sort(r);
        int n = 0;
        for (int i = 0; i < r.length; i++) {
            if (i == 0 || r[i] != r[i - 1]) {
                r[n++] = r[i];
            }
        }
        return Arrays.copyOf(r, n);
    }

    private static int codigo(char a, char b, char c) {
        if (a < 128 && b < 128 && c < 128) {
            return (a << 14) | (b << 7) | c; // exacto para ASCII (lo normal tras normalizar)
        }
        return 0x200000 | ((a * 961 + b * 31 + c) & 0x1FFFFF);
    }

    public String getNormalizado() {
        return normalizado;
    }

    /** Trigramas codificados (ordenados); se usan para el índice de la memoria. */
    public int[] getTrigramas() {
        return trigramas;
    }

    public double similitud(Huella otra) {
        if (normalizado.isEmpty() || otra.normalizado.isEmpty()) {
            return 0;
        }
        if (normalizado.equals(otra.normalizado)) {
            return 1;
        }
        double porPalabras = similitudPalabras(palabras, otra.palabras);
        double porLetras = dice(trigramas, otra.trigramas);
        return Math.min(1.0, 0.55 * porPalabras + 0.45 * porLetras);
    }

    /** Coeficiente de Dice entre dos conjuntos ordenados: 2·|A∩B| / (|A|+|B|). */
    static double dice(int[] a, int[] b) {
        if (a.length == 0 || b.length == 0) {
            return 0;
        }
        int i = 0;
        int j = 0;
        int comunes = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                comunes++;
                i++;
                j++;
            } else if (a[i] < b[j]) {
                i++;
            } else {
                j++;
            }
        }
        return 2.0 * comunes / (a.length + b.length);
    }

    private static double similitudPalabras(String[] a, String[] b) {
        if (a.length == 0 || b.length == 0) {
            return 0;
        }
        boolean[] usada = new boolean[b.length];
        double suma = 0;
        for (String x : a) {
            double mejor = 0;
            int indice = -1;
            for (int j = 0; j < b.length; j++) {
                if (!usada[j]) {
                    double p = parecido(x, b[j]);
                    if (p > mejor) {
                        mejor = p;
                        indice = j;
                        if (p == 1) {
                            break;
                        }
                    }
                }
            }
            if (indice >= 0) {
                usada[indice] = true;
                suma += mejor;
            }
        }
        return 2 * suma / (a.length + b.length);
    }

    /** Parecido entre dos palabras: 1 iguales, 0.85 errata, 0.8 abreviatura, 0 distintas. */
    static double parecido(String x, String y) {
        if (x.equals(y)) {
            return 1;
        }
        int lx = x.length();
        int ly = y.length();
        if (Math.min(lx, ly) >= 3 && (x.startsWith(y) || y.startsWith(x))
                && !Character.isDigit(x.charAt(0))) {
            return 0.8;
        }
        if (Math.min(lx, ly) >= 4) {
            int max = Math.max(lx, ly) > 7 ? 2 : 1;
            if (Texto.distancia(x, y, max) <= max) {
                return 0.85;
            }
        }
        return 0;
    }
}
