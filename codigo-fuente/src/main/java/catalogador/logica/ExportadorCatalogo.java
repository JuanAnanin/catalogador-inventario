package catalogador.logica;

import catalogador.excel.EscritorXlsx;
import catalogador.excel.EscritorXlsx.HojaSalida;
import catalogador.modelo.TerminoOficial;
import catalogador.modelo.TipoLista;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Exporta los catálogos con el mismo formato que el Excel que se importó
 * («Datos catalogo que paso al programa»): una hoja para GMDN y otra para
 * Mantenimiento, con las mismas cabeceras, columnas y anchos. Así el
 * catálogo de mantenimiento, con las entradas nuevas creadas en el programa,
 * puede volver a usarse donde se usaba el original.
 */
public final class ExportadorCatalogo {

    private static final double[] ANCHOS_GMDN = {14, 42.8, 87.1};
    private static final double[] ANCHOS_MANTENIMIENTO = {42.4, 21.4, 24.8, 14, 28, 17.8, 42.9, 21.6, 46.9, 28.6, 43.9};
    private static final Pattern ENTERO = Pattern.compile("[1-9]\\d{0,14}");

    private ExportadorCatalogo() {
    }

    /**
     * @param incluir los catálogos que se quieren en el fichero (los que no
     *                estén cargados se omiten)
     * @return número de hojas escritas
     */
    public static int exportar(Catalogos catalogos, List<TipoLista> incluir, File destino) throws IOException {
        List<HojaSalida> hojas = new ArrayList<>();
        Catalogos.Catalogo g = catalogos.get(TipoLista.GMDN);
        if (g != null && incluir.contains(TipoLista.GMDN)) {
            HojaSalida h = new HojaSalida(g.getHoja(), TerminoOficial.CABECERA_GMDN)
                    .estiloCabecera(EscritorXlsx.CABECERA_AMARILLA).sinFiltro().sinFijarCabecera()
                    .anchos(ANCHOS_GMDN);
            for (TerminoOficial t : g.getTerminos()) {
                String[] d = t.getDatos();
                h.anadir(numeroSiLoEs(d[0]), vacioANulo(d[1]), vacioANulo(d[2]));
            }
            hojas.add(h);
        }
        Catalogos.Catalogo m = catalogos.get(TipoLista.MANTENIMIENTO);
        if (m != null && incluir.contains(TipoLista.MANTENIMIENTO)) {
            HojaSalida h = new HojaSalida(m.getHoja(), TerminoOficial.CABECERA_MANTENIMIENTO)
                    .estiloCabecera(EscritorXlsx.CABECERA_AMARILLA).sinFijarCabecera()
                    .anchos(ANCHOS_MANTENIMIENTO);
            for (TerminoOficial t : m.getTerminos()) {
                String[] d = t.getDatos();
                Object[] fila = new Object[d.length];
                for (int i = 0; i < d.length; i++) {
                    fila[i] = vacioANulo(d[i]); // los códigos («01», «0103»…) se quedan como texto
                }
                h.anadir(fila);
            }
            hojas.add(h);
        }
        if (hojas.isEmpty()) {
            throw new IOException("No hay ningún catálogo cargado que exportar.");
        }
        EscritorXlsx.escribir(destino, hojas);
        return hojas.size();
    }

    /** Los códigos GMDN son números en el Excel original (10003…). */
    private static Object numeroSiLoEs(String s) {
        String t = s == null ? "" : s.trim();
        if (ENTERO.matcher(t).matches()) {
            return Long.valueOf(t);
        }
        return vacioANulo(s);
    }

    private static Object vacioANulo(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
