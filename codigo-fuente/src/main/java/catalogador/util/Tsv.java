package catalogador.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Ficheros de texto separados por tabuladores, con escape de tabuladores y
 * saltos de línea, y escritura "atómica": primero se escribe un temporal y
 * después se renombra. Así, si el programa se cierra a mitad de guardar, el
 * fichero anterior sigue intacto.
 */
public final class Tsv {

    /** Algo que sabe escribir el contenido de un fichero, línea a línea. */
    public interface Contenido {
        void escribir(Writer w) throws IOException;
    }

    /** Recibe las líneas de un fichero una a una. */
    public interface Lector {
        void linea(String linea) throws IOException;
    }

    private Tsv() {
    }

    public static String escapar(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = c == '\\' ? "\\\\" : c == '\t' ? "\\t" : c == '\n' ? "\\n" : c == '\r' ? "\\r" : null;
            if (r != null && b == null) {
                b = new StringBuilder(s.length() + 8);
                b.append(s, 0, i);
            }
            if (b != null) {
                if (r != null) {
                    b.append(r);
                } else {
                    b.append(c);
                }
            }
        }
        return b == null ? s : b.toString();
    }

    public static String desescapar(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 't': b.append('\t'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    default: b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    public static String linea(String... campos) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < campos.length; i++) {
            if (i > 0) {
                b.append('\t');
            }
            b.append(escapar(campos[i]));
        }
        return b.toString();
    }

    public static String[] campos(String linea) {
        String[] partes = linea.split("\t", -1);
        for (int i = 0; i < partes.length; i++) {
            partes[i] = desescapar(partes[i]);
        }
        return partes;
    }

    public static String campo(String[] campos, int i) {
        return i < campos.length ? campos[i] : "";
    }

    /** Escribe las líneas en un temporal, lo fuerza a disco y lo renombra. */
    public static void escribirAtomico(Path destino, List<String> lineas) throws IOException {
        escribirAtomico(destino, w -> {
            for (String l : lineas) {
                w.write(l);
                w.write('\n');
            }
        });
    }

    /** Igual, pero el contenido se genera sobre la marcha (para ficheros grandes). */
    public static void escribirAtomico(Path destino, Contenido contenido) throws IOException {
        Files.createDirectories(destino.toAbsolutePath().getParent());
        Path temporal = destino.resolveSibling(destino.getFileName() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(Files.newBufferedWriter(temporal, StandardCharsets.UTF_8), 1 << 16)) {
            contenido.escribir(w);
        }
        try (FileChannel canal = FileChannel.open(temporal, StandardOpenOption.WRITE)) {
            canal.force(true);
        }
        Ficheros.reemplazar(temporal, destino);
    }

    public static List<String> leer(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    /** Recorre un fichero línea a línea sin cargarlo entero en memoria. */
    public static void recorrer(Path p, Lector lector) throws IOException {
        try (BufferedReader r = new BufferedReader(Files.newBufferedReader(p, StandardCharsets.UTF_8), 1 << 16)) {
            String l;
            while ((l = r.readLine()) != null) {
                lector.linea(l);
            }
        }
    }

    /** Primera línea de un fichero, o "" si no existe o está vacío. */
    public static String primeraLinea(Path p) {
        if (!Files.exists(p)) {
            return "";
        }
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String l = r.readLine();
            return l == null ? "" : l;
        } catch (IOException e) {
            return "";
        }
    }
}
