package catalogador.logica;

import catalogador.modelo.TipoLista;
import catalogador.util.Texto;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Propuesta automática para un grupo: basada en algo que ya se clasificó antes
 * (memoria), hecha por la IA, o ambas cosas a la vez si coinciden.
 */
public final class Sugerencia {

    /** De dónde sale la propuesta. */
    public enum Origen {
        MEMORIA, IA, MEMORIA_E_IA
    }

    public static final double UMBRAL_VERDE = 0.85;
    public static final double UMBRAL_NARANJA = 0.65;

    private final TipoLista lista;
    private final String codigo;
    private final String nombreOficial;
    private final double similitud;
    private final Memoria.Entrada basadaEn;
    private final Origen origen;
    private final String motivo;
    private final String fuente;

    public Sugerencia(TipoLista lista, String codigo, String nombreOficial, double similitud, Memoria.Entrada basadaEn) {
        this(lista, codigo, nombreOficial, similitud, basadaEn, Origen.MEMORIA, "", "");
    }

    private Sugerencia(TipoLista lista, String codigo, String nombreOficial, double similitud, Memoria.Entrada basadaEn,
                       Origen origen, String motivo, String fuente) {
        this.lista = lista;
        this.codigo = codigo;
        this.nombreOficial = nombreOficial;
        this.similitud = similitud;
        this.basadaEn = basadaEn;
        this.origen = origen;
        this.motivo = motivo == null ? "" : motivo;
        this.fuente = fuente == null ? "" : fuente;
    }

    /** Propuesta de la IA; la confianza (0–100) hace de porcentaje de parecido. */
    public static Sugerencia deIA(TipoLista lista, String codigo, String nombreOficial, int confianza, String motivo,
                                  String fuente) {
        return new Sugerencia(lista, codigo, nombreOficial, Math.max(0, Math.min(100, confianza)) / 100.0, null,
                Origen.IA, motivo, fuente);
    }

    /**
     * Junta las sugerencias de la memoria con la de la IA: si la IA propone lo
     * mismo que la memoria, se quedan en una sola («coinciden»); si no, se
     * ordenan todas de más a menos porcentaje.
     */
    public static List<Sugerencia> combinar(List<Sugerencia> deMemoria, Sugerencia ia, int max) {
        List<Sugerencia> l = new ArrayList<>();
        boolean unida = false;
        for (Sugerencia s : deMemoria) {
            if (ia != null && !unida && s.lista == ia.lista && Texto.normalizar(s.codigo).equals(Texto.normalizar(ia.codigo))) {
                double v = Math.min(0.99, Math.max(s.similitud, ia.similitud) + 0.02);
                l.add(new Sugerencia(s.lista, s.codigo, s.nombreOficial, v, s.basadaEn, Origen.MEMORIA_E_IA, ia.motivo,
                        ia.fuente));
                unida = true;
            } else {
                l.add(s);
            }
        }
        if (ia != null && !unida) {
            l.add(ia);
        }
        l.sort((a, b) -> Double.compare(b.similitud, a.similitud));
        return l.size() > max ? new ArrayList<>(l.subList(0, max)) : l;
    }

    public TipoLista getLista() {
        return lista;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNombreOficial() {
        return nombreOficial;
    }

    public double getSimilitud() {
        return similitud;
    }

    /** Lo ya clasificado en que se basa (null si la propuesta es solo de la IA). */
    public Memoria.Entrada getBasadaEn() {
        return basadaEn;
    }

    public Origen getOrigen() {
        return origen;
    }

    public boolean esDeIA() {
        return origen != Origen.MEMORIA;
    }

    /** Explicación de la IA (vacía si no interviene). */
    public String getMotivo() {
        return motivo;
    }

    /** Servicio y modelo de IA que la hizo. */
    public String getFuente() {
        return fuente;
    }

    public int porcentaje() {
        return (int) Math.round(similitud * 100);
    }

    /** Verde = casi seguro, naranja = revisar, rojo = dudoso. */
    public static Color color(double similitud) {
        if (similitud >= UMBRAL_VERDE) {
            return new Color(0x1E8E3E);
        }
        if (similitud >= UMBRAL_NARANJA) {
            return new Color(0xE37400);
        }
        return new Color(0xC5221F);
    }

    public static String nivel(double similitud) {
        if (similitud >= UMBRAL_VERDE) {
            return "Casi seguro";
        }
        if (similitud >= UMBRAL_NARANJA) {
            return "Revísalo";
        }
        return "Dudoso";
    }
}
