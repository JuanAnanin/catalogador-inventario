package catalogador.modelo;

import catalogador.util.Texto;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Conjunto de equipos del inventario que son "el mismo aparato": mismo nombre
 * (normalizado), mismo tipo de activo y, por defecto, misma marca y modelo.
 * Se revisa una sola vez y la decisión se aplica a todos sus equipos.
 */
public final class Grupo {

    /** Código de Equipo_codiTipoActivo que identifica la electromedicina. */
    public static final String TIPO_ELECTROMEDICINA = "E";

    private final String clave;
    private final String tipoActivo;

    // Texto más frecuente dentro del grupo (el que se muestra).
    private String nombre = "";
    private String marca = "";
    private String modelo = "";
    private String referencia = "";

    private int[] filas = new int[2];
    private int numFilas;
    private final Variantes variantesNombre = new Variantes();
    private final Variantes variantesMarca = new Variantes();
    private final Variantes variantesModelo = new Variantes();
    private final Variantes variantesReferencia = new Variantes();
    private boolean variasMarcas;
    private boolean variosModelos;
    private int orden = -1;
    private String textoFiltro;

    // Resultado de la revisión
    private EstadoGrupo estado = EstadoGrupo.PENDIENTE;
    private TipoLista lista;
    private String codigo = "";
    private String nombreOficial = "";
    private String marcaCorregida;   // null = no se ha tocado
    private String modeloCorregido;  // null = no se ha tocado
    private String fecha = "";
    private String origen = "";

    public Grupo(String clave, String tipoActivo) {
        this.clave = clave;
        this.tipoActivo = Texto.seguro(tipoActivo).trim();
    }

    void anadirFila(int indiceFila, String nombre, String referencia, String marca, String modelo) {
        if (numFilas == filas.length) {
            filas = Arrays.copyOf(filas, filas.length * 2);
        }
        filas[numFilas++] = indiceFila;
        variantesNombre.anadir(nombre.trim());
        variantesReferencia.anadir(referencia.trim());
        variantesMarca.anadir(marca.trim());
        variantesModelo.anadir(modelo.trim());
    }

    /** Elige como texto representativo la variante más repetida y libera memoria sobrante. */
    void cerrar(int orden) {
        this.orden = orden;
        nombre = variantesNombre.masFrecuente();
        marca = variantesMarca.masFrecuente();
        modelo = variantesModelo.masFrecuente();
        referencia = variantesReferencia.masFrecuente();
        variasMarcas = distintasNormalizadas(variantesMarca) > 1;
        variosModelos = distintasNormalizadas(variantesModelo) > 1;
        if (filas.length != numFilas) {
            filas = Arrays.copyOf(filas, numFilas);
        }
    }

    private static int distintasNormalizadas(Variantes v) {
        if (v.distintas() <= 1) {
            return v.distintas();
        }
        Set<String> s = new HashSet<>();
        for (String k : v.claves()) {
            s.add(Texto.normalizar(k));
        }
        return s.size();
    }

    // ---- Acciones de revisión ----

    public void asignar(TipoLista lista, String codigo, String nombreOficial, String origen, String fecha) {
        this.estado = EstadoGrupo.REVISADO;
        this.lista = lista;
        this.codigo = Texto.seguro(codigo);
        this.nombreOficial = Texto.seguro(nombreOficial);
        this.origen = Texto.seguro(origen);
        this.fecha = Texto.seguro(fecha);
    }

    /** Se deja como duda para revisarlo más tarde (sin nombre oficial asignado). */
    public void posponer(String fecha) {
        this.estado = EstadoGrupo.POSPUESTO;
        this.lista = null;
        this.codigo = "";
        this.nombreOficial = "";
        this.origen = "";
        this.fecha = fecha;
    }

    public void reiniciar() {
        estado = EstadoGrupo.PENDIENTE;
        lista = null;
        codigo = "";
        nombreOficial = "";
        origen = "";
        fecha = "";
    }

    // ---- Consultas ----

    public boolean isElectromedicina() {
        return TIPO_ELECTROMEDICINA.equalsIgnoreCase(tipoActivo);
    }

    public String getTipoActivo() {
        return tipoActivo;
    }

    public boolean variasMarcas() {
        return variasMarcas;
    }

    public boolean variosModelos() {
        return variosModelos;
    }

    public String getClave() {
        return clave;
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

    public String getReferencia() {
        return referencia;
    }

    /** Número de equipos del grupo. */
    public int getEquipos() {
        return numFilas;
    }

    public int fila(int i) {
        return filas[i];
    }

    public int getOrden() {
        return orden;
    }

    public Variantes getVariantesNombre() {
        return variantesNombre;
    }

    public Variantes getVariantesMarca() {
        return variantesMarca;
    }

    public Variantes getVariantesModelo() {
        return variantesModelo;
    }

    public Variantes getVariantesReferencia() {
        return variantesReferencia;
    }

    /** Texto normalizado para el filtro de la cola (se calcula una vez). */
    public String textoFiltro() {
        if (textoFiltro == null) {
            textoFiltro = Texto.normalizar(nombre + " " + marca + " " + modelo + " " + referencia);
        }
        String asignado = nombreOficial.isEmpty() ? "" : " " + Texto.normalizar(nombreOficial + " " + codigo);
        return textoFiltro + asignado;
    }

    public EstadoGrupo getEstado() {
        return estado;
    }

    public void setEstado(EstadoGrupo estado) {
        this.estado = estado;
    }

    public TipoLista getLista() {
        return lista;
    }

    public void setLista(TipoLista lista) {
        this.lista = lista;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = Texto.seguro(codigo);
    }

    public String getNombreOficial() {
        return nombreOficial;
    }

    public void setNombreOficial(String nombreOficial) {
        this.nombreOficial = Texto.seguro(nombreOficial);
    }

    public String getMarcaCorregida() {
        return marcaCorregida;
    }

    public void setMarcaCorregida(String marcaCorregida) {
        this.marcaCorregida = marcaCorregida;
    }

    public String getModeloCorregido() {
        return modeloCorregido;
    }

    public void setModeloCorregido(String modeloCorregido) {
        this.modeloCorregido = modeloCorregido;
    }

    public String getFecha() {
        return fecha;
    }

    public void setFecha(String fecha) {
        this.fecha = Texto.seguro(fecha);
    }

    public String getOrigen() {
        return origen;
    }

    public void setOrigen(String origen) {
        this.origen = Texto.seguro(origen);
    }

    /** «GMDN 10003 · Faja…» o «Mantenimiento · MANGUERA», o "" si no está asignado. */
    public String asignacionTexto() {
        if (lista == null || nombreOficial.isEmpty()) {
            return "";
        }
        return lista == TipoLista.GMDN ? "GMDN " + codigo + " · " + nombreOficial : "Mantenimiento · " + nombreOficial;
    }
}
