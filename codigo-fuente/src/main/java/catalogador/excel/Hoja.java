package catalogador.excel;

import java.util.List;

/** Una hoja leída de un Excel o CSV: lista de filas, cada una lista de celdas como texto. */
public final class Hoja {

    private final String nombre;
    private final List<List<String>> filas;

    public Hoja(String nombre, List<List<String>> filas) {
        this.nombre = nombre;
        this.filas = filas;
    }

    public String getNombre() {
        return nombre;
    }

    public List<List<String>> getFilas() {
        return filas;
    }

    public int numFilas() {
        return filas.size();
    }

    public int numColumnas() {
        int max = 0;
        for (List<String> f : filas) {
            max = Math.max(max, f.size());
        }
        return max;
    }

    public String celda(int fila, int col) {
        if (fila < 0 || fila >= filas.size()) {
            return "";
        }
        List<String> f = filas.get(fila);
        if (col < 0 || col >= f.size() || f.get(col) == null) {
            return "";
        }
        return f.get(col);
    }

    public boolean filaVacia(int fila) {
        if (fila < 0 || fila >= filas.size()) {
            return true;
        }
        for (String c : filas.get(fila)) {
            if (c != null && !c.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int celdasConDatos(int fila) {
        int n = 0;
        if (fila >= 0 && fila < filas.size()) {
            for (String c : filas.get(fila)) {
                if (c != null && !c.trim().isEmpty()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Primera fila que parece una cabecera (al menos 2 celdas con texto). */
    public int filaCabeceraProbable() {
        int columnas = numColumnas();
        int minimo = columnas >= 2 ? 2 : 1;
        for (int i = 0; i < Math.min(filas.size(), 50); i++) {
            if (celdasConDatos(i) >= minimo) {
                return i;
            }
        }
        return 0;
    }

    /** Convierte un índice (0, 1, ... 26) en letra de columna (A, B, ... AA). */
    public static String letra(int col) {
        StringBuilder b = new StringBuilder();
        int n = col + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            b.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return b.toString();
    }
}
