package catalogador.logica;

import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TerminoOficial;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalogación automática de los equipos idénticos a otros ya catalogados
 * (opción «Catalogar sin preguntar los idénticos», desactivada por defecto).
 * <p>
 * Un grupo pendiente se cataloga solo cuando en la memoria hay un equipo
 * <b>idéntico</b> ya catalogado: las mismas palabras del nombre (en cualquier
 * orden), la misma marca y el mismo modelo, sin distinguir mayúsculas, tildes
 * ni signos; es decir, cuando la sugerencia saldría al 100 %. No se toca nada si:
 * <ul>
 *   <li>el grupo no está pendiente (los revisados y los pospuestos se respetan);</li>
 *   <li>el grupo mezcla varias marcas o modelos, o no tiene nombre;</li>
 *   <li>los idénticos se catalogaron de formas distintas (hay que decidir);</li>
 *   <li>el término ya no está en el catálogo cargado.</li>
 * </ul>
 * Funciona igual en modo manual y en modo IA (la memoria es la misma). Esta
 * clase solo cambia los grupos; quien la llama los guarda con
 * {@link Almacen#registrarCambios} (o {@link Almacen#guardarEstado}).
 */
public final class CatalogacionAutomatica {

    /** Origen que queda anotado en los grupos catalogados así. */
    public static final String ORIGEN = Grupo.ORIGEN_AUTOMATICO + ": idéntico a uno ya catalogado (100 %)";

    private CatalogacionAutomatica() {
    }

    /** Lo que se le asignaría a un grupo, o null si no se le puede asignar nada solo. */
    public static TerminoOficial terminoPara(Grupo g, Memoria memoria, Catalogos catalogos) {
        if (g.getEstado() != EstadoGrupo.PENDIENTE || g.variasMarcas() || g.variosModelos()
                || Proyecto.SIN_NOMBRE.equals(g.getNombre())) {
            return null;
        }
        Memoria.Entrada e = memoria.identica(g.claveIdentico());
        if (e == null) {
            return null;
        }
        return catalogos.porCodigo(e.getLista(), e.getCodigo());
    }

    /** Grupos (de los dados) que se catalogarían solos ahora mismo, sin cambiar nada. */
    public static List<Grupo> candidatos(Iterable<Grupo> grupos, Memoria memoria, Catalogos catalogos) {
        List<Grupo> l = new ArrayList<>();
        for (Grupo g : grupos) {
            if (terminoPara(g, memoria, catalogos) != null) {
                l.add(g);
            }
        }
        return l;
    }

    /**
     * Cataloga los grupos pendientes idénticos a otros ya catalogados. La marca y el
     * modelo quedan como se habrían propuesto en pantalla (la corrección aprendida o
     * el original). Devuelve los grupos cambiados (para guardarlos).
     */
    public static List<Grupo> aplicar(Iterable<Grupo> grupos, Memoria memoria, Catalogos catalogos, String fecha) {
        List<Grupo> cambiados = new ArrayList<>();
        for (Grupo g : grupos) {
            TerminoOficial t = terminoPara(g, memoria, catalogos);
            if (t == null) {
                continue;
            }
            g.asignar(t.getLista(), t.getCodigo(), t.getNombre(), ORIGEN, fecha);
            if (g.getMarcaCorregida() == null) {
                String m = memoria.marcaConocida(g.getMarca());
                g.setMarcaCorregida(m != null ? m : g.getMarca());
            }
            if (g.getModeloCorregido() == null) {
                String m = memoria.modeloConocido(g.getModelo());
                g.setModeloCorregido(m != null ? m : g.getModelo());
            }
            cambiados.add(g);
        }
        return cambiados;
    }

    /** Número de equipos de una lista de grupos. */
    public static long equipos(List<Grupo> grupos) {
        long n = 0;
        for (Grupo g : grupos) {
            n += g.getEquipos();
        }
        return n;
    }
}
