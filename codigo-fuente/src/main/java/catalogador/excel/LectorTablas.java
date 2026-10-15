package catalogador.excel;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lee ficheros Excel (.xlsx) y CSV sin librerías externas.
 * <p>
 * La lectura es <b>en flujo</b>: las filas se entregan una a una a un
 * {@link Receptor}, sin cargar la hoja entera en memoria, de modo que se
 * pueden procesar ficheros de cientos de miles de filas. El receptor puede
 * pedir que se pare (por ejemplo, para una vista previa de las primeras filas).
 */
public final class LectorTablas {

    /** Recibe cada fila no vacía: su número (0 = primera fila del fichero) y sus celdas. */
    public interface Receptor {
        /** @return false para dejar de leer */
        boolean fila(int numFila, String[] celdas) throws IOException;
    }

    /** Informa del avance: filas (o bytes) procesados y total estimado (−1 si se desconoce). */
    public interface Progreso {
        void avance(long hecho, long total);
    }

    private static final Progreso SIN_PROGRESO = (h, t) -> { };

    private LectorTablas() {
    }

    private static boolean esCsv(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".csv") || n.endsWith(".txt") || n.endsWith(".tsv");
    }

    private static void comprobarFormato(File f) throws IOException {
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".xls")) {
            throw new IOException("El formato .xls (Excel 97-2003) no está soportado.\n"
                    + "Ábrelo en Excel y usa «Guardar como» → «Libro de Excel (.xlsx)».");
        }
    }

    /** Nombres de las hojas del fichero (un CSV tiene una sola). */
    public static List<String> hojas(File f) throws IOException {
        comprobarFormato(f);
        if (esCsv(f)) {
            return Collections.singletonList(f.getName());
        }
        try (ZipFile zip = abrirZip(f)) {
            List<String> nombres = new ArrayList<>();
            for (String[] h : leerLibro(zip)) {
                nombres.add(h[0]);
            }
            return nombres;
        } catch (XMLStreamException e) {
            throw new IOException("El fichero Excel parece dañado: " + e.getMessage(), e);
        }
    }

    /** Recorre las filas de una hoja (índice 0 = primera hoja). */
    public static void recorrer(File f, int hoja, Receptor receptor, Progreso progreso) throws IOException {
        comprobarFormato(f);
        Progreso p = progreso == null ? SIN_PROGRESO : progreso;
        if (esCsv(f)) {
            recorrerCsv(f, receptor, p);
        } else {
            recorrerXlsx(f, hoja, receptor, p);
        }
    }

    /** Lee una hoja completa en memoria (para ficheros pequeños: catálogos, vistas previas). */
    public static Hoja leerHoja(File f, int hoja, int maxFilas) throws IOException {
        List<List<String>> filas = new ArrayList<>();
        String nombre = hojas(f).get(hoja);
        recorrer(f, hoja, (num, celdas) -> {
            while (filas.size() < num) {
                filas.add(new ArrayList<>());
            }
            List<String> l = new ArrayList<>(celdas.length);
            Collections.addAll(l, celdas);
            filas.add(l);
            return maxFilas <= 0 || filas.size() < maxFilas;
        }, null);
        return new Hoja(nombre, filas);
    }

    /** Lee todas las hojas completas en memoria (solo para ficheros pequeños). */
    public static List<Hoja> leer(File f) throws IOException {
        List<Hoja> res = new ArrayList<>();
        int n = hojas(f).size();
        for (int i = 0; i < n; i++) {
            res.add(leerHoja(f, i, 0));
        }
        return res;
    }

    // =====================================================================
    // XLSX
    // =====================================================================

    private static ZipFile abrirZip(File f) throws IOException {
        try {
            return new ZipFile(f);
        } catch (java.util.zip.ZipException e) {
            throw new IOException("No parece un fichero Excel .xlsx válido (" + f.getName() + ").", e);
        }
    }

    private static void recorrerXlsx(File f, int hoja, Receptor receptor, Progreso p) throws IOException {
        try (ZipFile zip = abrirZip(f)) {
            Map<String, String[]> rels = leerRelaciones(zip, "xl/_rels/workbook.xml.rels");
            String rutaCompartidas = "xl/sharedStrings.xml";
            for (String[] r : rels.values()) {
                if (r[1].endsWith("/sharedStrings")) {
                    rutaCompartidas = r[0];
                }
            }
            List<String[]> hojasLibro = leerLibro(zip);
            if (hoja < 0 || hoja >= hojasLibro.size()) {
                throw new IOException("El fichero no tiene la hoja " + (hoja + 1) + ".");
            }
            String[] rel = rels.get(hojasLibro.get(hoja)[1]);
            String ruta = rel != null ? rel[0] : "xl/worksheets/sheet" + (hoja + 1) + ".xml";
            ZipEntry entrada = zip.getEntry(ruta);
            if (entrada == null) {
                throw new IOException("No se encuentra la hoja «" + hojasLibro.get(hoja)[0] + "» dentro del fichero.");
            }
            List<String> compartidas = leerCompartidas(zip, rutaCompartidas);
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entrada), 1 << 16)) {
                leerHoja(in, compartidas, receptor, p);
            }
        } catch (XMLStreamException e) {
            throw new IOException("El fichero Excel parece dañado: " + e.getMessage(), e);
        }
    }

    private static XMLStreamReader lector(InputStream in) throws XMLStreamException {
        XMLInputFactory fabrica = XMLInputFactory.newInstance();
        fabrica.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        fabrica.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        fabrica.setProperty(XMLInputFactory.IS_COALESCING, true);
        return fabrica.createXMLStreamReader(in);
    }

    private static String atributo(XMLStreamReader r, String nombreLocal) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(nombreLocal)) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    /** id → {ruta dentro del zip, tipo} */
    private static Map<String, String[]> leerRelaciones(ZipFile zip, String ruta)
            throws IOException, XMLStreamException {
        Map<String, String[]> mapa = new HashMap<>();
        ZipEntry e = zip.getEntry(ruta);
        if (e == null) {
            return mapa;
        }
        try (InputStream in = zip.getInputStream(e)) {
            XMLStreamReader r = lector(in);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")) {
                    String id = atributo(r, "Id");
                    String destino = atributo(r, "Target");
                    String tipo = atributo(r, "Type");
                    if (id != null && destino != null) {
                        String completo = destino.startsWith("/") ? destino.substring(1) : "xl/" + destino;
                        completo = completo.replace("xl/../", "");
                        mapa.put(id, new String[]{completo, tipo == null ? "" : tipo});
                    }
                }
            }
            r.close();
        }
        return mapa;
    }

    /** Lista de {nombre de hoja, id de relación} en el orden del libro. */
    private static List<String[]> leerLibro(ZipFile zip) throws IOException, XMLStreamException {
        List<String[]> hojas = new ArrayList<>();
        ZipEntry e = zip.getEntry("xl/workbook.xml");
        if (e == null) {
            throw new IOException("No se encuentra xl/workbook.xml: no es un .xlsx válido.");
        }
        try (InputStream in = zip.getInputStream(e)) {
            XMLStreamReader r = lector(in);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                    String nombre = atributo(r, "name");
                    String id = atributo(r, "id");
                    hojas.add(new String[]{nombre == null ? "Hoja" : nombre, id == null ? "" : id});
                }
            }
            r.close();
        }
        return hojas;
    }

    private static List<String> leerCompartidas(ZipFile zip, String ruta) throws IOException, XMLStreamException {
        List<String> lista = new ArrayList<>();
        ZipEntry e = zip.getEntry(ruta);
        if (e == null) {
            return lista;
        }
        Map<String, String> unicas = new HashMap<>();
        try (InputStream in = new BufferedInputStream(zip.getInputStream(e), 1 << 16)) {
            XMLStreamReader r = lector(in);
            StringBuilder actual = null;
            boolean enTexto = false;
            int enFonetica = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("si")) {
                        actual = new StringBuilder();
                    } else if (n.equals("rPh")) {
                        enFonetica++;
                    } else if (n.equals("t") && enFonetica == 0) {
                        enTexto = true;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA
                        || ev == XMLStreamConstants.SPACE) {
                    if (enTexto && actual != null) {
                        actual.append(r.getText());
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("t")) {
                        enTexto = false;
                    } else if (n.equals("rPh")) {
                        enFonetica--;
                    } else if (n.equals("si")) {
                        String s = actual == null ? "" : actual.toString();
                        String previa = unicas.putIfAbsent(s, s);
                        lista.add(previa != null ? previa : s);
                        actual = null;
                    }
                }
            }
            r.close();
        }
        return lista;
    }

    private static void leerHoja(InputStream in, List<String> compartidas, Receptor receptor, Progreso p)
            throws IOException, XMLStreamException {
        XMLStreamReader r = lector(in);
        List<String> fila = null;
        int numFila = 0;
        long total = -1;
        String tipo = null;
        int columna = 0;
        StringBuilder valor = new StringBuilder();
        boolean enCelda = false;
        boolean enValor = false;
        boolean enInline = false;
        boolean enTexto = false;
        int enFonetica = 0;
        long leidas = 0;
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    switch (n) {
                        case "dimension": {
                            String ref = atributo(r, "ref");
                            if (ref != null && ref.contains(":")) {
                                total = filaDe(ref.substring(ref.indexOf(':') + 1));
                            }
                            break;
                        }
                        case "row": {
                            String rr = atributo(r, "r");
                            numFila = rr != null ? Integer.parseInt(rr.trim()) : numFila + 1;
                            fila = new ArrayList<>();
                            break;
                        }
                        case "c": {
                            String ref = atributo(r, "r");
                            tipo = atributo(r, "t");
                            columna = ref != null ? columnaDe(ref) : (fila == null ? 0 : fila.size());
                            valor.setLength(0);
                            enCelda = true;
                            break;
                        }
                        case "v": enValor = true; break;
                        case "is": enInline = true; break;
                        case "rPh": enFonetica++; break;
                        case "t":
                            if (enInline && enFonetica == 0) {
                                enTexto = true;
                            }
                            break;
                        default: break;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA
                        || ev == XMLStreamConstants.SPACE) {
                    if (enValor || enTexto) {
                        valor.append(r.getText());
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    switch (n) {
                        case "v": enValor = false; break;
                        case "t": enTexto = false; break;
                        case "is": enInline = false; break;
                        case "rPh": enFonetica--; break;
                        case "c":
                            if (fila != null && enCelda) {
                                String texto = convertir(tipo, valor.toString(), compartidas);
                                while (fila.size() < columna) {
                                    fila.add("");
                                }
                                if (columna < fila.size()) {
                                    fila.set(columna, texto);
                                } else {
                                    fila.add(texto);
                                }
                            }
                            enCelda = false;
                            break;
                        case "row":
                            if (fila != null) {
                                while (!fila.isEmpty() && fila.get(fila.size() - 1).isEmpty()) {
                                    fila.remove(fila.size() - 1);
                                }
                                if (!fila.isEmpty()) {
                                    if (!receptor.fila(numFila - 1, fila.toArray(new String[0]))) {
                                        return;
                                    }
                                }
                                if (++leidas % 5000 == 0) {
                                    p.avance(numFila, total);
                                }
                            }
                            fila = null;
                            break;
                        default: break;
                    }
                }
            }
            p.avance(numFila, numFila);
        } finally {
            r.close();
        }
    }

    private static String convertir(String tipo, String v, List<String> compartidas) {
        if (tipo == null || tipo.equals("n")) {
            return formatearNumero(v);
        }
        switch (tipo) {
            case "s":
                try {
                    int i = Integer.parseInt(v.trim());
                    return i >= 0 && i < compartidas.size() ? compartidas.get(i) : "";
                } catch (NumberFormatException e) {
                    return "";
                }
            case "b":
                return "1".equals(v.trim()) ? "VERDADERO" : "FALSO";
            default:
                return v; // inlineStr, str, e
        }
    }

    private static String formatearNumero(String v) {
        String t = v.trim();
        if (t.isEmpty()) {
            return "";
        }
        // Atajo para enteros (lo más habitual: identificadores, códigos)
        boolean entero = t.length() < 16;
        for (int i = 0; i < t.length() && entero; i++) {
            char c = t.charAt(i);
            entero = (c >= '0' && c <= '9') || (i == 0 && c == '-' && t.length() > 1);
        }
        if (entero) {
            return t;
        }
        try {
            BigDecimal bd = new BigDecimal(t).round(new MathContext(15));
            return bd.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** "C12" → 2 */
    static int columnaDe(String referencia) {
        int col = 0;
        for (int i = 0; i < referencia.length(); i++) {
            char c = referencia.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                col = col * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                col = col * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return Math.max(0, col - 1);
    }

    /** "I600002" → 600002 */
    static long filaDe(String referencia) {
        long n = 0;
        for (int i = 0; i < referencia.length(); i++) {
            char c = referencia.charAt(i);
            if (c >= '0' && c <= '9') {
                n = n * 10 + (c - '0');
            }
        }
        return n;
    }

    // =====================================================================
    // CSV
    // =====================================================================

    private static void recorrerCsv(File f, Receptor receptor, Progreso p) throws IOException {
        Charset cs = esUtf8(f) ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
        long tamano = Math.max(1, f.length());
        char separador;
        try (Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), cs)) {
            char[] buf = new char[8192];
            int n = r.read(buf);
            separador = detectarSeparador(n <= 0 ? "" : new String(buf, 0, n));
        }
        try (InputStream bruto = new BufferedInputStream(Files.newInputStream(f.toPath()), 1 << 16)) {
            ContadorBytes contador = new ContadorBytes(bruto);
            Reader r = new InputStreamReader(contador, cs);
            List<String> fila = new ArrayList<>();
            StringBuilder celda = new StringBuilder();
            boolean comillas = false;
            boolean inicio = true;
            int numFila = 0;
            int c;
            char[] buf = new char[1 << 15];
            int len;
            int prev = -1;
            while ((len = r.read(buf)) > 0) {
                for (int i = 0; i < len; i++) {
                    c = buf[i];
                    if (inicio) {
                        inicio = false;
                        if (c == '﻿') {
                            continue;
                        }
                    }
                    if (comillas) {
                        if (c == '"') {
                            comillas = false;
                            prev = '"';
                            continue;
                        }
                        celda.append((char) c);
                    } else if (c == '"') {
                        if (prev == '"') {
                            celda.append('"'); // comilla escapada ""
                        }
                        comillas = true;
                    } else if (c == separador) {
                        fila.add(celda.toString());
                        celda.setLength(0);
                    } else if (c == '\n' || c == '\r') {
                        if (!(c == '\n' && prev == '\r')) {
                            fila.add(celda.toString());
                            celda.setLength(0);
                            if (!entregar(fila, numFila, receptor)) {
                                return;
                            }
                            numFila++;
                            fila = new ArrayList<>();
                            if (numFila % 5000 == 0) {
                                p.avance(contador.leidos, tamano);
                            }
                        }
                    } else {
                        celda.append((char) c);
                    }
                    prev = c;
                }
            }
            if (celda.length() > 0 || !fila.isEmpty()) {
                fila.add(celda.toString());
                entregar(fila, numFila, receptor);
            }
            p.avance(tamano, tamano);
        }
    }

    private static boolean entregar(List<String> fila, int numFila, Receptor receptor) throws IOException {
        while (!fila.isEmpty() && fila.get(fila.size() - 1).isEmpty()) {
            fila.remove(fila.size() - 1);
        }
        return fila.isEmpty() || receptor.fila(numFila, fila.toArray(new String[0]));
    }

    /** Comprueba si todo el fichero es UTF-8 válido (si no, se leerá como Windows-1252). */
    private static boolean esUtf8(File f) throws IOException {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f.toPath()), 1 << 16)) {
            byte[] b = new byte[1 << 16];
            ByteBuffer entrada = ByteBuffer.allocate((1 << 16) + 8);
            CharBuffer salida = CharBuffer.allocate(1 << 17);
            int n;
            while ((n = in.read(b)) > 0) {
                entrada.put(b, 0, n);
                entrada.flip();
                CoderResult res = dec.decode(entrada, salida, false);
                if (res.isError()) {
                    return false;
                }
                entrada.compact();
                salida.clear();
            }
            entrada.flip();
            return !dec.decode(entrada, salida, true).isError();
        }
    }

    private static char detectarSeparador(String texto) {
        int fin = texto.indexOf('\n');
        String primera = fin < 0 ? texto : texto.substring(0, fin);
        char[] candidatos = {';', ',', '\t', '|'};
        char mejor = ',';
        int max = 0;
        for (char c : candidatos) {
            int n = 0;
            boolean comillas = false;
            for (char x : primera.toCharArray()) {
                if (x == '"') {
                    comillas = !comillas;
                } else if (x == c && !comillas) {
                    n++;
                }
            }
            if (n > max) {
                max = n;
                mejor = c;
            }
        }
        return mejor;
    }

    /** Cuenta los bytes leídos para informar del progreso de un CSV. */
    private static final class ContadorBytes extends java.io.FilterInputStream {
        long leidos;

        ContadorBytes(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) {
                leidos++;
            }
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                leidos += n;
            }
            return n;
        }
    }
}
