package catalogador.logica;

import catalogador.excel.EscritorCsv;
import catalogador.excel.EscritorXlsx;
import catalogador.excel.EscritorXlsx.HojaSalida;
import catalogador.excel.LectorTablas;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TipoLista;
import catalogador.util.Texto;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Genera el resultado de la catalogación con el formato pedido
 * («Datos que el programa debe devolver»): una fila por equipo con
 * <pre>
 *   Equipo_codiCentro | Equipo_idEquipo | CÓDIGO GMDN | NOMBRE GMDN y NOMBRE MANTENIMIENTO
 * </pre>
 * Los equipos catalogados con el catálogo de Mantenimiento no tienen código
 * GMDN (la columna queda vacía) y se identifican por su nombre, que es único.
 * Se crea un fichero (Excel o CSV) por cada centro.
 */
public final class Exportador {

    public static final List<String> CABECERA = Arrays.asList(
            "Equipo_codiCentro", "Equipo_idEquipo", "CÓDIGO GMDN", "NOMBRE GMDN y NOMBRE MANTENIMIENTO");
    public static final List<String> COLUMNAS_MARCA_MODELO = Arrays.asList("Marca", "Modelo");

    private static final double[] ANCHOS = {17.2, 15.4, 14, 42.8, 22, 26};
    private static final Pattern NUMERO = Pattern.compile("-?(0|[1-9]\\d{0,14})");

    /** Qué y cómo exportar. */
    public static final class Opciones {
        /** true = CSV (separado por «;»), false = Excel (.xlsx). */
        public boolean csv;
        /** Incluir también los equipos pendientes o pospuestos (con el nombre en blanco). */
        public boolean incluirSinCatalogar;
        /** Añadir dos columnas con la marca y el modelo (ya corregidos). */
        public boolean incluirMarcaModelo;
        public File carpeta;
        public String prefijo = "Catalogacion";

        public String extension() {
            return csv ? ".csv" : ".xlsx";
        }
    }

    /** Un fichero de salida (un centro). */
    public static final class Fichero {
        private final String centro;
        private final File fichero;
        private final int filas;

        Fichero(String centro, File fichero, int filas) {
            this.centro = centro;
            this.fichero = fichero;
            this.filas = filas;
        }

        public String getCentro() {
            return centro;
        }

        public File getFichero() {
            return fichero;
        }

        public int getFilas() {
            return filas;
        }
    }

    private Exportador() {
    }

    /** Nombre del fichero de un centro: «Catalogacion_GAIVI.xlsx». */
    public static String nombreFichero(String prefijo, String centro, String extension) {
        String p = limpiar(Texto.vacio(prefijo) ? "Catalogacion" : prefijo.trim(), 120);
        String c = limpiar(Texto.vacio(centro) ? "SIN_CENTRO" : centro.trim(), 60);
        return p + "_" + (c.isEmpty() ? "SIN_CENTRO" : c) + extension;
    }

    /**
     * Nombres de fichero de todos los centros, sin repetir: dos centros que solo
     * se distinguen por mayúsculas o por caracteres que no valen en un nombre de
     * fichero («C/S NORTE» y «C:S NORTE») no se pisan (Windows y macOS no
     * distinguen mayúsculas en los nombres de fichero).
     */
    static Map<String, String> nombresFicheros(Iterable<String> centros, Opciones o) {
        Map<String, String> res = new java.util.LinkedHashMap<>();
        java.util.Set<String> usados = new java.util.HashSet<>();
        for (String centro : centros) {
            String n = nombreFichero(o.prefijo, centro, o.extension());
            String base = n.substring(0, n.length() - o.extension().length());
            int k = 2;
            while (!usados.add(n.toLowerCase(java.util.Locale.ROOT))) {
                n = base + "_" + k++ + o.extension();
            }
            res.put(centro, n);
        }
        return res;
    }

    /** Quita lo que no vale en un nombre de fichero de Windows, macOS o Linux. */
    static String limpiar(String s, int largo) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\s\\p{Cntrl}]+", "_").replaceAll("^[._]+|[._]+$", "");
        return r.length() > largo ? r.substring(0, largo) : r;
    }

    private static boolean seExporta(Grupo g, Opciones o) {
        return o.incluirSinCatalogar || g.getEstado() == EstadoGrupo.REVISADO;
    }

    /** Filas (posiciones de equipos) que van a cada centro, en el orden del inventario. */
    private static Map<String, int[]> repartir(Proyecto p, Opciones o) {
        Map<String, int[]> tam = new TreeMap<>();
        Map<String, Integer> cuenta = new TreeMap<>();
        for (int i = 0; i < p.numEquipos(); i++) {
            if (seExporta(p.grupoDeFila(i), o)) {
                cuenta.merge(p.centro(i), 1, Integer::sum);
            }
        }
        Map<String, Integer> pos = new TreeMap<>();
        for (Map.Entry<String, Integer> e : cuenta.entrySet()) {
            tam.put(e.getKey(), new int[e.getValue()]);
            pos.put(e.getKey(), 0);
        }
        for (int i = 0; i < p.numEquipos(); i++) {
            if (seExporta(p.grupoDeFila(i), o)) {
                String c = p.centro(i);
                int k = pos.get(c);
                tam.get(c)[k] = i;
                pos.put(c, k + 1);
            }
        }
        return tam;
    }

    /** Lo que se va a crear, sin escribir nada (para enseñarlo antes de exportar). */
    public static List<Fichero> previsualizar(Proyecto p, Opciones o) {
        Map<String, Integer> cuenta = new TreeMap<>();
        for (int i = 0; i < p.numEquipos(); i++) {
            if (seExporta(p.grupoDeFila(i), o)) {
                cuenta.merge(p.centro(i), 1, Integer::sum);
            }
        }
        List<Fichero> l = new ArrayList<>();
        Map<String, String> nombres = nombresFicheros(cuenta.keySet(), o);
        for (Map.Entry<String, Integer> e : cuenta.entrySet()) {
            l.add(new Fichero(e.getKey(), new File(o.carpeta, nombres.get(e.getKey())), e.getValue()));
        }
        return l;
    }

    public static List<Fichero> exportar(Proyecto p, Opciones o, LectorTablas.Progreso progreso) throws IOException {
        Map<String, int[]> reparto = repartir(p, o);
        long total = 0;
        for (int[] f : reparto.values()) {
            total += f.length;
        }
        long[] hechas = new long[1];
        long totalFinal = total;
        List<String> cabecera = new ArrayList<>(CABECERA);
        if (o.incluirMarcaModelo) {
            cabecera.addAll(COLUMNAS_MARCA_MODELO);
        }
        List<Fichero> creados = new ArrayList<>();
        Map<String, String> nombres = nombresFicheros(reparto.keySet(), o);
        for (Map.Entry<String, int[]> e : reparto.entrySet()) {
            String centro = e.getKey();
            int[] filas = e.getValue();
            File destino = new File(o.carpeta, nombres.get(centro));
            if (o.csv) {
                EscritorCsv.escribir(destino, cabecera, filas(p, filas, 0, filas.length, o, progreso, hechas, totalFinal));
            } else {
                List<HojaSalida> hojas = new ArrayList<>();
                int porHoja = EscritorXlsx.MAX_FILAS_HOJA - 1;
                int partes = Math.max(1, (filas.length + porHoja - 1) / porHoja);
                for (int k = 0; k < partes; k++) {
                    int desde = k * porHoja;
                    int hasta = Math.min(filas.length, desde + porHoja);
                    String nombre = Texto.vacio(centro) ? "Equipos" : centro;
                    if (partes > 1) {
                        nombre = Texto.recortar(nombre, 24) + " (" + (k + 1) + ")";
                    }
                    hojas.add(new HojaSalida(nombre, cabecera, filas(p, filas, desde, hasta, o, progreso, hechas, totalFinal))
                            .anchos(ANCHOS));
                }
                EscritorXlsx.escribir(destino, hojas);
            }
            creados.add(new Fichero(centro, destino, filas.length));
        }
        return creados;
    }

    private static EscritorXlsx.Filas filas(Proyecto p, int[] filas, int desde, int hasta, Opciones o,
                                            LectorTablas.Progreso progreso, long[] hechas, long total) {
        return new EscritorXlsx.Filas() {
            @Override
            public int numero() {
                return hasta - desde;
            }

            @Override
            public Object[] fila(int k) {
                int i = filas[desde + k];
                Grupo g = p.grupoDeFila(i);
                boolean catalogado = g.getEstado() == EstadoGrupo.REVISADO && g.getLista() != null;
                Object codigo = catalogado && g.getLista() == TipoLista.GMDN ? numeroSiLoEs(g.getCodigo()) : null;
                Object nombre = catalogado ? g.getNombreOficial() : null;
                Object[] f = o.incluirMarcaModelo
                        ? new Object[]{p.centro(i), numeroSiLoEs(p.id(i)), codigo, nombre,
                        marcaFinal(g, p.marca(i)), modeloFinal(g, p.modelo(i))}
                        : new Object[]{p.centro(i), numeroSiLoEs(p.id(i)), codigo, nombre};
                if (progreso != null && ++hechas[0] % 10000 == 0) {
                    progreso.avance(hechas[0], total);
                }
                return f;
            }
        };
    }

    /** "138018" se escribe como número (como en el original); "00123" o "A-12" se quedan como texto. */
    static Object numeroSiLoEs(String s) {
        if (s != null && NUMERO.matcher(s).matches()) {
            return Long.valueOf(s);
        }
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * La marca corregida del grupo; si no se tocó (o el grupo mezcla varias
     * marcas y se dejó en blanco), la original de ese equipo.
     */
    public static String marcaFinal(Grupo g, String original) {
        String c = g.getMarcaCorregida();
        if (c == null || (c.isEmpty() && g.variasMarcas())) {
            return Texto.seguro(original).trim();
        }
        return c;
    }

    public static String modeloFinal(Grupo g, String original) {
        String c = g.getModeloCorregido();
        if (c == null || (c.isEmpty() && g.variosModelos())) {
            return Texto.seguro(original).trim();
        }
        return c;
    }
}
