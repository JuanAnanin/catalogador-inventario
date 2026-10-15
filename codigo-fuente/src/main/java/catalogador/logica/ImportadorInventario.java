package catalogador.logica;

import catalogador.excel.LectorTablas;
import catalogador.modelo.Proyecto;
import catalogador.util.Texto;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Importa el inventario del hospital («Datos inventario que paso al programa»)
 * leyendo el fichero fila a fila, sin cargarlo entero en memoria.
 * <ul>
 *   <li>Las columnas se reconocen por sus cabeceras literales.</li>
 *   <li>Se descartan los equipos con <code>Equipo_debaja</code> = «S»; solo se
 *       procesan los que tienen «N».</li>
 *   <li>Se guardan únicamente los datos necesarios; el resto (por ejemplo
 *       <code>refArea_nombre</code>) se ignora.</li>
 * </ul>
 */
public final class ImportadorInventario {

    public static final int CENTRO = 0;
    public static final int ID = 1;
    public static final int NOMBRE = 2;
    public static final int REFERENCIA = 3;
    public static final int MARCA = 4;
    public static final int MODELO = 5;
    public static final int TIPO = 6;
    public static final int BAJA = 7;

    /** Cabeceras literales del fichero de inventario. */
    public static final String[] CABECERAS = {"Equipo_codiCentro", "Equipo_idEquipo", "Equipo_nombre",
            "Equipo_referencia", "refMarca_nombre", "Equipo_modelo", "Equipo_codiTipoActivo", "Equipo_debaja"};

    public static final String[] ETIQUETAS = {"Centro", "Identificador del equipo", "Nombre del equipo",
            "Referencia", "Marca", "Modelo", "Tipo de activo", "De baja (S/N)"};

    public static final boolean[] OBLIGATORIAS = {true, true, true, false, false, false, false, false};

    /** Dónde está cada dato dentro del fichero. */
    public static final class Mapeo {
        public int hoja;
        public int filaCabecera = -1;
        public final int[] col = new int[CABECERAS.length];

        public Mapeo() {
            Arrays.fill(col, -1);
        }

        public boolean completo() {
            for (int i = 0; i < col.length; i++) {
                if (col[i] < 0) {
                    return false;
                }
            }
            return true;
        }

        public boolean valido() {
            for (int i = 0; i < col.length; i++) {
                if (OBLIGATORIAS[i] && col[i] < 0) {
                    return false;
                }
            }
            return filaCabecera >= 0;
        }

        public List<String> faltan() {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < col.length; i++) {
                if (col[i] < 0) {
                    l.add(CABECERAS[i]);
                }
            }
            return l;
        }
    }

    private ImportadorInventario() {
    }

    /** Cabecera comparable: sin mayúsculas, tildes, espacios ni signos («CÓDIGO GMDN» → «codigogmdn»). */
    static String clave(String cabecera) {
        return cabecera == null ? "" : Texto.normalizar(cabecera).replace(" ", "");
    }

    /**
     * Busca, en las primeras 30 filas de cada hoja, la fila de cabeceras
     * (la que contiene «Equipo_nombre») y la posición de cada columna.
     * @return el mapeo encontrado, o null si ninguna hoja tiene las cabeceras
     */
    public static Mapeo detectar(File f) throws IOException {
        int numHojas = LectorTablas.hojas(f).size();
        for (int h = 0; h < numHojas; h++) {
            Mapeo m = new Mapeo();
            m.hoja = h;
            LectorTablas.recorrer(f, h, (num, celdas) -> {
                int[] col = new int[CABECERAS.length];
                Arrays.fill(col, -1);
                for (int c = 0; c < celdas.length; c++) {
                    String k = clave(celdas[c]);
                    for (int i = 0; i < CABECERAS.length; i++) {
                        if (col[i] < 0 && k.equals(clave(CABECERAS[i]))) {
                            col[i] = c;
                        }
                    }
                }
                if (col[NOMBRE] >= 0) {
                    m.filaCabecera = num;
                    System.arraycopy(col, 0, m.col, 0, col.length);
                    return false;
                }
                return num < 30;
            }, null);
            if (m.filaCabecera >= 0) {
                return m;
            }
        }
        return null;
    }

    /** Lee el inventario completo y crea el proyecto con sus grupos. */
    public static Proyecto importar(File f, Mapeo m, boolean agruparPorMarcaYModelo, LectorTablas.Progreso progreso)
            throws IOException {
        Proyecto p = new Proyecto();
        p.setAgruparPorMarcaYModelo(agruparPorMarcaYModelo);
        long[] cuenta = new long[3]; // leídas, de baja, otros valores
        LectorTablas.recorrer(f, m.hoja, (num, celdas) -> {
            if (num <= m.filaCabecera) {
                return true;
            }
            String nombre = celda(celdas, m.col[NOMBRE]);
            String centro = celda(celdas, m.col[CENTRO]);
            String id = celda(celdas, m.col[ID]);
            if (nombre.isEmpty() && centro.isEmpty() && id.isEmpty()) {
                return true;
            }
            cuenta[0]++;
            if (m.col[BAJA] >= 0) {
                String baja = celda(celdas, m.col[BAJA]).toUpperCase(Locale.ROOT);
                if (baja.equals("S")) {
                    cuenta[1]++;
                    return true;
                }
                if (!baja.equals("N")) {
                    cuenta[2]++;
                    return true;
                }
            }
            p.anadirEquipo(centro, id, nombre, celda(celdas, m.col[REFERENCIA]), celda(celdas, m.col[MARCA]),
                    celda(celdas, m.col[MODELO]), celda(celdas, m.col[TIPO]));
            return true;
        }, progreso);
        p.setEstadisticas(cuenta[0], cuenta[1], cuenta[2]);
        p.terminarCarga();
        p.agrupar();
        return p;
    }

    private static String celda(String[] celdas, int col) {
        return col >= 0 && col < celdas.length && celdas[col] != null ? Texto.limpio(celdas[col]) : "";
    }
}
