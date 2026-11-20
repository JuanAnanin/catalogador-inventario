package catalogador.ia;

import catalogador.logica.Catalogos;
import catalogador.logica.Memoria;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;

import java.util.ArrayList;
import java.util.List;

/**
 * Aceptar propuestas de la IA sin revisarlas una a una: «IA → Aceptar en bloque»
 * (a mano, cuando se pide) y la aceptación automática de la opción «Aceptar sin
 * preguntar las propuestas de la IA con al menos N % de confianza» (desactivada
 * por defecto). Solo se aceptan propuestas de grupos <b>pendientes</b> (los
 * pospuestos y los revisados se respetan) cuyo término está en el catálogo.
 */
public final class AceptacionIA {

    /** Origen de las aceptadas con «Aceptar en bloque». */
    public static final String EN_BLOQUE = "IA en bloque";
    /** Origen de las aceptadas solas (opción de catalogación automática). */
    public static final String AUTOMATICA = Grupo.ORIGEN_IA_AUTOMATICA;

    private AceptacionIA() {
    }

    /** Grupos pendientes (de los dados) cuya propuesta tiene al menos esa confianza y cuyo término existe. */
    public static List<Grupo> seguros(Iterable<Grupo> grupos, PropuestasGuardadas propuestas, Catalogos catalogos,
                                      int umbral) {
        List<Grupo> l = new ArrayList<>();
        if (propuestas == null) {
            return l;
        }
        for (Grupo g : grupos) {
            PropuestaIA p = g.getEstado() == EstadoGrupo.PENDIENTE ? propuestas.get(g.getClave()) : null;
            if (p != null && p.tieneTermino() && p.getConfianza() >= umbral
                    && catalogos.porCodigo(p.getLista(), p.getCodigo()) != null) {
                l.add(g);
            }
        }
        return l;
    }

    /**
     * Asigna a cada grupo su propuesta, con el origen «tipo: servicio (NN% de confianza)»,
     * y la aprende en la memoria. No escribe en disco: quien llama guarda los grupos
     * ({@code Almacen.registrarCambios}) y la memoria ({@code Memoria.guardarCambios}).
     */
    public static void aceptar(List<Grupo> grupos, PropuestasGuardadas propuestas, Memoria memoria, String tipo,
                               String fecha) {
        for (Grupo g : grupos) {
            PropuestaIA p = propuestas.get(g.getClave());
            g.asignar(p.getLista(), p.getCodigo(), p.getNombreOficial(), tipo + ": " + p.getFuente() + " ("
                    + p.getConfianza() + "% de confianza)", fecha);
            memoria.aprender(g.getNombre(), g.getMarca(), g.getModelo(), p.getLista(), p.getCodigo(),
                    p.getNombreOficial(), fecha);
        }
    }
}
