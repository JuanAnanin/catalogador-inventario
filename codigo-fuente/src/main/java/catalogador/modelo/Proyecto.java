package catalogador.modelo;

import catalogador.util.Texto;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Un trabajo de catalogación: los equipos del inventario (solo los que no
 * están de baja y solo los datos necesarios) y los grupos con su estado.
 * <p>
 * Para admitir inventarios de cientos de miles de equipos, los datos se
 * guardan en columnas (un array por dato) y los textos repetidos (centros,
 * nombres, marcas…) se comparten en memoria en lugar de duplicarse.
 */
public final class Proyecto {

    public static final String SIN_NOMBRE = "(sin nombre)";

    private String nombre = "";
    private String ficheroOrigen = "";
    private String creado = "";
    private Path carpeta;
    private boolean agruparPorMarcaYModelo = true;

    // Estadísticas de la importación
    private long filasLeidas;
    private long descartadosBaja;
    private long descartadosOtros;

    // Equipos, por columnas
    private int n;
    private String[] centro = new String[1024];
    private String[] id = new String[1024];
    private String[] nombreEq = new String[1024];
    private String[] referencia = new String[1024];
    private String[] marca = new String[1024];
    private String[] modelo = new String[1024];
    private String[] tipo = new String[1024];
    private Map<String, String> textos = new HashMap<>();

    private final List<Grupo> grupos = new ArrayList<>();
    private final Map<String, Grupo> gruposPorClave = new HashMap<>();
    private Grupo[] grupoDeFila = new Grupo[0];

    // ------------------------------------------------------------------ carga

    public void anadirEquipo(String centro, String id, String nombre, String referencia, String marca,
                             String modelo, String tipo) {
        if (n == this.id.length) {
            int c = n * 2;
            this.centro = Arrays.copyOf(this.centro, c);
            this.id = Arrays.copyOf(this.id, c);
            this.nombreEq = Arrays.copyOf(this.nombreEq, c);
            this.referencia = Arrays.copyOf(this.referencia, c);
            this.marca = Arrays.copyOf(this.marca, c);
            this.modelo = Arrays.copyOf(this.modelo, c);
            this.tipo = Arrays.copyOf(this.tipo, c);
        }
        this.centro[n] = compartir(centro);
        this.id[n] = Texto.seguro(id).trim();
        this.nombreEq[n] = compartir(nombre);
        this.referencia[n] = compartir(referencia);
        this.marca[n] = compartir(marca);
        this.modelo[n] = compartir(modelo);
        this.tipo[n] = compartir(tipo);
        n++;
    }

    private String compartir(String s) {
        String v = s == null ? "" : s.trim();
        if (textos == null) {
            textos = new HashMap<>();
        }
        String previo = textos.putIfAbsent(v, v);
        return previo != null ? previo : v;
    }

    /** Ajusta los arrays al tamaño final y libera la tabla de textos compartidos. */
    public void terminarCarga() {
        centro = Arrays.copyOf(centro, n);
        id = Arrays.copyOf(id, n);
        nombreEq = Arrays.copyOf(nombreEq, n);
        referencia = Arrays.copyOf(referencia, n);
        marca = Arrays.copyOf(marca, n);
        modelo = Arrays.copyOf(modelo, n);
        tipo = Arrays.copyOf(tipo, n);
        textos = null;
    }

    // ------------------------------------------------------------------ agrupación

    /**
     * Junta los equipos iguales en grupos y los ordena de más a menos equipos,
     * para revisar primero lo que más pesa en el inventario.
     */
    public void agrupar() {
        Map<String, String> claves = new HashMap<>();
        Map<String, String> normalizados = new HashMap<>();
        Map<String, Grupo> mapa = new LinkedHashMap<>();
        grupoDeFila = new Grupo[n];
        StringBuilder k = new StringBuilder(96);
        for (int i = 0; i < n; i++) {
            String cn = claves.computeIfAbsent(nombreEq[i], Texto::clave);
            k.setLength(0);
            k.append(cn.isEmpty() ? SIN_NOMBRE : cn).append('|');
            if (agruparPorMarcaYModelo) {
                k.append(normalizados.computeIfAbsent(marca[i], Texto::normalizar)).append('|')
                        .append(normalizados.computeIfAbsent(modelo[i], Texto::normalizar)).append('|');
            }
            String t = tipo[i].toUpperCase();
            k.append(t);
            String clave = k.toString();
            Grupo g = mapa.get(clave);
            if (g == null) {
                g = new Grupo(clave, t);
                mapa.put(clave, g);
            }
            g.anadirFila(i, nombreEq[i].isEmpty() ? SIN_NOMBRE : nombreEq[i], referencia[i], marca[i], modelo[i]);
            grupoDeFila[i] = g;
        }
        grupos.clear();
        gruposPorClave.clear();
        grupos.addAll(mapa.values());
        grupos.sort((a, b) -> {
            int c = Integer.compare(b.getEquipos(), a.getEquipos());
            return c != 0 ? c : a.getClave().compareTo(b.getClave());
        });
        for (int i = 0; i < grupos.size(); i++) {
            Grupo g = grupos.get(i);
            g.cerrar(i);
            gruposPorClave.put(g.getClave(), g);
        }
    }

    // ------------------------------------------------------------------ estadísticas

    public int contar(EstadoGrupo e) {
        int c = 0;
        for (Grupo g : grupos) {
            if (g.getEstado() == e) {
                c++;
            }
        }
        return c;
    }

    public long equiposTotales() {
        return n;
    }

    public long equipos(EstadoGrupo e) {
        long c = 0;
        for (Grupo g : grupos) {
            if (g.getEstado() == e) {
                c += g.getEquipos();
            }
        }
        return c;
    }

    /** Centros (Equipo_codiCentro) con su número de equipos, ordenados. */
    public Map<String, Integer> centros() {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            m.merge(centro[i], 1, Integer::sum);
        }
        return m;
    }

    public int posicion(Grupo g) {
        return g.getOrden();
    }

    public Grupo grupoDeFila(int fila) {
        return grupoDeFila[fila];
    }

    // ------------------------------------------------------------------ acceso a los equipos

    public int numEquipos() {
        return n;
    }

    public String centro(int i) {
        return centro[i];
    }

    public String id(int i) {
        return id[i];
    }

    public String nombre(int i) {
        return nombreEq[i];
    }

    public String referencia(int i) {
        return referencia[i];
    }

    public String marca(int i) {
        return marca[i];
    }

    public String modelo(int i) {
        return modelo[i];
    }

    public String tipo(int i) {
        return tipo[i];
    }

    // ------------------------------------------------------------------ getters / setters

    public List<Grupo> getGrupos() {
        return Collections.unmodifiableList(grupos);
    }

    public Grupo getGrupo(String clave) {
        return gruposPorClave.get(clave);
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getFicheroOrigen() {
        return ficheroOrigen;
    }

    public void setFicheroOrigen(String ficheroOrigen) {
        this.ficheroOrigen = ficheroOrigen;
    }

    public String getCreado() {
        return creado;
    }

    public void setCreado(String creado) {
        this.creado = creado;
    }

    public Path getCarpeta() {
        return carpeta;
    }

    public void setCarpeta(Path carpeta) {
        this.carpeta = carpeta;
    }

    public boolean isAgruparPorMarcaYModelo() {
        return agruparPorMarcaYModelo;
    }

    public void setAgruparPorMarcaYModelo(boolean agruparPorMarcaYModelo) {
        this.agruparPorMarcaYModelo = agruparPorMarcaYModelo;
    }

    public long getFilasLeidas() {
        return filasLeidas;
    }

    public long getDescartadosBaja() {
        return descartadosBaja;
    }

    public long getDescartadosOtros() {
        return descartadosOtros;
    }

    public void setEstadisticas(long leidas, long deBaja, long otros) {
        this.filasLeidas = leidas;
        this.descartadosBaja = deBaja;
        this.descartadosOtros = otros;
    }
}
