package catalogador.excel;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Escribe un libro .xlsx (varias hojas, cabecera con estilo, columnas con
 * ancho ajustado, filtro opcional) sin librerías. Las filas se escriben en
 * flujo, directamente al fichero comprimido, así que el tamaño de la hoja no
 * está limitado por la memoria (sí por el máximo de Excel: 1.048.576 filas).
 */
public final class EscritorXlsx {

    /** Máximo de filas de datos por hoja que admite Excel (sin contar la cabecera). */
    public static final int MAX_FILAS_HOJA = 1_048_575;

    /** Estilos disponibles para las celdas. */
    public static final int NORMAL = 0;
    public static final int CABECERA = 1;
    public static final int VERDE = 2;
    public static final int NARANJA = 3;
    public static final int GRIS = 4;
    public static final int NEGRITA = 5;
    public static final int CABECERA_AMARILLA = 6;

    /** Celda con estilo. Para celdas normales basta con poner un String o un Number. */
    public static final class Celda {
        final Object valor;
        final int estilo;

        public Celda(Object valor, int estilo) {
            this.valor = valor;
            this.estilo = estilo;
        }
    }

    /** Origen de las filas de una hoja, con acceso por posición (para no tenerlas todas en memoria). */
    public interface Filas {
        int numero();

        Object[] fila(int i);
    }

    public static final class HojaSalida {
        final String nombre;
        final List<String> cabecera;
        final List<Object[]> lista = new ArrayList<>();
        Filas filas;
        boolean conFiltro = true;
        boolean fijarCabecera = true;
        int estiloCabecera = CABECERA;
        double[] anchos;

        public HojaSalida(String nombre, List<String> cabecera) {
            this.nombre = nombreValido(nombre);
            this.cabecera = new ArrayList<>(cabecera);
        }

        public HojaSalida(String nombre, List<String> cabecera, Filas filas) {
            this(nombre, cabecera);
            this.filas = filas;
        }

        public void anadir(Object... valores) {
            lista.add(valores);
        }

        public HojaSalida sinFiltro() {
            conFiltro = false;
            return this;
        }

        public HojaSalida sinFijarCabecera() {
            fijarCabecera = false;
            return this;
        }

        public HojaSalida estiloCabecera(int estilo) {
            estiloCabecera = estilo;
            return this;
        }

        public HojaSalida anchos(double... anchos) {
            this.anchos = anchos;
            return this;
        }

        Filas origen() {
            if (filas != null) {
                return filas;
            }
            return new Filas() {
                @Override
                public int numero() {
                    return lista.size();
                }

                @Override
                public Object[] fila(int i) {
                    return lista.get(i);
                }
            };
        }
    }

    private EscritorXlsx() {
    }

    public static void escribir(File destino, List<HojaSalida> hojas) throws IOException {
        Path final_ = destino.toPath().toAbsolutePath();
        Path temporal = final_.resolveSibling("." + final_.getFileName() + ".tmp");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(temporal), 1 << 16);
             ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            poner(zip, "[Content_Types].xml", tiposContenido(hojas.size()));
            poner(zip, "_rels/.rels", RELS_RAIZ);
            poner(zip, "docProps/app.xml", APP);
            poner(zip, "xl/workbook.xml", libro(hojas));
            poner(zip, "xl/_rels/workbook.xml.rels", relacionesLibro(hojas.size()));
            poner(zip, "xl/styles.xml", ESTILOS);
            for (int i = 0; i < hojas.size(); i++) {
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + (i + 1) + ".xml"));
                Writer w = new BufferedWriter(new OutputStreamWriter(new SinCerrar(zip), StandardCharsets.UTF_8), 1 << 16);
                hoja(w, hojas.get(i));
                w.flush();
                zip.closeEntry();
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

    /** Evita que cerrar el escritor de una hoja cierre el ZIP entero. */
    private static final class SinCerrar extends FilterOutputStream {
        SinCerrar(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static void poner(ZipOutputStream zip, String ruta, String contenido) throws IOException {
        zip.putNextEntry(new ZipEntry(ruta));
        zip.write(contenido.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ------------------------------------------------------------------

    private static final String CABECERA_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String RELS_RAIZ = CABECERA_XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"" + NS_REL + "/extended-properties\" Target=\"docProps/app.xml\"/>"
            + "</Relationships>";

    private static final String APP = CABECERA_XML
            + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
            + "<Application>Catalogador</Application></Properties>";

    private static final String ESTILOS = CABECERA_XML
            + "<styleSheet xmlns=\"" + NS_MAIN + "\">"
            + "<fonts count=\"3\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "</fonts>"
            + "<fills count=\"7\">"
            + "<fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F3A5F\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFC6EFCE\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFE699\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEDEDED\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFFF00\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"7\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"5\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"6\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";

    private static String tiposContenido(int numHojas) {
        StringBuilder b = new StringBuilder(CABECERA_XML);
        b.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        b.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        b.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        b.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        b.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        b.append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        for (int i = 1; i <= numHojas; i++) {
            b.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        b.append("</Types>");
        return b.toString();
    }

    private static String libro(List<HojaSalida> hojas) {
        StringBuilder b = new StringBuilder(CABECERA_XML);
        b.append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">");
        b.append("<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
        for (int i = 0; i < hojas.size(); i++) {
            b.append("<sheet name=\"").append(xml(hojas.get(i).nombre)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        b.append("</sheets>");
        StringBuilder nombres = new StringBuilder();
        for (int i = 0; i < hojas.size(); i++) {
            HojaSalida h = hojas.get(i);
            if (h.conFiltro && !h.cabecera.isEmpty()) {
                nombres.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i)
                        .append("\" hidden=\"1\">'").append(xml(h.nombre.replace("'", "''"))).append("'!$A$1:$")
                        .append(Hoja.letra(h.cabecera.size() - 1)).append('$').append(h.origen().numero() + 1)
                        .append("</definedName>");
            }
        }
        if (nombres.length() > 0) {
            b.append("<definedNames>").append(nombres).append("</definedNames>");
        }
        b.append("</workbook>");
        return b.toString();
    }

    private static String relacionesLibro(int numHojas) {
        StringBuilder b = new StringBuilder(CABECERA_XML);
        b.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= numHojas; i++) {
            b.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(NS_REL)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        b.append("<Relationship Id=\"rId").append(numHojas + 1).append("\" Type=\"").append(NS_REL)
                .append("/styles\" Target=\"styles.xml\"/>");
        b.append("</Relationships>");
        return b.toString();
    }

    private static void hoja(Writer w, HojaSalida h) throws IOException {
        Filas filas = h.origen();
        int n = filas.numero();
        if (n > MAX_FILAS_HOJA) {
            throw new IOException("La hoja «" + h.nombre + "» supera el máximo de filas de Excel.");
        }
        int columnas = h.cabecera.size();
        for (int i = 0; i < Math.min(n, 500); i++) {
            columnas = Math.max(columnas, filas.fila(i).length);
        }
        w.write(CABECERA_XML);
        w.write("<worksheet xmlns=\"" + NS_MAIN + "\" xmlns:r=\"" + NS_REL + "\">");
        if (h.fijarCabecera && !h.cabecera.isEmpty()) {
            w.write("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" "
                    + "activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        }
        w.write("<sheetFormatPr defaultRowHeight=\"15\"/>");
        if (columnas > 0) {
            double[] anchos = h.anchos != null ? Arrays.copyOf(h.anchos, Math.max(columnas, h.anchos.length))
                    : anchos(h, filas, columnas);
            w.write("<cols>");
            for (int c = 0; c < columnas; c++) {
                double a = anchos[c] > 0 ? anchos[c] : 10;
                w.write("<col min=\"" + (c + 1) + "\" max=\"" + (c + 1) + "\" width=\"" + a + "\" customWidth=\"1\"/>");
            }
            w.write("</cols>");
        }
        w.write("<sheetData>");
        StringBuilder b = new StringBuilder(512);
        int numFila = 1;
        if (!h.cabecera.isEmpty()) {
            b.append("<row r=\"1\">");
            for (int c = 0; c < h.cabecera.size(); c++) {
                celda(b, c, 1, h.cabecera.get(c), h.estiloCabecera);
            }
            b.append("</row>");
            w.write(b.toString());
            numFila = 2;
        }
        for (int i = 0; i < n; i++) {
            Object[] f = filas.fila(i);
            b.setLength(0);
            b.append("<row r=\"").append(numFila).append("\">");
            for (int c = 0; c < f.length; c++) {
                Object v = f[c];
                int estilo = NORMAL;
                if (v instanceof Celda) {
                    estilo = ((Celda) v).estilo;
                    v = ((Celda) v).valor;
                }
                celda(b, c, numFila, v, estilo);
            }
            b.append("</row>");
            w.write(b.toString());
            numFila++;
        }
        w.write("</sheetData>");
        if (h.conFiltro && !h.cabecera.isEmpty()) {
            w.write("<autoFilter ref=\"A1:" + Hoja.letra(h.cabecera.size() - 1) + (n + 1) + "\"/>");
        }
        w.write("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");
        w.write("</worksheet>");
    }

    private static void celda(StringBuilder b, int col, int fila, Object valor, int estilo) {
        if (valor == null && estilo == NORMAL) {
            return;
        }
        String ref = Hoja.letra(col) + fila;
        String s = estilo == NORMAL ? "" : " s=\"" + estilo + "\"";
        if (valor instanceof Number) {
            double d = ((Number) valor).doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                b.append("<c r=\"").append(ref).append('"').append(s).append("><v>")
                        .append(valor instanceof Double || valor instanceof Float ? Double.toString(d) : valor.toString())
                        .append("</v></c>");
                return;
            }
        }
        String texto = valor == null ? "" : valor.toString();
        if (texto.isEmpty()) {
            if (estilo != NORMAL) {
                b.append("<c r=\"").append(ref).append('"').append(s).append("/>");
            }
            return;
        }
        b.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(s).append("><is><t xml:space=\"preserve\">");
        xml(b, texto);
        b.append("</t></is></c>");
    }

    private static double[] anchos(HojaSalida h, Filas filas, int columnas) {
        double[] anchos = new double[columnas];
        Arrays.fill(anchos, 8);
        for (int c = 0; c < h.cabecera.size(); c++) {
            anchos[c] = Math.max(anchos[c], h.cabecera.get(c).length() + 4);
        }
        int muestra = Math.min(filas.numero(), 500);
        for (int i = 0; i < muestra; i++) {
            Object[] f = filas.fila(i);
            for (int c = 0; c < f.length; c++) {
                Object v = f[c] instanceof Celda ? ((Celda) f[c]).valor : f[c];
                if (v != null) {
                    anchos[c] = Math.max(anchos[c], v.toString().length() + 2);
                }
            }
        }
        for (int c = 0; c < columnas; c++) {
            anchos[c] = Math.min(anchos[c], 60);
        }
        return anchos;
    }

    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        xml(b, s);
        return b.toString();
    }

    /** Escapa texto para XML y elimina caracteres de control no permitidos. */
    static void xml(StringBuilder b, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if ((c >= 0x20 || c == '\t' || c == '\n' || c == '\r') && c != 0xFFFE && c != 0xFFFF) {
                        b.append(c);
                    }
            }
        }
    }

    /** Excel no admite nombres de hoja de más de 31 caracteres ni con []:*?/\ */
    public static String nombreValido(String nombre) {
        String n = nombre == null ? "Hoja" : nombre.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (n.isEmpty()) {
            n = "Hoja";
        }
        return n.length() > 31 ? n.substring(0, 31) : n;
    }
}
