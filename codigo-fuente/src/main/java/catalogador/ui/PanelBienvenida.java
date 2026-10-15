package catalogador.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;

/** Pantalla inicial cuando todavía no hay ningún proyecto abierto. */
final class PanelBienvenida extends JPanel {

    private final JLabel estadoGmdn = new JLabel();
    private final JLabel estadoMant = new JLabel();
    private final JLabel estadoMemoria = new JLabel();

    PanelBienvenida(Runnable importarCatalogos, Runnable nuevoProyecto, Runnable abrirProyecto,
                    String carpetaDatos) {
        super(new GridBagLayout());
        setBackground(Estilo.FONDO);

        JPanel caja = new JPanel();
        caja.setLayout(new BoxLayout(caja, BoxLayout.Y_AXIS));
        caja.setBackground(Color.WHITE);
        caja.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Estilo.BORDE, 1, true), Estilo.relleno(28, 34, 26, 34)));

        JPanel titulo = new JPanel(new BorderLayout(14, 0));
        titulo.setOpaque(false);
        titulo.add(new JLabel(new ImageIcon(Estilo.icono(52))), BorderLayout.WEST);
        JPanel textos = new JPanel(new BorderLayout(0, 2));
        textos.setOpaque(false);
        textos.add(Estilo.etiqueta("Catalogador de inventario", Font.BOLD, 24f, Estilo.TEXTO), BorderLayout.NORTH);
        textos.add(Estilo.etiqueta("Relaciona cada equipo del hospital con su nombre oficial (GMDN o Mantenimiento), rápido y sin perder nada.",
                Font.PLAIN, 13.5f, Estilo.TEXTO_SUAVE), BorderLayout.SOUTH);
        titulo.add(textos, BorderLayout.CENTER);
        anadir(caja, titulo);
        caja.add(Box.createVerticalStrut(26));

        anadir(caja, paso("1", "Carga los catálogos oficiales",
                "Un Excel con dos hojas: GMDN y Mantenimiento. Solo hace falta la primera vez; quedan guardados."));
        caja.add(Box.createVerticalStrut(10));
        anadir(caja, filaBoton("Importar catálogos (GMDN y Mantenimiento)…", importarCatalogos, estadoGmdn));
        JPanel filaMant = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        filaMant.setOpaque(false);
        filaMant.add(Box.createHorizontalStrut(40 + 330 + 12));
        estadoMant.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
        filaMant.add(estadoMant);
        caja.add(Box.createVerticalStrut(2));
        anadir(caja, filaMant);
        caja.add(Box.createVerticalStrut(22));

        anadir(caja, paso("2", "Importa el inventario del hospital",
                "Se descartan los equipos de baja, se juntan los repetidos y se ordenan de más a menos equipos."));
        caja.add(Box.createVerticalStrut(10));
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        botones.setOpaque(false);
        JButton nuevo = new JButton("Nuevo proyecto desde un inventario…");
        nuevo.setFont(nuevo.getFont().deriveFont(Font.BOLD));
        nuevo.addActionListener(e -> nuevoProyecto.run());
        JButton abrir = new JButton("Abrir un proyecto guardado…");
        abrir.addActionListener(e -> abrirProyecto.run());
        botones.add(Box.createHorizontalStrut(40));
        botones.add(nuevo);
        botones.add(Box.createHorizontalStrut(8));
        botones.add(abrir);
        anadir(caja, botones);
        caja.add(Box.createVerticalStrut(26));

        estadoMemoria.setFont(Estilo.fuente(Font.PLAIN, 12f));
        estadoMemoria.setForeground(Estilo.TEXTO_SUAVE);
        anadir(caja, estadoMemoria);
        caja.add(Box.createVerticalStrut(4));
        anadir(caja, Estilo.etiqueta("Todo se guarda automáticamente en: " + carpetaDatos, Font.PLAIN, 12f,
                Estilo.TEXTO_SUAVE));

        add(caja);
    }

    private static void anadir(JPanel caja, java.awt.Component c) {
        if (c instanceof javax.swing.JComponent) {
            ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        caja.add(c);
    }

    private static JPanel paso(String numero, String titulo, String detalle) {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setOpaque(false);
        JLabel n = new JLabel(numero, JLabel.CENTER) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Estilo.ACENTO);
                g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        n.setForeground(Color.WHITE);
        n.setFont(Estilo.fuente(Font.BOLD, 14f));
        n.setPreferredSize(new Dimension(28, 28));
        p.add(n, BorderLayout.WEST);
        JPanel t = new JPanel(new BorderLayout());
        t.setOpaque(false);
        t.add(Estilo.etiqueta(titulo, Font.BOLD, 15f, Estilo.TEXTO), BorderLayout.NORTH);
        t.add(Estilo.etiqueta(detalle, Font.PLAIN, 12.5f, Estilo.TEXTO_SUAVE), BorderLayout.SOUTH);
        p.add(t, BorderLayout.CENTER);
        return p;
    }

    private static JPanel filaBoton(String texto, Runnable accion, JLabel estado) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        JButton b = new JButton(texto);
        b.addActionListener(e -> accion.run());
        b.setPreferredSize(new Dimension(330, b.getPreferredSize().height));
        p.add(Box.createHorizontalStrut(40));
        p.add(b);
        p.add(Box.createHorizontalStrut(12));
        estado.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
        p.add(estado);
        return p;
    }

    void actualizar(String gmdn, boolean gmdnOk, String mant, boolean mantOk, String memoria) {
        estadoGmdn.setText((gmdnOk ? "✓ " : "") + "GMDN: " + gmdn);
        estadoGmdn.setForeground(gmdnOk ? Estilo.VERDE : Estilo.TEXTO_SUAVE);
        estadoMant.setText((mantOk ? "✓ " : "") + "Mantenimiento: " + mant);
        estadoMant.setForeground(mantOk ? Estilo.VERDE : Estilo.TEXTO_SUAVE);
        estadoMemoria.setText(memoria);
    }
}
