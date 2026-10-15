package catalogador.ui;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Etiqueta con fondo de color redondeado (estado del aparato, etc.). */
final class Chip extends JLabel {

    private Color fondo = Estilo.GRIS;

    Chip() {
        setFont(Estilo.fuente(Font.BOLD, 11.5f));
        setForeground(Color.WHITE);
        setHorizontalAlignment(CENTER);
        setBorder(Estilo.relleno(3, 10, 3, 10));
        setOpaque(false);
    }

    void mostrar(String texto, Color fondo) {
        this.fondo = fondo;
        setText(texto);
        setVisible(texto != null && !texto.isEmpty());
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, Math.max(d.height, 22));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(fondo);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
