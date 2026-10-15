package catalogador.ia;

import catalogador.util.Diario;
import catalogador.util.Tsv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Propuestas de la IA de un proyecto, guardadas en {@code ia_propuestas.tsv}
 * dentro de su carpeta. Así no se vuelve a pagar (ni a esperar) por un grupo
 * ya consultado, aunque se cierre el programa. Cada propuesta nueva se añade
 * al final del fichero; al leer, la última de cada grupo es la que vale.
 */
public final class PropuestasGuardadas {

    public static final String FICHERO = "ia_propuestas.tsv";

    private final Diario diario;
    private final Map<String, PropuestaIA> porGrupo = new HashMap<>();

    public PropuestasGuardadas(Path carpetaProyecto) {
        this.diario = new Diario(carpetaProyecto.resolve(FICHERO));
    }

    /** Lee las propuestas guardadas (si el fichero no existe, no hay ninguna). */
    public synchronized void cargar() throws IOException {
        porGrupo.clear();
        for (String l : diario.leer()) {
            PropuestaIA p;
            try {
                p = PropuestaIA.desdeCampos(Tsv.campos(l));
            } catch (IllegalArgumentException e) {
                p = null;
            }
            if (p != null) {
                porGrupo.put(p.getClaveGrupo(), p);
            }
        }
    }

    public synchronized PropuestaIA get(String claveGrupo) {
        return porGrupo.get(claveGrupo);
    }

    public synchronized boolean tiene(String claveGrupo) {
        return porGrupo.containsKey(claveGrupo);
    }

    public synchronized int tamano() {
        return porGrupo.size();
    }

    public synchronized List<PropuestaIA> todas() {
        return new ArrayList<>(porGrupo.values());
    }

    /** Guarda en disco y en memoria. */
    public synchronized void guardar(List<PropuestaIA> nuevas) throws IOException {
        List<String> lineas = new ArrayList<>();
        for (PropuestaIA p : nuevas) {
            lineas.add(p.linea());
        }
        diario.anadir(lineas);
        for (PropuestaIA p : nuevas) {
            porGrupo.put(p.getClaveGrupo(), p);
        }
    }

    /** Olvida una propuesta (para volver a preguntar). */
    public synchronized void olvidar(String claveGrupo) throws IOException {
        if (porGrupo.remove(claveGrupo) != null) {
            reescribir();
        }
    }

    public synchronized void borrarTodas() throws IOException {
        porGrupo.clear();
        diario.vaciar();
    }

    private void reescribir() throws IOException {
        List<String> lineas = new ArrayList<>();
        for (PropuestaIA p : porGrupo.values()) {
            lineas.add(p.linea());
        }
        Tsv.escribirAtomico(diario.getFichero(), lineas);
        diario.leer(); // recuenta las líneas
    }
}
