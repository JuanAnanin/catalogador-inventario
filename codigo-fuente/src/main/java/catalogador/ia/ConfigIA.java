package catalogador.ia;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/**
 * Modo de trabajo (manual o con IA), servicio elegido, y la clave y el modelo
 * de cada servicio. Se guarda en {@code ia.properties}, dentro de la carpeta
 * de datos del usuario; en Linux y macOS el fichero solo lo puede leer su dueño.
 * Si no se ha escrito una clave, se usa la de la variable de entorno del
 * servicio (por ejemplo {@code GROQ_API_KEY}), si existe.
 */
public final class ConfigIA {

    /** Cómo se trabaja. */
    public enum Modo {
        MANUAL, IA
    }

    /** Grupos que se envían en cada consulta. */
    public static final int LOTE_POR_DEFECTO = 8;
    public static final int UMBRAL_BLOQUE_POR_DEFECTO = 90;

    private final Path fichero;
    private final Properties p = new Properties();

    public ConfigIA(Path carpetaDatos) {
        this.fichero = carpetaDatos.resolve("ia.properties");
        recargar();
    }

    /** Vuelve a leer el fichero (descarta lo cambiado en memoria y no guardado). */
    public synchronized void recargar() {
        p.clear();
        if (Files.exists(fichero)) {
            try (InputStream in = Files.newInputStream(fichero)) {
                p.load(in);
            } catch (IOException e) {
                // se empieza con la configuración por defecto
            }
        }
    }

    public synchronized void guardar() throws IOException {
        Files.createDirectories(fichero.getParent());
        Path tmp = fichero.resolveSibling("ia.properties.tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "Catalogador: modo de trabajo y servicios de IA (contiene claves: no lo compartas)");
        }
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: la carpeta del usuario ya es privada
        }
        catalogador.util.Ficheros.reemplazar(tmp, fichero);
    }

    public Path getFichero() {
        return fichero;
    }

    // ------------------------------------------------------------------ modo

    public synchronized Modo getModo() {
        return "IA".equals(p.getProperty("modo")) ? Modo.IA : Modo.MANUAL;
    }

    public synchronized void setModo(Modo m) {
        p.setProperty("modo", m.name());
    }

    /** Se pregunta el modo al abrir el programa (por defecto, sí). */
    public synchronized boolean isPreguntarAlEmpezar() {
        return !"false".equals(p.getProperty("preguntarAlEmpezar"));
    }

    public synchronized void setPreguntarAlEmpezar(boolean si) {
        p.setProperty("preguntarAlEmpezar", String.valueOf(si));
    }

    /** true si nunca se ha elegido modo (primera vez). */
    public synchronized boolean esPrimeraVez() {
        return p.getProperty("modo") == null;
    }

    // ------------------------------------------------------------------ servicio

    public synchronized ProveedorIA getProveedor() {
        ProveedorIA pr = ProveedorIA.porId(p.getProperty("proveedor", ""));
        return pr != null ? pr : ProveedorIA.porId("groq");
    }

    public synchronized void setProveedor(ProveedorIA pr) {
        p.setProperty("proveedor", pr.getId());
    }

    /** La clave escrita para el servicio (vacía si no hay). */
    public synchronized String getClaveGuardada(ProveedorIA pr) {
        return p.getProperty(pr.getGrupoClave() + ".clave", "");
    }

    /** La clave que se usará: la guardada o, si no hay, la de la variable de entorno. */
    public synchronized String getClave(ProveedorIA pr) {
        String c = getClaveGuardada(pr).trim();
        if (c.isEmpty() && pr.getVariableEntorno() != null) {
            String v = System.getenv(pr.getVariableEntorno());
            if (v != null) {
                c = v.trim();
            }
        }
        return c;
    }

    public synchronized boolean claveDeEntorno(ProveedorIA pr) {
        return getClaveGuardada(pr).trim().isEmpty() && !getClave(pr).isEmpty();
    }

    public synchronized void setClave(ProveedorIA pr, String clave) {
        String c = clave == null ? "" : clave.trim();
        if (c.isEmpty()) {
            p.remove(pr.getGrupoClave() + ".clave");
        } else {
            p.setProperty(pr.getGrupoClave() + ".clave", c);
        }
    }

    public synchronized String getModelo(ProveedorIA pr) {
        String m = p.getProperty(pr.getId() + ".modelo", "").trim();
        return m.isEmpty() ? pr.getModeloPorDefecto() : m;
    }

    public synchronized void setModelo(ProveedorIA pr, String modelo) {
        p.setProperty(pr.getId() + ".modelo", modelo == null ? "" : modelo.trim());
    }

    public synchronized String getUrl(ProveedorIA pr) {
        String u = pr.urlEditable() ? p.getProperty(pr.getId() + ".url", "").trim() : "";
        return u.isEmpty() ? pr.getUrlBase() : u;
    }

    public synchronized void setUrl(ProveedorIA pr, String url) {
        if (pr.urlEditable()) {
            p.setProperty(pr.getId() + ".url", url == null ? "" : url.trim());
        }
    }

    /** Listo para usarse: tiene dirección, modelo y (si la necesita) clave. */
    public synchronized boolean estaListo(ProveedorIA pr) {
        String u = getUrl(pr);
        return u.startsWith("http") && u.length() > 8 && !getModelo(pr).isEmpty()
                && (!pr.necesitaClave() || !getClave(pr).isEmpty());
    }

    // ------------------------------------------------------------------ ajustes

    public synchronized int getLote() {
        return entero("lote", LOTE_POR_DEFECTO, 1, 25);
    }

    /**
     * Grupos por consulta para ese servicio: el escrito en la configuración o, si
     * no hay ninguno, el recomendado (menos en los locales y en Groq gratis).
     */
    public synchronized int getLote(ProveedorIA pr) {
        return p.getProperty("lote") == null ? pr.getLoteRecomendado() : getLote();
    }

    public synchronized void setLote(int n) {
        p.setProperty("lote", String.valueOf(n));
    }

    public synchronized int getUmbralBloque() {
        return entero("umbralBloque", UMBRAL_BLOQUE_POR_DEFECTO, 50, 100);
    }

    public synchronized void setUmbralBloque(int n) {
        p.setProperty("umbralBloque", String.valueOf(n));
    }

    private int entero(String clave, int porDefecto, int min, int max) {
        try {
            int v = Integer.parseInt(p.getProperty(clave, String.valueOf(porDefecto)).trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    /** Texto corto para la cabecera: «Groq · llama-3.3-70b-versatile». */
    public synchronized String resumen() {
        ProveedorIA pr = getProveedor();
        return pr.getNombreCorto() + " · " + getModelo(pr);
    }

    /** La clave con casi todo oculto, para enseñarla: «gsk_…9f2c». */
    public static String ocultar(String clave) {
        if (clave == null || clave.length() < 9) {
            return clave == null || clave.isEmpty() ? "" : "••••";
        }
        return clave.substring(0, 4) + "…" + clave.substring(clave.length() - 4);
    }
}
