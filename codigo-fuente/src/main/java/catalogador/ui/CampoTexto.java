package catalogador.ui;

import javax.swing.JTextField;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/** Campo de texto con un texto de ayuda en gris cuando está vacío. */
final class CampoTexto extends JTextField {

    private final String ayuda;

    CampoTexto(String ayuda) {
        this.ayuda = ayuda;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (getText().isEmpty() && ayuda != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Estilo.TEXTO_TENUE);
            g2.setFont(getFont());
            Insets in = getInsets();
            int y = (getHeight() - g2.getFontMetrics().getHeight()) / 2 + g2.getFontMetrics().getAscent();
            g2.drawString(ayuda, in.left + 2, y);
            g2.dispose();
        }
    }
}
