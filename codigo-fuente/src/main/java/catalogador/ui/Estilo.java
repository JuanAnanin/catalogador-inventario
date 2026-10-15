package catalogador.ui;

import catalogador.modelo.EstadoGrupo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.Locale;

/** Colores, tipos de letra y piezas visuales comunes. */
final class Estilo {

    static final Color FONDO = new Color(0xF1F3F6);
    static final Color TARJETA = Color.WHITE;
    static final Color BORDE = new Color(0xD8DDE4);
    static final Color TEXTO = new Color(0x1D2733);
    static final Color TEXTO_SUAVE = new Color(0x667085);
    static final Color TEXTO_TENUE = new Color(0x98A2B3);
    static final Color ACENTO = new Color(0x1A5FB4);
    static final Color ACENTO_CLARO = new Color(0xE3EDFA);
    static final Color VERDE = new Color(0x1E8E3E);
    static final Color VERDE_CLARO = new Color(0xE6F4EA);
    static final Color NARANJA = new Color(0xE37400);
    static final Color NARANJA_CLARO = new Color(0xFEF1DE);
    static final Color ROJO = new Color(0xC5221F);
    static final Color GRIS = new Color(0x98A2B3);
    static final Color MORADO = new Color(0x7B3FA0);
    static final Color FONDO_SUAVE = new Color(0xF7F9FB);

    static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** Ctrl en Windows/Linux, ⌘ en Mac. */
    static final int MASCARA = mascara();

    private static final NumberFormat NUMEROS = NumberFormat.getIntegerInstance(new Locale("es", "ES"));

    private Estilo() {
    }

    private static int mascara() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            return java.awt.event.InputEvent.CTRL_DOWN_MASK;
        }
    }

    /** «⌘P» en Mac; «Ctrl+P» en Windows y Linux (y «⇧» se escribe «Mayús+»). */
    static String atajo(String tecla) {
        return MAC ? "⌘" + tecla : "Ctrl+" + tecla.replace("⇧", "Mayús+");
    }

    static String numero(long n) {
        return NUMEROS.format(n);
    }

    static Font base() {
        Font f = UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    static Font fuente(int estilo, float tamano) {
        return base().deriveFont(estilo, tamano);
    }

    static Border relleno(int arriba, int izquierda, int abajo, int derecha) {
        return BorderFactory.createEmptyBorder(arriba, izquierda, abajo, derecha);
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static Color colorEstado(EstadoGrupo e) {
        switch (e) {
            case REVISADO: return VERDE;
            case POSPUESTO: return NARANJA;
            default: return GRIS;
        }
    }

    /** Panel blanco con borde fino y título pequeño en mayúsculas. */
    static JPanel tarjeta(String titulo, JComponent contenido) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(TARJETA);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDE, 1, true), relleno(10, 14, 12, 14)));
        if (titulo != null) {
            p.add(tituloTarjeta(titulo), BorderLayout.NORTH);
        }
        contenido.setOpaque(false);
        p.add(contenido, BorderLayout.CENTER);
        return p;
    }

    static JLabel tituloTarjeta(String titulo) {
        JLabel t = new JLabel(titulo.toUpperCase(new Locale("es", "ES")));
        t.setFont(fuente(Font.BOLD, 11f));
        t.setForeground(TEXTO_SUAVE);
        return t;
    }

    static JLabel etiqueta(String texto, int estilo, float tamano, Color color) {
        JLabel l = new JLabel(texto);
        l.setFont(fuente(estilo, tamano));
        l.setForeground(color);
        return l;
    }

    /** Pequeño bloque "TÍTULO / valor" para los datos del aparato. */
    static JPanel dato(String titulo, JLabel valor) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel t = etiqueta(titulo, Font.BOLD, 10.5f, TEXTO_TENUE);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        valor.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(t);
        p.add(Box.createVerticalStrut(2));
        p.add(valor);
        return p;
    }

    /** Botón con aspecto de enlace. */
    /** Zona útil de la pantalla (sin la barra de tareas ni la de menús). */
    static java.awt.Rectangle zonaUtil() {
        try {
            return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        } catch (RuntimeException e) {
            java.awt.Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
            return new java.awt.Rectangle(0, 0, d.width, d.height);
        }
    }

    /**
     * Da a una ventana el tamaño pedido sin pasarse de la pantalla (portátiles
     * con la escala de Windows al 125-150 %) y la centra sobre su padre.
     */
    static void ajustarAPantalla(java.awt.Window w, int ancho, int alto, int minAncho, int minAlto,
                                 java.awt.Component padre) {
        java.awt.Rectangle u = zonaUtil();
        int maxAncho = Math.max(400, u.width - 16);
        int maxAlto = Math.max(300, u.height - 16);
        w.setMinimumSize(new java.awt.Dimension(Math.min(minAncho, maxAncho), Math.min(minAlto, maxAlto)));
        w.setSize(Math.min(ancho, maxAncho), Math.min(alto, maxAlto));
        w.setLocationRelativeTo(padre);
        java.awt.Point p = w.getLocation();
        w.setLocation(Math.max(u.x, Math.min(p.x, u.x + u.width - w.getWidth())),
                Math.max(u.y, Math.min(p.y, u.y + u.height - w.getHeight())));
    }

    static JButton enlace(String texto) {
        JButton b = new JButton(texto);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setForeground(ACENTO);
        b.setHorizontalAlignment(JButton.LEFT);
        b.setBorder(relleno(2, 0, 2, 0));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Puntos por pulgada de la pantalla (Swing convierte con ellos los «px» del HTML). */
    private static final float PPP = puntosPorPulgada();

    private static float puntosPorPulgada() {
        try {
            int r = Toolkit.getDefaultToolkit().getScreenResolution();
            return r > 0 ? r : 96;
        } catch (java.awt.HeadlessException e) {
            return 96;
        }
    }

    /**
     * Envuelve HTML para que Swing lo parta en líneas a un ancho dado. Swing
     * multiplica los «px» del HTML por (ppp de la pantalla / 72), que cambia
     * entre Windows (96), macOS (72) o con la pantalla ampliada; se compensa
     * para que el texto ocupe lo mismo en todos: unos {@code ancho × 1,39}
     * píxeles de la interfaz.
     */
    static String envolver(String html, int ancho) {
        int css = Math.round(ancho * 100f / PPP);
        return "<html><div style='width:" + css + "px'>" + html + "</div></html>";
    }

    /** Icono de la aplicación dibujado por código (sin ficheros externos). */
    static Image icono(int tam) {
        BufferedImage img = new BufferedImage(tam, tam, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(ACENTO);
        g.fillRoundRect(0, 0, tam, tam, tam / 4, tam / 4);
        g.setColor(Color.WHITE);
        float s = tam / 64f;
        g.setStroke(new BasicStroke(5 * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 3; i++) {
            int y = Math.round((18 + i * 14) * s);
            g.drawLine(Math.round(30 * s), y, Math.round(50 * s), y);
        }
        g.setColor(new Color(0x7EE2A0));
        g.drawPolyline(new int[]{Math.round(12 * s), Math.round(16 * s), Math.round(23 * s)},
                new int[]{Math.round(18 * s), Math.round(22 * s), Math.round(13 * s)}, 3);
        g.drawPolyline(new int[]{Math.round(12 * s), Math.round(16 * s), Math.round(23 * s)},
                new int[]{Math.round(32 * s), Math.round(36 * s), Math.round(27 * s)}, 3);
        g.setColor(new Color(255, 255, 255, 150));
        g.drawOval(Math.round(13 * s), Math.round(42 * s), Math.round(9 * s), Math.round(9 * s));
        g.dispose();
        return img;
    }
}
