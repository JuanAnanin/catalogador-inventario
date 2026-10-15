package catalogador.ia;

import catalogador.modelo.TipoLista;
import catalogador.util.Texto;
import catalogador.util.Tsv;

/**
 * Lo que la IA propone para un grupo de equipos: un término de uno de los dos
 * catálogos (siempre uno de los candidatos que se le enviaron, nunca uno
 * inventado), lo segura que está (0–100), por qué, y —si lo ve— la marca y el
 * modelo escritos de forma limpia. Si ningún candidato le encaja, la propuesta
 * queda sin término ({@link #tieneTermino()} = false) y explica el motivo.
 */
public final class PropuestaIA {

    private final String claveGrupo;
    private final TipoLista lista;
    private final String codigo;
    private final String nombreOficial;
    private final int confianza;
    private final String motivo;
    private final String marca;
    private final String modelo;
    private final String fuente;
    private final String fecha;

    public PropuestaIA(String claveGrupo, TipoLista lista, String codigo, String nombreOficial, int confianza,
                       String motivo, String marca, String modelo, String fuente, String fecha) {
        this.claveGrupo = claveGrupo;
        this.lista = lista;
        this.codigo = Texto.seguro(codigo);
        this.nombreOficial = Texto.seguro(nombreOficial);
        this.confianza = Math.max(0, Math.min(100, confianza));
        this.motivo = Texto.seguro(motivo).trim();
        this.marca = Texto.seguro(marca).trim();
        this.modelo = Texto.seguro(modelo).trim();
        this.fuente = Texto.seguro(fuente);
        this.fecha = Texto.seguro(fecha);
    }

    /** Propuesta vacía (la IA no ha encontrado un término que encaje). */
    public static PropuestaIA sinTermino(String claveGrupo, String motivo, String fuente, String fecha) {
        return new PropuestaIA(claveGrupo, null, "", "", 0, motivo, "", "", fuente, fecha);
    }

    public boolean tieneTermino() {
        return lista != null && !nombreOficial.isEmpty();
    }

    public String getClaveGrupo() {
        return claveGrupo;
    }

    public TipoLista getLista() {
        return lista;
    }

    /** GMDN: el código. Mantenimiento: el nombre (igual que en {@code Grupo}). */
    public String getCodigo() {
        return codigo;
    }

    public String getNombreOficial() {
        return nombreOficial;
    }

    /** 0 a 100. */
    public int getConfianza() {
        return confianza;
    }

    public String getMotivo() {
        return motivo;
    }

    /** Marca escrita de forma limpia según la IA (vacía si no propone cambio). */
    public String getMarca() {
        return marca;
    }

    public String getModelo() {
        return modelo;
    }

    /** Servicio y modelo que la hicieron: «Groq · llama-3.3-70b-versatile». */
    public String getFuente() {
        return fuente;
    }

    public String getFecha() {
        return fecha;
    }

    String linea() {
        return Tsv.linea("P", claveGrupo, lista == null ? "" : lista.name(), codigo, nombreOficial,
                String.valueOf(confianza), motivo, marca, modelo, fuente, fecha);
    }

    static PropuestaIA desdeCampos(String[] c) {
        if (c.length < 11 || !"P".equals(c[0])) {
            return null;
        }
        TipoLista l = c[2].isEmpty() ? null : TipoLista.valueOf(c[2]);
        int conf;
        try {
            conf = Integer.parseInt(c[5]);
        } catch (NumberFormatException e) {
            conf = 0;
        }
        return new PropuestaIA(c[1], l, c[3], c[4], conf, c[6], c[7], c[8], c[9], c[10]);
    }

    @Override
    public String toString() {
        return tieneTermino() ? (lista == TipoLista.GMDN ? "GMDN " + codigo + " · " : "Mant. · ") + nombreOficial
                + " (" + confianza + " %)" : "sin término: " + motivo;
    }
}
