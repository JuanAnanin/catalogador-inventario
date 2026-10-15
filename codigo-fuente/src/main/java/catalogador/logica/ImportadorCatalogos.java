package catalogador.logica;

import catalogador.excel.Hoja;
import catalogador.excel.LectorTablas;
import catalogador.modelo.TerminoOficial;
import catalogador.util.Texto;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Importa el Excel de catálogos («Datos catalogo que paso al programa»), que
 * tiene dos hojas: «CATÁLOGO GMDN» y «CATÁLOGO MANTENIMIENTO». Las hojas se
 * reconocen por sus cabeceras literales, así que su orden y su nombre dan igual.
 */
public final class ImportadorCatalogos {

    public static final class Resultado {
        public List<TerminoOficial> gmdn;           // null si el fichero no tiene esa hoja
        public List<TerminoOficial> mantenimiento;  // null si el fichero no tiene esa hoja
        public String hojaGmdn = "";
        public String hojaMantenimiento = "";
        public int gmdnRepetidos;
        public int gmdnIncompletos;
        public int mantenimientoSinNombre;
        public final Set<String> nombresRepetidos = new LinkedHashSet<>();
    }

    private ImportadorCatalogos() {
    }

    public static Resultado leer(File f) throws IOException {
        Resultado r = new Resultado();
        int numHojas = LectorTablas.hojas(f).size();
        for (int i = 0; i < numHojas; i++) {
            Hoja h = LectorTablas.leerHoja(f, i, 0);
            int[] cab = buscarCabecera(h, TerminoOficial.CABECERA_GMDN, 2);
            if (cab != null && r.gmdn == null) {
                r.gmdn = leerGmdn(h, cab, r);
                r.hojaGmdn = h.getNombre();
                continue;
            }
            cab = buscarCabecera(h, TerminoOficial.CABECERA_MANTENIMIENTO, 1);
            if (cab != null && r.mantenimiento == null) {
                r.mantenimiento = leerMantenimiento(h, cab, r);
                r.hojaMantenimiento = h.getNombre();
            }
        }
        if (r.gmdn == null && r.mantenimiento == null) {
            throw new IOException("No se ha encontrado ninguna hoja de catálogo.\n\n"
                    + "Se esperan hojas con las cabeceras «" + String.join(", ", TerminoOficial.CABECERA_GMDN)
                    + "» (GMDN)\no que empiecen por «NOMBRE MANTENIMIENTO» (Mantenimiento).");
        }
        return r;
    }

    /**
     * Busca en las primeras 20 filas una con las cabeceras dadas. Devuelve
     * [fila de cabecera, columna de cada cabecera (o −1)], o null si las
     * {@code minimas} primeras cabeceras no están.
     */
    static int[] buscarCabecera(Hoja h, List<String> cabeceras, int minimas) {
        for (int f = 0; f < Math.min(h.numFilas(), 20); f++) {
            Map<String, Integer> pos = new HashMap<>();
            for (int c = 0; c < h.getFilas().get(f).size(); c++) {
                pos.putIfAbsent(Texto.normalizar(h.celda(f, c)), c);
            }
            int[] res = new int[cabeceras.size() + 1];
            res[0] = f;
            boolean ok = true;
            for (int k = 0; k < cabeceras.size(); k++) {
                Integer c = pos.get(Texto.normalizar(cabeceras.get(k)));
                res[k + 1] = c == null ? -1 : c;
                if (k < minimas && c == null) {
                    ok = false;
                }
            }
            if (ok) {
                return res;
            }
        }
        return null;
    }

    private static List<TerminoOficial> leerGmdn(Hoja h, int[] cab, Resultado r) {
        List<TerminoOficial> lista = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (int f = cab[0] + 1; f < h.numFilas(); f++) {
            if (h.filaVacia(f)) {
                continue;
            }
            String codigo = Texto.limpio(valor(h, f, cab[1]));
            String nombre = Texto.limpio(valor(h, f, cab[2]));
            if (codigo.isEmpty() || nombre.isEmpty()) {
                r.gmdnIncompletos++;
                continue;
            }
            if (!vistos.add(Texto.normalizar(codigo))) {
                r.gmdnRepetidos++;
                continue;
            }
            lista.add(TerminoOficial.gmdn(codigo, nombre, valor(h, f, cab[3])));
        }
        return lista;
    }

    private static List<TerminoOficial> leerMantenimiento(Hoja h, int[] cab, Resultado r) {
        List<TerminoOficial> lista = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        int n = TerminoOficial.CABECERA_MANTENIMIENTO.size();
        for (int f = cab[0] + 1; f < h.numFilas(); f++) {
            if (h.filaVacia(f)) {
                continue;
            }
            String[] once = new String[n];
            for (int k = 0; k < n; k++) {
                once[k] = valor(h, f, cab[k + 1]); // tal cual, para exportar igual que el original
            }
            if (Texto.limpio(once[0]).isEmpty()) {
                r.mantenimientoSinNombre++;
                continue;
            }
            if (!vistos.add(Texto.normalizar(once[0]))) {
                r.nombresRepetidos.add(Texto.limpio(once[0]));
            }
            lista.add(TerminoOficial.mantenimiento(once, false, ""));
        }
        return lista;
    }

    private static String valor(Hoja h, int fila, int col) {
        return col < 0 ? "" : h.celda(fila, col);
    }
}
