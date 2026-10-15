package catalogador.util;

import java.awt.Desktop;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Operaciones con ficheros que se comportan distinto en Windows, macOS y Linux:
 * sustituir un fichero por otro aunque un antivirus o el indexador lo tengan
 * abierto un instante, abrir una carpeta o una web, localizar el Escritorio y
 * explicar con palabras claras los errores de disco.
 */
public final class Ficheros {

    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private Ficheros() {
    }

    /**
     * Sustituye {@code destino} por {@code temporal} (en la misma carpeta), de
     * forma atómica si el sistema lo permite. En Windows, si el destino está
     * abierto un momento por otro programa (antivirus, indexador, copia de
     * seguridad), se reintenta durante un par de segundos antes de rendirse.
     */
    public static void reemplazar(Path temporal, Path destino) throws IOException {
        IOException ultimo = null;
        for (int intento = 0; intento < 12; intento++) {
            try {
                try {
                    Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException | DirectoryNotEmptyException e) {
                ultimo = e;
            } catch (FileSystemException e) {
                if (!WINDOWS) {
                    throw e;
                }
                ultimo = e; // «está siendo utilizado por otro proceso»
            }
            try {
                Thread.sleep(50L + 30L * intento);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw ultimo;
    }

    /**
     * Mensaje claro para un error de disco: «No se puede escribir en …: puede
     * que el fichero esté abierto en Excel…». Para los demás errores, el suyo.
     */
    public static String mensaje(Throwable t) {
        if (t == null) {
            return "Error desconocido.";
        }
        if (t instanceof AccessDeniedException) {
            String f = nombre(((AccessDeniedException) t).getFile());
            return "No se puede escribir o abrir «" + f + "».\n\nPuede que esté abierto en Excel o en otro programa "
                    + "(ciérralo y vuelve a intentarlo) o que no tengas permiso en esa carpeta (prueba a elegir otra, "
                    + "por ejemplo el Escritorio o Documentos).";
        }
        if (t instanceof NoSuchFileException) {
            return "No se encuentra «" + ((NoSuchFileException) t).getFile() + "».\n\n¿Se ha movido, borrado o "
                    + "desconectado la unidad?";
        }
        if (t instanceof FileSystemException) {
            FileSystemException e = (FileSystemException) t;
            String razon = e.getReason() == null ? "" : e.getReason();
            String r = razon.toLowerCase(Locale.ROOT);
            if (r.contains("another process") || r.contains("otro proceso") || r.contains("being used")
                    || r.contains("utilizado")) {
                return "«" + nombre(e.getFile()) + "» está abierto en otro programa (¿Excel?). Ciérralo y vuelve a "
                        + "intentarlo.";
            }
            if (r.contains("no space") || r.contains("espacio") || r.contains("disk full")) {
                return "No queda espacio en el disco para guardar «" + nombre(e.getFile()) + "».";
            }
            return "No se pudo usar «" + e.getFile() + "»" + (razon.isEmpty() ? "." : ": " + razon);
        }
        if (t instanceof FileNotFoundException && t.getMessage() != null) {
            return "No se puede abrir el fichero: " + t.getMessage();
        }
        if (t instanceof IOException && t.getCause() instanceof FileSystemException && t.getMessage() != null
                && t.getMessage().contains("¿")) {
            return t.getMessage(); // ya trae una explicación
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    private static String nombre(String ruta) {
        if (ruta == null) {
            return "";
        }
        Path p = Paths.get(ruta).getFileName();
        String n = p == null ? ruta : p.toString();
        // los temporales se llaman «.Fichero.xlsx.tmp»
        if (n.startsWith(".") && n.endsWith(".tmp")) {
            n = n.substring(1, n.length() - 4);
        }
        return n;
    }

    /** Abre una carpeta o un fichero con el programa del sistema. Devuelve false si no se pudo. */
    public static boolean abrir(File f) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
                return true;
            }
        } catch (IOException | RuntimeException e) {
            // se prueba con el programa del sistema
        }
        return ejecutar(WINDOWS ? new String[]{"explorer.exe", f.getAbsolutePath()}
                : MAC ? new String[]{"open", f.getAbsolutePath()} : new String[]{"xdg-open", f.getAbsolutePath()});
    }

    /** Abre una dirección web en el navegador. Devuelve false si no se pudo. */
    public static boolean navegar(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (IOException | RuntimeException e) {
            // se prueba con el programa del sistema
        }
        return ejecutar(WINDOWS ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                : MAC ? new String[]{"open", url} : new String[]{"xdg-open", url});
    }

    private static boolean ejecutar(String[] orden) {
        try {
            new ProcessBuilder(orden).redirectErrorStream(true).start();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * La carpeta del Escritorio del usuario (en Windows puede estar dentro de
     * OneDrive; en Linux en español se llama «Escritorio»), o la carpeta personal.
     */
    public static Path escritorio() {
        String casa = System.getProperty("user.home");
        List<Path> candidatas = new ArrayList<>();
        String oneDrive = System.getenv("OneDrive");
        if (oneDrive != null && !oneDrive.isEmpty()) {
            candidatas.add(Paths.get(oneDrive, "Desktop"));
            candidatas.add(Paths.get(oneDrive, "Escritorio"));
        }
        candidatas.add(Paths.get(casa, "Desktop"));
        candidatas.add(Paths.get(casa, "Escritorio"));
        for (Path p : candidatas) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Paths.get(casa);
    }
}
