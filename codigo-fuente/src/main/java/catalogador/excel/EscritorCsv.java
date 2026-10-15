package catalogador.excel;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Escribe un CSV pensado para abrirse con Excel en español: UTF-8 con marca
 * BOM (para que se vean bien las tildes), separador punto y coma, saltos de
 * línea CRLF y comillas solo cuando hacen falta. Se escribe en flujo y no tiene
 * límite de filas.
 */
public final class EscritorCsv {

    public static final char SEPARADOR = ';';

    private EscritorCsv() {
    }

    public static void escribir(File destino, List<String> cabecera, EscritorXlsx.Filas filas) throws IOException {
        Path final_ = destino.toPath().toAbsolutePath();
        Path temporal = final_.resolveSibling("." + final_.getFileName() + ".tmp");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(temporal), StandardCharsets.UTF_8), 1 << 16)) {
            w.write('﻿');
            escribirFila(w, cabecera.toArray());
            for (int i = 0; i < filas.numero(); i++) {
                escribirFila(w, filas.fila(i));
            }
        } catch (IOException e) {
            Files.deleteIfExists(temporal);
            throw e;
        }
        try {
            catalogador.util.Ficheros.reemplazar(temporal, final_);
        } catch (IOException e) {
            Files.deleteIfExists(temporal);
            throw new IOException("No se pudo escribir «" + destino.getName()
                    + "». ¿Está abierto en Excel o en otro programa? Ciérralo y vuelve a intentarlo.", e);
        }
    }

    private static void escribirFila(Writer w, Object[] valores) throws IOException {
        for (int i = 0; i < valores.length; i++) {
            if (i > 0) {
                w.write(SEPARADOR);
            }
            Object v = valores[i];
            if (v instanceof EscritorXlsx.Celda) {
                v = ((EscritorXlsx.Celda) v).valor;
            }
            w.write(campo(v == null ? "" : v.toString()));
        }
        w.write("\r\n");
    }

    static String campo(String s) {
        boolean comillas = false;
        for (int i = 0; i < s.length() && !comillas; i++) {
            char c = s.charAt(i);
            comillas = c == SEPARADOR || c == '"' || c == '\n' || c == '\r';
        }
        return comillas ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
