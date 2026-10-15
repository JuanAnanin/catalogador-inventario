package catalogador.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Diario de cambios: fichero al que solo se añaden líneas al final y que se
 * fuerza a disco en cada anotación. Guardar un cambio cuesta lo mismo con 10
 * que con 100.000 aparatos revisados. Periódicamente se "compacta": se
 * escribe una instantánea completa y el diario se vacía.
 *
 * Si el programa se cierra a mitad de una anotación, la última línea puede
 * quedar incompleta; al leer se descarta.
 */
public final class Diario {

    private final Path fichero;
    private int lineas = -1;

    public Diario(Path fichero) {
        this.fichero = fichero;
    }

    public Path getFichero() {
        return fichero;
    }

    public void anadir(String linea) throws IOException {
        anadir(Arrays.asList(linea));
    }

    public void anadir(List<String> nuevas) throws IOException {
        if (nuevas.isEmpty()) {
            return;
        }
        int antes = tamano();
        StringBuilder b = new StringBuilder();
        if (terminaAMedias()) {
            b.append('\n'); // la línea incompleta queda aislada y se ignora al leer
        }
        for (String l : nuevas) {
            b.append(l).append('\n');
        }
        ByteBuffer datos = ByteBuffer.wrap(b.toString().getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(fichero.toAbsolutePath().getParent());
        try (FileChannel canal = FileChannel.open(fichero, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            while (datos.hasRemaining()) {
                canal.write(datos);
            }
            canal.force(false);
        }
        lineas = antes + nuevas.size();
    }

    /** true si el fichero existe y su último byte no es un salto de línea. */
    private boolean terminaAMedias() {
        try (FileChannel canal = FileChannel.open(fichero, StandardOpenOption.READ)) {
            long t = canal.size();
            if (t == 0) {
                return false;
            }
            ByteBuffer b = ByteBuffer.allocate(1);
            canal.read(b, t - 1);
            return b.get(0) != '\n';
        } catch (IOException e) {
            return false;
        }
    }

    /** Líneas completas del diario (la última se descarta si quedó a medias). */
    public List<String> leer() throws IOException {
        List<String> res = new ArrayList<>();
        if (!Files.exists(fichero)) {
            lineas = 0;
            return res;
        }
        byte[] bytes = Files.readAllBytes(fichero);
        int fin = bytes.length;
        while (fin > 0 && bytes[fin - 1] != '\n') {
            fin--; // línea final incompleta
        }
        String texto = new String(bytes, 0, fin, StandardCharsets.UTF_8);
        for (String l : texto.split("\n")) {
            if (!l.isEmpty()) {
                res.add(l);
            }
        }
        lineas = res.size();
        return res;
    }

    public int tamano() {
        if (lineas < 0) {
            try {
                leer();
            } catch (IOException e) {
                lineas = 0;
            }
        }
        return lineas;
    }

    public void vaciar() throws IOException {
        Files.deleteIfExists(fichero);
        lineas = 0;
    }
}
