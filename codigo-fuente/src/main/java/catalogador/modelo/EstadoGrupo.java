package catalogador.modelo;

/** Situación de cada grupo de aparatos en la revisión. */
public enum EstadoGrupo {

    PENDIENTE("Pendiente"),
    REVISADO("Revisado"),
    POSPUESTO("Pospuesto (duda)");

    private final String texto;

    EstadoGrupo(String texto) {
        this.texto = texto;
    }

    public String texto() {
        return texto;
    }
}
