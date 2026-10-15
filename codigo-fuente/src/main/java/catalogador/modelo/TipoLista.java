package catalogador.modelo;

import catalogador.util.Texto;

/** Los dos catálogos oficiales de nombres autorizados. */
public enum TipoLista {

    GMDN("GMDN", "Catálogo GMDN"),
    MANTENIMIENTO("Mantenimiento", "Catálogo de Mantenimiento");

    private final String corto;
    private final String largo;

    TipoLista(String corto, String largo) {
        this.corto = corto;
        this.largo = largo;
    }

    public String corto() {
        return corto;
    }

    public String largo() {
        return largo;
    }

    public TipoLista otra() {
        return this == GMDN ? MANTENIMIENTO : GMDN;
    }

    /** Interpreta textos como "GMDN", "Mantenimiento", "MANT"... */
    public static TipoLista desdeTexto(String s) {
        if (Texto.vacio(s)) {
            return null;
        }
        for (TipoLista t : values()) {
            if (t.name().equalsIgnoreCase(s.trim()) || t.corto.equalsIgnoreCase(s.trim())
                    || t.largo.equalsIgnoreCase(s.trim())) {
                return t;
            }
        }
        String n = Texto.normalizar(s);
        if (n.contains("gmdn")) {
            return GMDN;
        }
        if (n.contains("mant")) {
            return MANTENIMIENTO;
        }
        return null;
    }
}
