package catalogador.modelo;

import catalogador.util.Texto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Una entrada de un catálogo oficial.
 * <ul>
 *   <li><b>GMDN</b>: código, nombre y descripción. Se identifica por su código.</li>
 *   <li><b>Mantenimiento</b>: nombre más su clasificación en cinco niveles (tipo de
 *       activo, uso, familia, subfamilia y subgrupo), cada uno con código y nombre.
 *       No tiene código propio: se identifica por su <b>nombre</b>, que es único.</li>
 * </ul>
 * Se conservan los valores tal y como venían en el Excel (incluidos espacios),
 * para poder exportar el catálogo igual que el original.
 */
public final class TerminoOficial {

    public static final List<String> CABECERA_GMDN = Collections.unmodifiableList(Arrays.asList(
            "CÓDIGO GMDN", "NOMBRE GMDN", "DESCRIPCIÓN GMDN"));

    public static final List<String> CABECERA_MANTENIMIENTO = Collections.unmodifiableList(Arrays.asList(
            "NOMBRE MANTENIMIENTO", "CODIGO TIPO ACTIVO", "NOMBRE TIPO ACTIVO", "CODIGO USO", "NOMBRE USO",
            "CODIGO FAMILIA", "NOMBRE FAMILIA", "CODIGO SUB FAMILIA", "NOMBRE SUB FAMILIA",
            "CODIGO SUB GRUPO FAMILIA", "NOMBRE SUB GRUPO FAMILIA"));

    /** Niveles de la clasificación de mantenimiento, de más general a más concreto. */
    public static final String[] NIVELES = {"Tipo de activo", "Uso", "Familia", "Subfamilia", "Subgrupo"};

    /** Código y nombre de un nivel de la clasificación de mantenimiento. */
    public static final class Nivel {
        private final String codigo;
        private final String nombre;

        public Nivel(String codigo, String nombre) {
            this.codigo = Texto.limpio(codigo);
            this.nombre = Texto.limpio(nombre);
        }

        public String getCodigo() {
            return codigo;
        }

        public String getNombre() {
            return nombre;
        }

        public boolean vacio() {
            return codigo.isEmpty() && nombre.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Nivel && ((Nivel) o).codigo.equals(codigo) && ((Nivel) o).nombre.equals(nombre);
        }

        @Override
        public int hashCode() {
            return codigo.hashCode() * 31 + nombre.hashCode();
        }

        @Override
        public String toString() {
            return nombre + (codigo.isEmpty() ? "" : "  (" + codigo + ")");
        }
    }

    private final TipoLista lista;
    private final String[] datos;          // valores originales (3 para GMDN, 11 para Mantenimiento)
    private final boolean creadoEnPrograma;
    private final String fechaCreacion;

    private final String codigo;
    private final String nombre;
    private final String descripcion;

    // Versiones normalizadas, precalculadas para que el buscador sea instantáneo.
    private final String codigoNorm;
    private final String codigoCompacto;
    private final String nombreNorm;
    private final String[] palabrasNombre;
    private final String descripcionNorm;

    private TerminoOficial(TipoLista lista, String[] datos, boolean creado, String fecha) {
        this.lista = lista;
        int n = lista == TipoLista.GMDN ? 3 : 11;
        this.datos = new String[n];
        for (int i = 0; i < n; i++) {
            this.datos[i] = i < datos.length && datos[i] != null ? datos[i] : "";
        }
        this.creadoEnPrograma = creado;
        this.fechaCreacion = Texto.seguro(fecha);
        if (lista == TipoLista.GMDN) {
            codigo = Texto.limpio(this.datos[0]);
            nombre = Texto.limpio(this.datos[1]);
            descripcion = Texto.limpio(this.datos[2]);
        } else {
            nombre = Texto.limpio(this.datos[0]);
            codigo = nombre;
            List<String> partes = new ArrayList<>();
            for (int i = 0; i < NIVELES.length; i++) {
                String nom = nivel(i).getNombre();
                if (!nom.isEmpty()) {
                    partes.add(nom);
                }
            }
            descripcion = String.join(" › ", partes);
        }
        codigoNorm = Texto.normalizar(codigo);
        codigoCompacto = codigoNorm.replace(" ", "");
        nombreNorm = Texto.normalizar(nombre);
        palabrasNombre = Texto.palabras(nombreNorm);
        descripcionNorm = Texto.normalizar(descripcion);
    }

    public static TerminoOficial gmdn(String codigo, String nombre, String descripcion) {
        return new TerminoOficial(TipoLista.GMDN, new String[]{codigo, nombre, descripcion}, false, "");
    }

    /** @param once las 11 columnas del catálogo de mantenimiento, en su orden */
    public static TerminoOficial mantenimiento(String[] once, boolean creadoEnPrograma, String fechaCreacion) {
        return new TerminoOficial(TipoLista.MANTENIMIENTO, once, creadoEnPrograma, fechaCreacion);
    }

    /** Nivel i de la clasificación de mantenimiento (0 = tipo de activo … 4 = subgrupo). */
    public Nivel nivel(int i) {
        if (lista != TipoLista.MANTENIMIENTO) {
            return new Nivel("", "");
        }
        return new Nivel(datos[1 + 2 * i], datos[2 + 2 * i]);
    }

    public TipoLista getLista() {
        return lista;
    }

    /** GMDN: el código. Mantenimiento: el nombre (que lo identifica). */
    public String getCodigo() {
        return codigo;
    }

    /** Código para mostrar o exportar: el GMDN, o vacío en Mantenimiento. */
    public String getCodigoGmdn() {
        return lista == TipoLista.GMDN ? codigo : "";
    }

    public String getNombre() {
        return nombre;
    }

    /** GMDN: la descripción. Mantenimiento: la clasificación (tipo › uso › familia…). */
    public String getDescripcion() {
        return descripcion;
    }

    public String[] getDatos() {
        return datos.clone();
    }

    public boolean isCreadoEnPrograma() {
        return creadoEnPrograma;
    }

    public String getFechaCreacion() {
        return fechaCreacion;
    }

    public String getCodigoNorm() {
        return codigoNorm;
    }

    /** Código sin espacios ni signos: "MOB-001" → "mob001". */
    public String getCodigoCompacto() {
        return codigoCompacto;
    }

    public String getNombreNorm() {
        return nombreNorm;
    }

    public String[] getPalabrasNombre() {
        return palabrasNombre;
    }

    public String getDescripcionNorm() {
        return descripcionNorm;
    }

    /** Texto breve: «GMDN 10003 · Faja abdominal…» o «Mantenimiento · MANGUERA». */
    public String etiqueta() {
        return lista == TipoLista.GMDN ? "GMDN " + codigo + " · " + nombre : "Mantenimiento · " + nombre;
    }

    @Override
    public String toString() {
        return etiqueta();
    }
}
