package catalogador.logica;

import catalogador.modelo.TerminoOficial;
import catalogador.modelo.TerminoOficial.Nivel;
import catalogador.modelo.TipoLista;
import catalogador.util.Texto;
import catalogador.util.Tsv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Los dos catálogos oficiales (GMDN y Mantenimiento) y el buscador
 * instantáneo sobre ellos. Se guardan en disco al importarlos, así que solo
 * hay que importarlos una vez.
 * <p>
 * El catálogo de Mantenimiento es propio del hospital: se pueden crear
 * entradas nuevas (con sus códigos de clasificación) y exportarlo de nuevo
 * con el mismo formato que el Excel original.
 * <p>
 * Formato en disco (versión 2):
 * <pre>
 *   #CATALOGO  2  GMDN|MANTENIMIENTO  fichero de origen  fecha  nombre de la hoja
 *   GMDN:          código  nombre  descripción
 *   Mantenimiento: 11 columnas del Excel  creada-en-el-programa (1/0)  fecha de creación
 * </pre>
 */
public final class Catalogos {

    public static final String VERSION = "2";
    public static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Nombres de hoja del Excel original, que se usan al exportar. */
    public static final String HOJA_GMDN = "CATÁLOGO GMDN";
    public static final String HOJA_MANTENIMIENTO = "CATÁLOGO MANTENIMIENTO";

    public static final class Catalogo {
        private final TipoLista tipo;
        private final List<TerminoOficial> terminos;
        private final Map<String, TerminoOficial> porCodigo = new HashMap<>();
        private final String origen;
        private final String fecha;
        private final String hoja;

        Catalogo(TipoLista tipo, List<TerminoOficial> terminos, String origen, String fecha, String hoja) {
            this.tipo = tipo;
            this.terminos = Collections.unmodifiableList(new ArrayList<>(terminos));
            this.origen = Texto.seguro(origen);
            this.fecha = Texto.seguro(fecha);
            this.hoja = Texto.vacio(hoja) ? (tipo == TipoLista.GMDN ? HOJA_GMDN : HOJA_MANTENIMIENTO) : hoja;
            for (TerminoOficial t : terminos) {
                porCodigo.putIfAbsent(t.getCodigoNorm(), t);
            }
        }

        public TipoLista getTipo() {
            return tipo;
        }

        public List<TerminoOficial> getTerminos() {
            return terminos;
        }

        public String getOrigen() {
            return origen;
        }

        public String getFecha() {
            return fecha;
        }

        public String getHoja() {
            return hoja;
        }

        /** GMDN: busca por código. Mantenimiento: busca por nombre. */
        public TerminoOficial porCodigo(String codigo) {
            return porCodigo.get(Texto.normalizar(codigo));
        }

        public int creadasEnPrograma() {
            int n = 0;
            for (TerminoOficial t : terminos) {
                if (t.isCreadoEnPrograma()) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Un término encontrado y su puntuación. */
    public static final class Resultado {
        private final TerminoOficial termino;
        private final double puntos;

        Resultado(TerminoOficial termino, double puntos) {
            this.termino = termino;
            this.puntos = puntos;
        }

        public TerminoOficial getTermino() {
            return termino;
        }

        public double getPuntos() {
            return puntos;
        }
    }

    public static final class Busqueda {
        private final List<Resultado> resultados;
        private final int total;

        Busqueda(List<Resultado> resultados, int total) {
            this.resultados = resultados;
            this.total = total;
        }

        public List<Resultado> getResultados() {
            return resultados;
        }

        public int getTotal() {
            return total;
        }
    }

    private final Path carpeta;
    /** Se sustituye entero al cambiar, para que el buscador (en otro hilo) nunca vea un estado a medias. */
    private volatile Map<TipoLista, Catalogo> catalogos = new EnumMap<>(TipoLista.class);
    /** Ficheros de una versión anterior del programa que se han apartado al cargar. */
    private final List<String> apartados = new ArrayList<>();

    public Catalogos(Path carpeta) {
        this.carpeta = carpeta;
    }

    private Path fichero(TipoLista tipo) {
        return carpeta.resolve(tipo.name() + ".tsv");
    }

    // ------------------------------------------------------------------ carga y guardado

    public void cargar() throws IOException {
        Map<TipoLista, Catalogo> nuevo = new EnumMap<>(TipoLista.class);
        apartados.clear();
        for (TipoLista tipo : TipoLista.values()) {
            Path p = fichero(tipo);
            if (!Files.exists(p)) {
                continue;
            }
            String[] cab = Tsv.campos(Tsv.primeraLinea(p));
            if (!"#CATALOGO".equals(cab[0]) || !VERSION.equals(Tsv.campo(cab, 1))) {
                // Lista de la versión 1 (formato distinto): se aparta sin borrarla.
                Path viejo = carpeta.resolve(tipo.name() + ".v1.tsv");
                Files.move(p, viejo, StandardCopyOption.REPLACE_EXISTING);
                apartados.add(viejo.getFileName().toString());
                continue;
            }
            List<TerminoOficial> terminos = new ArrayList<>();
            Tsv.recorrer(p, l -> {
                if (l.isEmpty() || l.startsWith("#")) {
                    return;
                }
                String[] c = Tsv.campos(l);
                if (tipo == TipoLista.GMDN) {
                    terminos.add(TerminoOficial.gmdn(Tsv.campo(c, 0), Tsv.campo(c, 1), Tsv.campo(c, 2)));
                } else {
                    String[] once = new String[TerminoOficial.CABECERA_MANTENIMIENTO.size()];
                    for (int i = 0; i < once.length; i++) {
                        once[i] = Tsv.campo(c, i);
                    }
                    terminos.add(TerminoOficial.mantenimiento(once, "1".equals(Tsv.campo(c, once.length)),
                            Tsv.campo(c, once.length + 1)));
                }
            });
            nuevo.put(tipo, new Catalogo(tipo, terminos, Tsv.campo(cab, 3), Tsv.campo(cab, 4), Tsv.campo(cab, 5)));
        }
        catalogos = nuevo;
    }

    /** Nombres de los ficheros antiguos apartados en la última carga (normalmente ninguno). */
    public List<String> getApartados() {
        return Collections.unmodifiableList(apartados);
    }

    private void guardar(Catalogo c) throws IOException {
        Tsv.escribirAtomico(fichero(c.tipo), w -> {
            w.write(Tsv.linea("#CATALOGO", VERSION, c.tipo.name(), c.origen, c.fecha, c.hoja));
            w.write('\n');
            for (TerminoOficial t : c.terminos) {
                String[] d = t.getDatos();
                if (c.tipo == TipoLista.MANTENIMIENTO) {
                    String[] ext = java.util.Arrays.copyOf(d, d.length + 2);
                    ext[d.length] = t.isCreadoEnPrograma() ? "1" : "0";
                    ext[d.length + 1] = t.getFechaCreacion();
                    d = ext;
                }
                w.write(Tsv.linea(d));
                w.write('\n');
            }
        });
    }

    private void publicar(Catalogo c) throws IOException {
        guardar(c);
        Map<TipoLista, Catalogo> nuevo = new EnumMap<>(TipoLista.class);
        nuevo.putAll(catalogos);
        nuevo.put(c.tipo, c);
        catalogos = nuevo;
    }

    /**
     * Sustituye un catálogo por otro recién importado y lo guarda en disco.
     * En Mantenimiento se conservan las entradas creadas en el programa que no
     * vengan en el fichero nuevo (para no perder trabajo).
     * @return número de entradas creadas en el programa que se han conservado
     */
    public int reemplazar(TipoLista tipo, List<TerminoOficial> terminos, String origen, String hoja)
            throws IOException {
        String fecha = LocalDateTime.now().format(FECHA);
        List<TerminoOficial> lista = new ArrayList<>(terminos);
        int conservadas = 0;
        Catalogo anterior = catalogos.get(tipo);
        if (tipo == TipoLista.MANTENIMIENTO && anterior != null) {
            Set<String> nombres = new HashSet<>();
            for (TerminoOficial t : terminos) {
                nombres.add(t.getNombreNorm());
            }
            for (TerminoOficial t : anterior.terminos) {
                if (t.isCreadoEnPrograma() && !nombres.contains(t.getNombreNorm())) {
                    insertarOrdenado(lista, t);
                    conservadas++;
                }
            }
        }
        publicar(new Catalogo(tipo, lista, origen, fecha, hoja));
        return conservadas;
    }

    // ------------------------------------------------------------------ consultas

    public Catalogo get(TipoLista tipo) {
        return catalogos.get(tipo);
    }

    public int total() {
        int n = 0;
        for (Catalogo c : catalogos.values()) {
            n += c.terminos.size();
        }
        return n;
    }

    public int total(TipoLista tipo) {
        Catalogo c = catalogos.get(tipo);
        return c == null ? 0 : c.terminos.size();
    }

    /** Busca un término: en GMDN por código, en Mantenimiento por nombre. */
    public TerminoOficial porCodigo(TipoLista lista, String codigo) {
        if (lista == null || Texto.vacio(codigo)) {
            return null;
        }
        Catalogo c = catalogos.get(lista);
        return c == null ? null : c.porCodigo(codigo);
    }

    /**
     * Búsqueda "según se escribe" dentro de un catálogo. Cada palabra escrita
     * suma puntos si aparece en el nombre (entera, como principio de palabra,
     * dentro, o con una errata), en el código GMDN, o —si se pide— en la
     * descripción (GMDN) o la clasificación (Mantenimiento). Los términos que
     * contienen todas las palabras salen primero.
     */
    public Busqueda buscar(String consulta, TipoLista lista, boolean enDescripcion, int max) {
        String q = Texto.normalizar(consulta);
        String[] palabras = Texto.palabrasSignificativas(q);
        Catalogo c = lista == null ? null : catalogos.get(lista);
        if (palabras.length == 0 || c == null) {
            return new Busqueda(Collections.<Resultado>emptyList(), 0);
        }
        boolean conCodigo = lista == TipoLista.GMDN;
        String qCompacta = q.replace(" ", "");
        List<Resultado> encontrados = new ArrayList<>();
        for (TerminoOficial t : c.terminos) {
            boolean codigoExacto = conCodigo && t.getCodigoCompacto().equals(qCompacta);
            boolean codigoEmpieza = conCodigo && !codigoExacto && qCompacta.length() >= 2 && tieneDigito(qCompacta)
                    && t.getCodigoCompacto().startsWith(qCompacta);
            double puntos = 0;
            int enNombre = 0;
            int enDescripcionN = 0;
            for (String p : palabras) {
                double pp = puntuar(p, t, conCodigo);
                if (pp > 0) {
                    puntos += pp;
                    enNombre++;
                } else if (enDescripcion && p.length() >= 3 && t.getDescripcionNorm().contains(p)) {
                    enDescripcionN++;
                }
            }
            if (enNombre == 0 && enDescripcionN == 0 && !codigoExacto && !codigoEmpieza) {
                continue;
            }
            double total = enNombre * 10 + puntos + enDescripcionN * 3 - t.getNombre().length() * 0.01;
            if (q.length() >= 3 && t.getNombreNorm().contains(q)) {
                total += 6;
            }
            if (t.getNombreNorm().equals(q)) {
                total += 20;
            }
            if (codigoExacto) {
                total += 100;
            } else if (codigoEmpieza) {
                total += 40;
            }
            encontrados.add(new Resultado(t, total));
        }
        encontrados.sort((a, b) -> Double.compare(b.puntos, a.puntos));
        int total = encontrados.size();
        List<Resultado> primeros = total > max ? new ArrayList<>(encontrados.subList(0, max)) : encontrados;
        return new Busqueda(primeros, total);
    }

    private static boolean tieneDigito(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static double puntuar(String palabra, TerminoOficial t, boolean conCodigo) {
        if (conCodigo) {
            if (t.getCodigoNorm().equals(palabra) || t.getCodigoCompacto().equals(palabra)) {
                return 6;
            }
            if (tieneDigito(palabra) && t.getCodigoCompacto().startsWith(palabra)) {
                return 4;
            }
        }
        double mejor = 0;
        for (String w : t.getPalabrasNombre()) {
            if (w.equals(palabra)) {
                return 3;
            }
            if (w.startsWith(palabra)) {
                mejor = Math.max(mejor, palabra.length() >= 2 ? 2.5 : 1);
            } else if (palabra.length() >= 3 && w.contains(palabra)) {
                mejor = Math.max(mejor, 1.5);
            } else if (mejor < 1.2 && palabra.length() >= 4 && w.length() >= 4
                    && Math.abs(w.length() - palabra.length()) <= 1 && Texto.distancia(w, palabra, 1) <= 1) {
                mejor = 1.2;
            }
        }
        return mejor;
    }

    // ------------------------------------------------------------------ catálogo de mantenimiento

    /** true si ya hay una entrada de mantenimiento con ese nombre (sin distinguir mayúsculas ni tildes). */
    public boolean existeNombreMantenimiento(String nombre) {
        return porCodigo(TipoLista.MANTENIMIENTO, nombre) != null;
    }

    /**
     * Opciones existentes para un nivel de la clasificación de mantenimiento.
     * @param nivel        0 = tipo de activo … 4 = subgrupo
     * @param codigoPadre  código elegido en el nivel anterior (se ignora en el nivel 0)
     */
    public List<Nivel> opcionesNivel(int nivel, String codigoPadre) {
        Catalogo c = catalogos.get(TipoLista.MANTENIMIENTO);
        Map<String, Nivel> res = new LinkedHashMap<>();
        if (c == null) {
            return new ArrayList<>();
        }
        String padre = Texto.seguro(codigoPadre).trim();
        for (TerminoOficial t : c.terminos) {
            if (nivel > 0 && !t.nivel(nivel - 1).getCodigo().equals(padre)) {
                continue;
            }
            Nivel n = t.nivel(nivel);
            if (!n.vacio()) {
                res.putIfAbsent(n.getCodigo() + "\t" + n.getNombre(), n);
            }
        }
        List<Nivel> l = new ArrayList<>(res.values());
        l.sort((a, b) -> a.getCodigo().compareTo(b.getCodigo()));
        return l;
    }

    /**
     * Código que le correspondería a una opción nueva en un nivel:
     * <ul>
     *   <li>Tipo de activo: no se puede deducir (son letras); devuelve "".</li>
     *   <li>Uso: el siguiente número de dos cifras de todo el catálogo (p. ej. «38»).</li>
     *   <li>Familia, subfamilia y subgrupo: el código del nivel anterior más
     *       las dos cifras siguientes (p. ej. «0103» → «010303»).</li>
     * </ul>
     */
    public String siguienteCodigo(int nivel, String codigoPadre) {
        if (nivel <= 0) {
            return "";
        }
        Catalogo c = catalogos.get(TipoLista.MANTENIMIENTO);
        String padre = nivel == 1 ? "" : Texto.seguro(codigoPadre).trim();
        int maximo = 0;
        int ancho = 2;
        if (c != null) {
            for (TerminoOficial t : c.terminos) {
                String cod = t.nivel(nivel).getCodigo();
                if (cod.isEmpty() || !cod.startsWith(padre) || cod.length() <= padre.length()) {
                    continue;
                }
                String sufijo = cod.substring(padre.length());
                if (sufijo.chars().allMatch(Character::isDigit) && sufijo.length() <= 6) {
                    maximo = Math.max(maximo, Integer.parseInt(sufijo));
                    ancho = Math.max(ancho, nivel == 1 ? sufijo.length() : 2);
                }
            }
        }
        String s = String.valueOf(maximo + 1);
        while (s.length() < ancho) {
            s = "0" + s;
        }
        return padre + s;
    }

    /** Nombre que ya tiene un código en un nivel, o null si el código no se usa. */
    public String nombreDeCodigo(int nivel, String codigo) {
        Catalogo c = catalogos.get(TipoLista.MANTENIMIENTO);
        String cod = Texto.seguro(codigo).trim();
        if (c == null || cod.isEmpty()) {
            return null;
        }
        for (TerminoOficial t : c.terminos) {
            Nivel n = t.nivel(nivel);
            if (n.getCodigo().equals(cod)) {
                return n.getNombre();
            }
        }
        return null;
    }

    /**
     * Crea una entrada nueva en el catálogo de mantenimiento y la guarda. Se
     * coloca junto a las de su misma clasificación, para que el catálogo
     * exportado siga ordenado.
     * @param once las 11 columnas del catálogo, en su orden
     * @throws IllegalArgumentException si falta el nombre o ya existe
     */
    public TerminoOficial anadirMantenimiento(String[] once) throws IOException {
        Catalogo c = catalogos.get(TipoLista.MANTENIMIENTO);
        String nombre = once.length > 0 && once[0] != null ? once[0].trim() : "";
        if (nombre.isEmpty()) {
            throw new IllegalArgumentException("Falta el nombre de mantenimiento.");
        }
        if (existeNombreMantenimiento(nombre)) {
            throw new IllegalArgumentException("Ya existe una entrada llamada «" + nombre + "».");
        }
        String[] datos = limpiar(once);
        TerminoOficial t = TerminoOficial.mantenimiento(datos, true, LocalDateTime.now().format(FECHA));
        List<TerminoOficial> lista = c == null ? new ArrayList<>() : new ArrayList<>(c.terminos);
        insertarOrdenado(lista, t);
        publicar(c == null ? new Catalogo(TipoLista.MANTENIMIENTO, lista, "Creado en el programa",
                LocalDateTime.now().format(FECHA), HOJA_MANTENIMIENTO)
                : new Catalogo(TipoLista.MANTENIMIENTO, lista, c.origen, c.fecha, c.hoja));
        return t;
    }

    /**
     * Cambia la clasificación de una entrada de mantenimiento. El nombre no se
     * puede cambiar, porque es lo que la identifica en las catalogaciones.
     */
    public TerminoOficial modificarMantenimiento(TerminoOficial anterior, String[] once) throws IOException {
        Catalogo c = catalogos.get(TipoLista.MANTENIMIENTO);
        if (c == null || anterior == null) {
            throw new IllegalArgumentException("No hay catálogo de mantenimiento.");
        }
        String[] datos = limpiar(once);
        datos[0] = anterior.getDatos()[0]; // el nombre se mantiene tal cual
        TerminoOficial t = TerminoOficial.mantenimiento(datos, anterior.isCreadoEnPrograma(),
                anterior.getFechaCreacion());
        List<TerminoOficial> lista = new ArrayList<>(c.terminos);
        int i = lista.indexOf(anterior);
        if (i < 0) {
            throw new IllegalArgumentException("La entrada ya no está en el catálogo.");
        }
        lista.set(i, t);
        publicar(new Catalogo(TipoLista.MANTENIMIENTO, lista, c.origen, c.fecha, c.hoja));
        return t;
    }

    private static String[] limpiar(String[] once) {
        String[] d = new String[TerminoOficial.CABECERA_MANTENIMIENTO.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = i < once.length && once[i] != null ? once[i].trim() : "";
        }
        return d;
    }

    /**
     * Inserta detrás de la última entrada que comparte el nivel de
     * clasificación más concreto posible (subgrupo, subfamilia, familia…).
     */
    static void insertarOrdenado(List<TerminoOficial> lista, TerminoOficial t) {
        for (int nivel = TerminoOficial.NIVELES.length - 1; nivel >= 0; nivel--) {
            String cod = t.nivel(nivel).getCodigo();
            if (cod.isEmpty()) {
                continue;
            }
            int ultimo = -1;
            for (int i = 0; i < lista.size(); i++) {
                if (lista.get(i).nivel(nivel).getCodigo().equals(cod)) {
                    ultimo = i;
                }
            }
            if (ultimo >= 0) {
                lista.add(ultimo + 1, t);
                return;
            }
        }
        lista.add(t);
    }
}
