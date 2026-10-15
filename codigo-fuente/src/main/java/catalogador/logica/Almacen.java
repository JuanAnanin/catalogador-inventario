package catalogador.logica;

import catalogador.excel.LectorTablas;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TipoLista;
import catalogador.util.Diario;
import catalogador.util.Texto;
import catalogador.util.Tsv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Dónde y cómo se guarda todo en disco. Por defecto en la carpeta
 * "Catalogador" dentro de la carpeta personal del usuario:
 * <pre>
 *   ~/Catalogador/
 *       catalogos/GMDN.tsv, MANTENIMIENTO.tsv   catálogos importados (y entradas nuevas)
 *       memoria.tsv, memoria.diario.tsv         lo aprendido
 *       proyectos/&lt;nombre&gt;/inventario.tsv      equipos del inventario (solo los datos necesarios)
 *       proyectos/&lt;nombre&gt;/estado.tsv          trabajo de revisión (instantánea)
 *       proyectos/&lt;nombre&gt;/cambios.tsv         cada revisión, anotada al momento
 * </pre>
 * Guardar una revisión solo añade una línea a {@code cambios.tsv}, así que
 * cuesta lo mismo con cien equipos que con seiscientos mil. Cada cierto
 * número de cambios (y al abrir el proyecto) se reescribe {@code estado.tsv}
 * y se vacía el diario.
 */
public final class Almacen {

    public static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    static final String VERSION_PROYECTO = "2";
    static final int COMPACTAR_ESTADO = 2000;

    /** El proyecto se creó con una versión anterior del programa y no se puede abrir. */
    public static final class ProyectoAntiguoException extends IOException {
        private static final long serialVersionUID = 1L;

        ProyectoAntiguoException(Path carpeta) {
            super("El proyecto de " + carpeta.getFileName() + " se creó con una versión anterior del programa "
                    + "(con otro formato de inventario).\nVuelve a importar el inventario para crear un proyecto nuevo.");
        }
    }

    /** Datos básicos para el listado de proyectos. */
    public static final class InfoProyecto {
        private final Path carpeta;
        private final String nombre;
        private final String origen;
        private final String creado;
        private final String modificado;
        private final long equipos;

        InfoProyecto(Path carpeta, String nombre, String origen, String creado, String modificado, long equipos) {
            this.carpeta = carpeta;
            this.nombre = nombre;
            this.origen = origen;
            this.creado = creado;
            this.modificado = modificado;
            this.equipos = equipos;
        }

        public Path getCarpeta() {
            return carpeta;
        }

        public String getNombre() {
            return nombre;
        }

        public String getOrigen() {
            return origen;
        }

        public String getCreado() {
            return creado;
        }

        public String getModificado() {
            return modificado;
        }

        /** Equipos del proyecto (−1 si no se sabe). */
        public long getEquipos() {
            return equipos;
        }
    }

    private final Path base;
    private FileChannel canalBloqueo;
    private FileLock bloqueo;

    public Almacen(Path base) {
        this.base = base;
    }

    public static Almacen predeterminado() {
        return new Almacen(Paths.get(System.getProperty("user.home"), "Catalogador"));
    }

    public Path getBase() {
        return base;
    }

    public Path carpetaProyectos() {
        return base.resolve("proyectos");
    }

    public Path carpetaCatalogos() {
        return base.resolve("catalogos");
    }

    private Path ficheroConfig() {
        return base.resolve("config.properties");
    }

    public void prepararCarpetas() throws IOException {
        Files.createDirectories(carpetaProyectos());
        Files.createDirectories(carpetaCatalogos());
    }

    /** Evita que dos ventanas del programa escriban a la vez en los mismos ficheros. */
    public boolean bloquearInstancia() {
        try {
            canalBloqueo = FileChannel.open(base.resolve(".bloqueo"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            bloqueo = canalBloqueo.tryLock();
            return bloqueo != null;
        } catch (IOException e) {
            return true; // si el sistema no admite bloqueos, seguimos
        }
    }

    // ---------------------------------------------------------------- proyectos

    public Path nuevaCarpetaProyecto(String nombre) throws IOException {
        String limpio = Texto.normalizar(nombre).replace(' ', '-');
        if (limpio.length() > 40) {
            limpio = limpio.substring(0, 40);
        }
        if (limpio.isEmpty()) {
            limpio = "proyecto";
        }
        String sello = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        Path p = carpetaProyectos().resolve(limpio + "_" + sello);
        int n = 2;
        while (Files.exists(p)) {
            p = carpetaProyectos().resolve(limpio + "_" + sello + "_" + n++);
        }
        Files.createDirectories(p);
        return p;
    }

    private static Diario diario(Proyecto p) {
        return new Diario(p.getCarpeta().resolve("cambios.tsv"));
    }

    /** Guarda los equipos del proyecto recién importado (una sola vez). */
    public void guardarInventario(Proyecto p) throws IOException {
        Tsv.escribirAtomico(p.getCarpeta().resolve("inventario.tsv"), w -> {
            w.write("#CATALOGADOR-INVENTARIO\t" + VERSION_PROYECTO + "\n");
            w.write(Tsv.linea("NOMBRE", p.getNombre()) + "\n");
            w.write(Tsv.linea("ORIGEN", p.getFicheroOrigen()) + "\n");
            w.write(Tsv.linea("CREADO", p.getCreado()) + "\n");
            w.write(Tsv.linea("AGRUPAR", p.isAgruparPorMarcaYModelo() ? "1" : "0") + "\n");
            w.write(Tsv.linea("ESTADISTICAS", String.valueOf(p.getFilasLeidas()),
                    String.valueOf(p.getDescartadosBaja()), String.valueOf(p.getDescartadosOtros())) + "\n");
            w.write(Tsv.linea("EQUIPOS", String.valueOf(p.numEquipos())) + "\n");
            for (int i = 0; i < p.numEquipos(); i++) {
                w.write(Tsv.linea("E", p.centro(i), p.id(i), p.nombre(i), p.referencia(i), p.marca(i),
                        p.modelo(i), p.tipo(i)));
                w.write('\n');
            }
        });
    }

    private static String lineaGrupo(Grupo g) {
        return Tsv.linea("GRUPO", g.getClave(), g.getEstado().name(),
                g.getLista() == null ? "" : g.getLista().name(), g.getCodigo(), g.getNombreOficial(),
                g.getMarcaCorregida() == null ? "0" : "1", Texto.seguro(g.getMarcaCorregida()),
                g.getModeloCorregido() == null ? "0" : "1", Texto.seguro(g.getModeloCorregido()),
                g.getFecha(), g.getOrigen());
    }

    private static boolean tocado(Grupo g) {
        return g.getEstado() != EstadoGrupo.PENDIENTE || g.getMarcaCorregida() != null
                || g.getModeloCorregido() != null;
    }

    /**
     * Anota el estado de un grupo recién revisado. Es lo que se hace tras
     * cada Guardar / Posponer / Deshacer: una línea al final de cambios.tsv.
     */
    public void registrarCambio(Proyecto p, Grupo g) throws IOException {
        List<String> l = new ArrayList<>(1);
        l.add(lineaGrupo(g));
        anotar(p, l);
    }

    /** Igual que {@link #registrarCambio} para varios grupos a la vez. */
    public void registrarCambios(Proyecto p, List<Grupo> grupos) throws IOException {
        List<String> l = new ArrayList<>(grupos.size());
        for (Grupo g : grupos) {
            l.add(lineaGrupo(g));
        }
        anotar(p, l);
    }

    private void anotar(Proyecto p, List<String> lineas) throws IOException {
        Diario d = diario(p);
        d.anadir(lineas);
        if (d.tamano() > COMPACTAR_ESTADO) {
            guardarEstado(p);
        }
    }

    /** Escribe la instantánea completa del trabajo (estado.tsv) y vacía el diario de cambios. */
    public void guardarEstado(Proyecto p) throws IOException {
        Tsv.escribirAtomico(p.getCarpeta().resolve("estado.tsv"), w -> {
            w.write("#CATALOGADOR-ESTADO\t" + VERSION_PROYECTO + "\n");
            for (Grupo g : p.getGrupos()) {
                if (tocado(g)) {
                    w.write(lineaGrupo(g));
                    w.write('\n');
                }
            }
        });
        diario(p).vaciar();
    }

    /** true si la carpeta tiene un proyecto de la versión actual. */
    public static boolean esProyectoActual(Path carpeta) {
        String[] c = Tsv.campos(Tsv.primeraLinea(carpeta.resolve("inventario.tsv")));
        return "#CATALOGADOR-INVENTARIO".equals(c[0]) && VERSION_PROYECTO.equals(Tsv.campo(c, 1));
    }

    public Proyecto abrir(Path carpeta) throws IOException {
        return abrir(carpeta, null);
    }

    public Proyecto abrir(Path carpeta, LectorTablas.Progreso progreso) throws IOException {
        Path inv = carpeta.resolve("inventario.tsv");
        if (!Files.exists(inv)) {
            throw new IOException("No se encuentra el proyecto en " + carpeta);
        }
        if (!esProyectoActual(carpeta)) {
            throw new ProyectoAntiguoException(carpeta);
        }
        Proyecto p = new Proyecto();
        p.setCarpeta(carpeta);
        long[] estad = new long[4]; // leídas, baja, otros, equipos
        long[] leidos = new long[1];
        Tsv.recorrer(inv, linea -> {
            if (linea.isEmpty() || linea.startsWith("#")) {
                return;
            }
            String[] c = Tsv.campos(linea);
            switch (c[0]) {
                case "E":
                    p.anadirEquipo(Tsv.campo(c, 1), Tsv.campo(c, 2), Tsv.campo(c, 3), Tsv.campo(c, 4),
                            Tsv.campo(c, 5), Tsv.campo(c, 6), Tsv.campo(c, 7));
                    if (progreso != null && ++leidos[0] % 20000 == 0) {
                        progreso.avance(leidos[0], estad[3]);
                    }
                    break;
                case "NOMBRE": p.setNombre(Tsv.campo(c, 1)); break;
                case "ORIGEN": p.setFicheroOrigen(Tsv.campo(c, 1)); break;
                case "CREADO": p.setCreado(Tsv.campo(c, 1)); break;
                case "AGRUPAR": p.setAgruparPorMarcaYModelo(!"0".equals(Tsv.campo(c, 1))); break;
                case "ESTADISTICAS":
                    estad[0] = numero(Tsv.campo(c, 1));
                    estad[1] = numero(Tsv.campo(c, 2));
                    estad[2] = numero(Tsv.campo(c, 3));
                    break;
                case "EQUIPOS": estad[3] = numero(Tsv.campo(c, 1)); break;
                default: break;
            }
        });
        p.setEstadisticas(estad[0], estad[1], estad[2]);
        p.terminarCarga();
        p.agrupar();

        Path est = carpeta.resolve("estado.tsv");
        if (Files.exists(est)) {
            // Copia de seguridad del estado tal y como estaba al abrir
            Files.copy(est, carpeta.resolve("estado.copia-al-abrir.tsv"), StandardCopyOption.REPLACE_EXISTING);
            Tsv.recorrer(est, linea -> aplicarLineaGrupo(p, linea));
        }
        Diario d = new Diario(carpeta.resolve("cambios.tsv"));
        List<String> cambios = d.leer();
        for (String linea : cambios) {
            aplicarLineaGrupo(p, linea);
        }
        if (!cambios.isEmpty()) {
            guardarEstado(p); // se integra el diario en la instantánea
        }
        return p;
    }

    private static void aplicarLineaGrupo(Proyecto p, String linea) {
        if (linea.isEmpty() || linea.startsWith("#")) {
            return;
        }
        String[] c = Tsv.campos(linea);
        if (!"GRUPO".equals(c[0]) || c.length < 12) {
            return;
        }
        Grupo g = p.getGrupo(c[1]);
        if (g == null) {
            return;
        }
        EstadoGrupo e;
        try {
            e = EstadoGrupo.valueOf(c[2]);
        } catch (IllegalArgumentException ex) {
            e = EstadoGrupo.PENDIENTE;
        }
        g.setEstado(e);
        g.setLista(TipoLista.desdeTexto(c[3]));
        g.setCodigo(c[4]);
        g.setNombreOficial(c[5]);
        g.setMarcaCorregida("1".equals(c[6]) ? c[7] : null);
        g.setModeloCorregido("1".equals(c[8]) ? c[9] : null);
        g.setFecha(c[10]);
        g.setOrigen(c[11]);
    }

    /** Proyectos de la versión actual, del más reciente al más antiguo. */
    public List<InfoProyecto> listarProyectos() {
        List<InfoProyecto> lista = new ArrayList<>();
        if (!Files.isDirectory(carpetaProyectos())) {
            return lista;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(carpetaProyectos())) {
            for (Path d : ds) {
                Path inv = d.resolve("inventario.tsv");
                if (!Files.exists(inv) || !esProyectoActual(d)) {
                    continue;
                }
                String nombre = d.getFileName().toString();
                String origen = "";
                String creado = "";
                long equipos = -1;
                try (BufferedReader r = Files.newBufferedReader(inv, StandardCharsets.UTF_8)) {
                    String linea;
                    int leidas = 0;
                    while ((linea = r.readLine()) != null && leidas++ < 10) {
                        String[] c = Tsv.campos(linea);
                        switch (c[0]) {
                            case "NOMBRE": nombre = Tsv.campo(c, 1); break;
                            case "ORIGEN": origen = Tsv.campo(c, 1); break;
                            case "CREADO": creado = Tsv.campo(c, 1); break;
                            case "EQUIPOS": equipos = numero(Tsv.campo(c, 1)); break;
                            default: break;
                        }
                    }
                }
                Path est = d.resolve("estado.tsv");
                Path cam = d.resolve("cambios.tsv");
                Path ultimo = Files.exists(cam) ? cam : Files.exists(est) ? est : inv;
                Instant mod = Files.getLastModifiedTime(ultimo).toInstant();
                String modificado = LocalDateTime.ofInstant(mod, ZoneId.systemDefault()).format(FECHA);
                lista.add(new InfoProyecto(d, nombre, origen, creado, modificado, equipos));
            }
        } catch (IOException e) {
            // Listado parcial: mejor que nada
        }
        lista.sort((a, b) -> b.getCarpeta().getFileName().toString().compareTo(a.getCarpeta().getFileName().toString()));
        return lista;
    }

    private static long numero(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- configuración

    public Properties leerConfig() {
        Properties p = new Properties();
        Path f = ficheroConfig();
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                // configuración por defecto
            }
        }
        return p;
    }

    public void guardarConfig(Properties p) {
        try {
            Files.createDirectories(base);
            try (OutputStream out = Files.newOutputStream(ficheroConfig())) {
                p.store(out, "Configuración de Catalogador");
            }
        } catch (IOException e) {
            // no es crítico
        }
    }
}
