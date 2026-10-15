package catalogador.ui;

import catalogador.logica.Sugerencia;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Recuadro de color con el porcentaje de parecido (verde / naranja / rojo). */
final class InsigniaSimilitud extends JComponent {

    private double valor = -1;

    void setValor(double valor) {
        this.valor = valor;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(74, 50);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (valor < 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(Sugerencia.color(valor));
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g2.setColor(Color.WHITE);
        g2.setFont(Estilo.fuente(Font.BOLD, 20f));
        String t = Math.round(valor * 100) + "%";
        FontMetrics fm = g2.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(t)) / 2;
        int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(t, x, y);
        g2.dispose();
    }
}
