package catalogador.logica;

import catalogador.modelo.TipoLista;
import catalogador.util.Diario;
import catalogador.util.Texto;
import catalogador.util.Tsv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que el programa "ha aprendido": cada aparato ya clasificado (nombre,
 * marca y modelo originales → término oficial) y las correcciones de marcas
 * y modelos. Se guarda en disco y sirve para todos los proyectos.
 * <p>
 * Pensada para crecer a decenas de miles de aparatos:
 * <ul>
 *   <li>Cada cambio se anota al final de un diario ({@code memoria.diario.tsv})
 *       en lugar de reescribir todo; de vez en cuando se compacta en
 *       {@code memoria.tsv}.</li>
 *   <li>Para sugerir no se compara con toda la memoria: un índice de
 *       trigramas preselecciona los candidatos que comparten más letras y
 *       solo con ellos se calcula la similitud completa.</li>
 * </ul>
 */
public final class Memoria {

    /** Umbral mínimo de parecido para proponer algo. */
    public static final double MINIMO = 0.45;

    static final String VERSION = "2";
    /** Por debajo de este tamaño se compara con todo (es igual de rápido y exacto). */
    static final int ESCANEO_COMPLETO = 5000;
    /** Candidatos que se evalúan a fondo cuando se usa el índice. */
    static final int CANDIDATOS = 400;
    /** Líneas del diario a partir de las cuales se compacta. */
    static final int COMPACTAR = 5000;

    public static final class Entrada {
        private final String nombre;
        private final String marca;
        private final String modelo;
        private final TipoLista lista;
        private final String codigo;
        private final String nombreOficial;
        private final String fecha;
        private final Huella huella;
        private final String marcaNorm;
        private final String modeloNorm;

        Entrada(String nombre, String marca, String modelo, TipoLista lista, String codigo,
                String nombreOficial, String fecha) {
            this.nombre = Texto.seguro(nombre).trim();
            this.marca = Texto.seguro(marca).trim();
            this.modelo = Texto.seguro(modelo).trim();
            this.lista = lista;
            this.codigo = Texto.seguro(codigo).trim();
            this.nombreOficial = Texto.seguro(nombreOficial).trim();
            this.fecha = Texto.seguro(fecha);
            this.huella = new Huella(this.nombre);
            this.marcaNorm = Texto.normalizar(this.marca);
            this.modeloNorm = Texto.normalizar(this.modelo);
        }

        public String getNombre() {
            return nombre;
        }

        public String getMarca() {
            return marca;
        }

        public String getModelo() {
            return modelo;
        }

        public TipoLista getLista() {
            return lista;
        }

        /** GMDN: el código. Mantenimiento: el nombre de mantenimiento. */
        public String getCodigo() {
            return codigo;
        }

        public String getNombreOficial() {
            return nombreOficial;
        }

        public String getFecha() {
            return fecha;
        }

        String linea() {
            return Tsv.linea("E", nombre, marca, modelo, lista.name(), codigo, nombreOficial, fecha);
        }
    }

    /** Lista de enteros que crece sin crear objetos Integer. */
    private static final class Enteros {
        int[] v = new int[4];
        int n;

        void anadir(int x) {
            if (n == v.length) {
                v = Arrays.copyOf(v, n * 2);
            }
            v[n++] = x;
        }
    }

    private final Path fichero;
    private final Diario diario;
    private final Path ficheroCorreccionesV1;

    private final List<Entrada> entradas = new ArrayList<>();          // null = olvidada
    private final Map<String, Integer> porClave = new HashMap<>();
    private final Map<Integer, Enteros> indice = new HashMap<>();
    private int vivas;
    private final Map<String, String> marcas = new LinkedHashMap<>();
    private final Map<String, String> modelos = new LinkedHashMap<>();
    private final List<String> pendientes = new ArrayList<>();
    private final List<String> apartados = new ArrayList<>();

    /** @param carpeta carpeta de datos (la de memoria.tsv) */
    public Memoria(Path carpeta) {
        this.fichero = carpeta.resolve("memoria.tsv");
        this.diario = new Diario(carpeta.resolve("memoria.diario.tsv"));
        this.ficheroCorreccionesV1 = carpeta.resolve("correcciones.tsv");
    }

    // ------------------------------------------------------------------ disco

    public synchronized void cargar() throws IOException {
        entradas.clear();
        porClave.clear();
        indice.clear();
        vivas = 0;
        marcas.clear();
        modelos.clear();
        pendientes.clear();
        apartados.clear();
        if (Files.exists(fichero)) {
            String[] cab = Tsv.campos(Tsv.primeraLinea(fichero));
            if (!"#CATALOGADOR-MEMORIA".equals(cab[0]) || !VERSION.equals(Tsv.campo(cab, 1))) {
                apartar(fichero, "memoria.v1.tsv");
                if (Files.exists(ficheroCorreccionesV1)) {
                    apartar(ficheroCorreccionesV1, "correcciones.v1.tsv");
                }
            } else {
                Tsv.recorrer(fichero, l -> aplicar(l));
            }
        }
        for (String l : diario.leer()) {
            aplicar(l);
        }
    }

    private void apartar(Path p, String nuevoNombre) throws IOException {
        Files.move(p, p.resolveSibling(nuevoNombre), StandardCopyOption.REPLACE_EXISTING);
        apartados.add(nuevoNombre);
    }

    /** Ficheros de la versión anterior apartados en la última carga. */
    public List<String> getApartados() {
        return Collections.unmodifiableList(apartados);
    }

    private void aplicar(String linea) {
        if (linea.isEmpty() || linea.startsWith("#")) {
            return;
        }
        String[] c = Tsv.campos(linea);
        switch (c[0]) {
            case "E":
                if (c.length >= 8) {
                    TipoLista lista = TipoLista.desdeTexto(c[4]);
                    if (lista != null && !c[5].isEmpty()) {
                        poner(new Entrada(c[1], c[2], c[3], lista, c[5], c[6], c[7]));
                    }
                }
                break;
            case "O":
                if (c.length >= 4) {
                    quitar(clave(c[1], c[2], c[3]));
                }
                break;
            case "M":
                if (c.length >= 3) {
                    marcas.put(c[1], c[2]);
                }
                break;
            case "D":
                if (c.length >= 3) {
                    modelos.put(c[1], c[2]);
                }
                break;
            default:
                break; // línea dañada: se ignora
        }
    }

    /**
     * Anota en el diario los cambios pendientes (es rápido: solo añade
     * líneas). Si el diario ha crecido mucho, lo compacta.
     */
    public synchronized void guardarCambios() throws IOException {
        if (!pendientes.isEmpty()) {
            diario.anadir(pendientes);
            pendientes.clear();
        }
        if (diario.tamano() > COMPACTAR) {
            compactar();
        }
    }

    /** Escribe la memoria completa en memoria.tsv y vacía el diario. */
    public synchronized void compactar() throws IOException {
        if (!pendientes.isEmpty()) {
            diario.anadir(pendientes);
            pendientes.clear();
        }
        Tsv.escribirAtomico(fichero, w -> {
            w.write("#CATALOGADOR-MEMORIA\t" + VERSION + "\n");
            for (Entrada e : entradas) {
                if (e != null) {
                    w.write(e.linea());
                    w.write('\n');
                }
            }
            for (Map.Entry<String, String> e : marcas.entrySet()) {
                w.write(Tsv.linea("M", e.getKey(), e.getValue()));
                w.write('\n');
            }
            for (Map.Entry<String, String> e : modelos.entrySet()) {
                w.write(Tsv.linea("D", e.getKey(), e.getValue()));
                w.write('\n');
            }
        });
        diario.vaciar();
    }

    public synchronized boolean hayCambiosSinGuardar() {
        return !pendientes.isEmpty();
    }

    // ------------------------------------------------------------------ aprender

    private static String clave(String nombre, String marca, String modelo) {
        return Texto.normalizar(nombre) + "|" + Texto.normalizar(marca) + "|" + Texto.normalizar(modelo);
    }

    private void poner(Entrada e) {
        String k = clave(e.nombre, e.marca, e.modelo);
        quitar(k); // la nueva queda al final (la más reciente)
        int i = entradas.size();
        entradas.add(e);
        porClave.put(k, i);
        vivas++;
        for (int t : e.huella.getTrigramas()) {
            indice.computeIfAbsent(t, x -> new Enteros()).anadir(i);
        }
    }

    private void quitar(String clave) {
        Integer i = porClave.remove(clave);
        if (i != null && entradas.get(i) != null) {
            entradas.set(i, null); // el índice la ignora a partir de ahora
            vivas--;
        }
    }

    public synchronized void aprender(String nombre, String marca, String modelo, TipoLista lista, String codigo,
                                      String nombreOficial, String fecha) {
        if (Texto.vacio(nombre) || lista == null || Texto.vacio(codigo)) {
            return;
        }
        Entrada e = new Entrada(nombre, marca, modelo, lista, codigo, nombreOficial, fecha);
        poner(e);
        pendientes.add(e.linea());
    }

    public synchronized void olvidar(String nombre, String marca, String modelo) {
        String k = clave(nombre, marca, modelo);
        if (porClave.containsKey(k)) {
            quitar(k);
            pendientes.add(Tsv.linea("O", Texto.seguro(nombre).trim(), Texto.seguro(marca).trim(),
                    Texto.seguro(modelo).trim()));
        }
    }

    public synchronized void aprenderMarca(String original, String corregida) {
        aprenderCorreccion(marcas, "M", original, corregida);
    }

    public synchronized void aprenderModelo(String original, String corregido) {
        aprenderCorreccion(modelos, "D", original, corregido);
    }

    private void aprenderCorreccion(Map<String, String> mapa, String tipo, String original, String corregido) {
        String k = Texto.normalizar(original);
        if (k.isEmpty() || Texto.vacio(corregido)) {
            return;
        }
        String v = corregido.trim();
        if (!v.equals(mapa.put(k, v))) {
            pendientes.add(Tsv.linea(tipo, k, v));
        }
    }

    /** Marca "limpia" usada antes para esta marca original, o null si no hay. */
    public synchronized String marcaConocida(String original) {
        return marcas.get(Texto.normalizar(original));
    }

    public synchronized String modeloConocido(String original) {
        return modelos.get(Texto.normalizar(original));
    }

    // ------------------------------------------------------------------ sugerir

    /**
     * Busca lo más parecido ya clasificado. Devuelve como mucho {@code max}
     * propuestas distintas, de más a menos parecido.
     */
    public synchronized List<Sugerencia> sugerir(String nombre, String marca, String modelo, int max) {
        Huella h = new Huella(nombre);
        String marcaNorm = Texto.normalizar(marca);
        String modeloNorm = Texto.normalizar(modelo);
        Map<String, Sugerencia> mejores = new HashMap<>();
        for (int i : candidatos(h)) {
            Entrada e = entradas.get(i);
            if (e == null) {
                continue;
            }
            double s = h.similitud(e.huella);
            if (s < MINIMO) {
                continue;
            }
            if (s < 1) {
                if (!marcaNorm.isEmpty() && marcaNorm.equals(e.marcaNorm)) {
                    s = Math.min(0.99, s + 0.03);
                }
                if (!modeloNorm.isEmpty() && modeloNorm.equals(e.modeloNorm)) {
                    s = Math.min(0.99, s + 0.02);
                }
            } else if (!marcaNorm.equals(e.marcaNorm) || !modeloNorm.equals(e.modeloNorm)) {
                // Mismo nombre pero otra marca/modelo: casi seguro, pero no idéntico
                s = 0.98;
            }
            String k = e.lista.name() + "|" + Texto.normalizar(e.codigo);
            Sugerencia actual = mejores.get(k);
            if (actual == null || s > actual.getSimilitud()) {
                mejores.put(k, new Sugerencia(e.lista, e.codigo, e.nombreOficial, s, e));
            }
        }
        List<Sugerencia> lista = new ArrayList<>(mejores.values());
        lista.sort((a, b) -> Double.compare(b.getSimilitud(), a.getSimilitud()));
        return lista.size() > max ? new ArrayList<>(lista.subList(0, max)) : lista;
    }

    /** Posiciones de las entradas que merece la pena comparar a fondo. */
    private int[] candidatos(Huella h) {
        int total = entradas.size();
        if (vivas <= ESCANEO_COMPLETO) {
            int[] todas = new int[total];
            for (int i = 0; i < total; i++) {
                todas[i] = i;
            }
            return todas;
        }
        int[] trig = h.getTrigramas();
        if (trig.length == 0) {
            return new int[0];
        }
        int[] comunes = new int[total];
        Enteros tocadas = new Enteros();
        for (int t : trig) {
            Enteros l = indice.get(t);
            if (l == null) {
                continue;
            }
            for (int k = 0; k < l.n; k++) {
                int i = l.v[k];
                if (comunes[i]++ == 0) {
                    tocadas.anadir(i);
                }
            }
        }
        if (tocadas.n <= CANDIDATOS) {
            return Arrays.copyOf(tocadas.v, tocadas.n);
        }
        // Se quedan las que más trigramas comparten en proporción (coeficiente de Dice).
        long[] orden = new long[tocadas.n];
        int m = 0;
        for (int k = 0; k < tocadas.n; k++) {
            int i = tocadas.v[k];
            Entrada e = entradas.get(i);
            if (e == null) {
                continue;
            }
            double d = 2.0 * comunes[i] / (trig.length + e.huella.getTrigramas().length);
            long puntos = (long) (d * 1_000_000);
            orden[m++] = (puntos << 32) | i; // se ordena por puntos y se recupera la posición
        }
        Arrays.sort(orden, 0, m);
        int n = Math.min(CANDIDATOS, m);
        int[] res = new int[n];
        for (int k = 0; k < n; k++) {
            res[k] = (int) (orden[m - 1 - k] & 0xFFFFFFFFL);
        }
        return res;
    }

    public synchronized int tamano() {
        return vivas;
    }

    public Path getFichero() {
        return fichero;
    }
}
