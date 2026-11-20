package catalogador.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.Function;

/**
 * Opciones de catalogación automática, las dos desactivadas por defecto (sin
 * ellas el programa propone y la persona verifica antes de aceptar):
 * <ul>
 *   <li><b>Idénticos</b> (modo manual y modo IA): los equipos idénticos a otros ya
 *       catalogados —coincidencia del 100 %— se catalogan sin preguntar.</li>
 *   <li><b>IA</b> (solo en modo IA): las propuestas de la IA con al menos N % de
 *       confianza se aceptan sin preguntar.</li>
 * </ul>
 * El mismo panel, más compacto, aparece al importar un inventario.
 */
final class DialogoAutomatico {

    /** Lo elegido. */
    static final class Opciones {
        boolean identicos;
        boolean ia;
        int umbral;

        Opciones(boolean identicos, boolean ia, int umbral) {
            this.identicos = identicos;
            this.ia = ia;
            this.umbral = umbral;
        }
    }

    /** Las casillas (y el porcentaje) de la catalogación automática. */
    static final class Panel extends JPanel {
        final JCheckBox chkIdenticos = new JCheckBox("Catalogar sin preguntar los equipos idénticos a otros ya catalogados");
        final JCheckBox chkIA = new JCheckBox("Modo IA: aceptar sin preguntar las propuestas de la IA con al menos");
        final JSpinner spUmbral;

        Panel(Opciones o, boolean modoIA, boolean compacto) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            int ancho = compacto ? 520 : 560;
            chkIdenticos.setSelected(o.identicos);
            chkIdenticos.setToolTipText("Coincidencia del 100 %: el mismo nombre, la misma marca y el mismo modelo");
            chkIdenticos.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(chkIdenticos);
            add(explicacion("Coincidencia del 100 %: las mismas palabras del nombre (en cualquier orden, sin importar "
                    + "mayúsculas ni tildes), la misma marca y el mismo modelo. Se les asigna el mismo código y nombre "
                    + "que al ya catalogado. Funciona en modo manual y en modo IA"
                    + (compacto ? "." : ": al importar un inventario, al abrir un proyecto y cada vez que catalogas un "
                    + "equipo."), ancho));
            add(Box.createVerticalStrut(compacto ? 4 : 8));

            spUmbral = new JSpinner(new SpinnerNumberModel(o.umbral, 50, 100, 1));
            JPanel filaIA = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            filaIA.setOpaque(false);
            chkIA.setSelected(o.ia);
            filaIA.add(chkIA);
            filaIA.add(Box.createHorizontalStrut(4));
            filaIA.add(spUmbral);
            filaIA.add(new JLabel(" % de confianza"));
            filaIA.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(filaIA);
            add(explicacion((modoIA ? "" : "<font color='" + Estilo.hex(Estilo.NARANJA) + "'>Solo se aplica en modo IA "
                    + "(ahora estás en modo manual).</font> ")
                    + (compacto ? "Al importar se piden propuestas para todos los grupos pendientes y se aceptan las "
                    + "que lleguen a ese porcentaje; el resto quedan para revisar."
                    : "Se aplica al importar un inventario (se piden propuestas para todos los grupos pendientes), a "
                    + "«Pedir propuestas para todos» y a las propuestas que llegan mientras trabajas, salvo la del "
                    + "grupo que tienes en pantalla. Solo se aceptan propuestas de grupos pendientes cuyo término "
                    + "está en el catálogo."), ancho));
            Runnable r = () -> spUmbral.setEnabled(chkIA.isSelected());
            chkIA.addActionListener(e -> r.run());
            r.run();
        }

        private static JComponent explicacion(String html, int ancho) {
            JLabel l = new JLabel(Estilo.envolver(html, ancho));
            l.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
            l.setForeground(Estilo.TEXTO_SUAVE);
            l.setBorder(BorderFactory.createEmptyBorder(1, 26, 0, 0));
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            return l;
        }

        Opciones opciones() {
            return new Opciones(chkIdenticos.isSelected(), chkIA.isSelected(), (Integer) spUmbral.getValue());
        }
    }

    private DialogoAutomatico() {
    }

    /**
     * Muestra las opciones. {@code previsto} devuelve, para unas opciones, el texto de lo
     * que se catalogará al aceptar en el proyecto abierto (o null si no hay proyecto).
     * Devuelve las opciones elegidas, o null si se cancela.
     */
    static Opciones mostrar(Component padre, Opciones actuales, boolean modoIA, Function<Opciones, String> previsto) {
        JPanel raiz = new JPanel();
        raiz.setLayout(new BoxLayout(raiz, BoxLayout.Y_AXIS));
        JLabel intro = new JLabel(Estilo.envolver("Sin estas opciones el programa <b>no cataloga nada solo</b>: propone "
                + "y tú verificas antes de aceptar, por si hay algún error. Si las activas, lo que es seguro se cataloga "
                + "sin preguntar y queda anotado: puedes revisarlo cuando quieras con el filtro <b>«Catalogados "
                + "automáticamente»</b> de la cola y devolver cualquiera a pendiente.", 560));
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        raiz.add(intro);
        raiz.add(Box.createVerticalStrut(12));
        Panel p = new Panel(actuales, modoIA, false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        raiz.add(p);
        JLabel lblPrevisto = new JLabel(" ");
        lblPrevisto.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblPrevisto.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        raiz.add(lblPrevisto);
        Runnable r = () -> {
            String t = previsto.apply(p.opciones());
            lblPrevisto.setText(t == null ? " " : Estilo.envolver(t, 560));
        };
        p.chkIdenticos.addActionListener(e -> r.run());
        p.chkIA.addActionListener(e -> r.run());
        p.spUmbral.addChangeListener(e -> r.run());
        r.run();
        int ok = JOptionPane.showConfirmDialog(padre, raiz, "Catalogación automática", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        return ok == JOptionPane.OK_OPTION ? p.opciones() : null;
    }
}
