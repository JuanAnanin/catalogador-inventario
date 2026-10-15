package catalogador.ui;

import catalogador.logica.Exportador;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Proyecto;
import catalogador.util.Texto;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;

/**
 * Opciones de exportación: formato (Excel o CSV), qué equipos incluir, si se
 * añaden la marca y el modelo, carpeta y nombre de los ficheros. Enseña
 * antes de exportar qué ficheros se van a crear (uno por centro).
 */
final class DialogoExportar extends JDialog {

    private final Proyecto proyecto;
    private final JRadioButton rbExcel = new JRadioButton("Excel (.xlsx)", true);
    private final JRadioButton rbCsv = new JRadioButton("CSV separado por «;» (.csv)");
    private final JCheckBox chkSinCatalogar = new JCheckBox(
            "Incluir también los equipos sin catalogar (pendientes y dudas), con el nombre en blanco");
    private final JCheckBox chkMarcaModelo = new JCheckBox(
            "Añadir dos columnas con la marca y el modelo (con las correcciones hechas)");
    private final JTextField txtCarpeta = new JTextField(34);
    private final JTextField txtPrefijo = new JTextField(22);
    private final JTextArea txtVista = new JTextArea(8, 60);
    private Exportador.Opciones resultado;

    private DialogoExportar(Window padre, Proyecto proyecto, Exportador.Opciones previas) {
        super(padre, "Exportar el resultado", Dialog.ModalityType.APPLICATION_MODAL);
        this.proyecto = proyecto;

        JPanel raiz = new JPanel(new BorderLayout(0, 12));
        raiz.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        long revisados = proyecto.equipos(EstadoGrupo.REVISADO);
        raiz.add(new JLabel(Estilo.envolver("Se genera un fichero por cada centro (Equipo_codiCentro) con las columnas "
                + "<b>Equipo_codiCentro · Equipo_idEquipo · CÓDIGO GMDN · NOMBRE GMDN y NOMBRE MANTENIMIENTO</b>. "
                + "Los equipos del catálogo de Mantenimiento no llevan código GMDN.<br><br>"
                + "Equipos catalogados: <b>" + Estilo.numero(revisados) + "</b> de "
                + Estilo.numero(proyecto.equiposTotales()) + ".", 640)), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(3, 0, 3, 10);
        int fila = 0;

        ButtonGroup grupo = new ButtonGroup();
        grupo.add(rbExcel);
        grupo.add(rbCsv);
        JPanel formatos = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        formatos.add(rbExcel);
        formatos.add(javax.swing.Box.createHorizontalStrut(16));
        formatos.add(rbCsv);
        anadir(form, g, fila++, "Formato", formatos);
        anadir(form, g, fila++, "Contenido", chkSinCatalogar);
        anadir(form, g, fila++, "", chkMarcaModelo);

        JPanel carpeta = new JPanel(new BorderLayout(6, 0));
        carpeta.add(txtCarpeta, BorderLayout.CENTER);
        JButton elegir = new JButton("Elegir…");
        elegir.addActionListener(e -> elegirCarpeta());
        carpeta.add(elegir, BorderLayout.EAST);
        anadir(form, g, fila++, "Carpeta", carpeta);
        anadir(form, g, fila++, "Nombre de los ficheros", txtPrefijo);

        txtVista.setEditable(false);
        txtVista.setFont(Estilo.fuente(Font.PLAIN, 12f));
        JScrollPane sv = new JScrollPane(txtVista);
        JPanel vista = new JPanel(new BorderLayout(0, 4));
        vista.add(Estilo.etiqueta("Ficheros que se van a crear", Font.BOLD, 12f, Estilo.TEXTO), BorderLayout.NORTH);
        vista.add(sv, BorderLayout.CENTER);

        JPanel centro = new JPanel(new BorderLayout(0, 12));
        centro.add(form, BorderLayout.NORTH);
        centro.add(vista, BorderLayout.CENTER);
        raiz.add(centro, BorderLayout.CENTER);

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancelar = new JButton("Cancelar");
        JButton exportar = new JButton("Exportar");
        exportar.setFont(exportar.getFont().deriveFont(Font.BOLD));
        cancelar.addActionListener(e -> dispose());
        exportar.addActionListener(e -> aceptar());
        botones.add(cancelar);
        botones.add(exportar);
        raiz.add(botones, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(exportar);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setContentPane(raiz);

        rbCsv.setSelected(previas.csv);
        rbExcel.setSelected(!previas.csv);
        chkSinCatalogar.setSelected(previas.incluirSinCatalogar);
        chkMarcaModelo.setSelected(previas.incluirMarcaModelo);
        txtCarpeta.setText(previas.carpeta == null ? "" : previas.carpeta.getAbsolutePath());
        txtPrefijo.setText(previas.prefijo);

        Runnable r = this::refrescarVista;
        rbExcel.addActionListener(e -> r.run());
        rbCsv.addActionListener(e -> r.run());
        chkSinCatalogar.addActionListener(e -> r.run());
        txtCarpeta.getDocument().addDocumentListener(alCambiar(r));
        txtPrefijo.getDocument().addDocumentListener(alCambiar(r));
        refrescarVista();

        pack();
        Estilo.ajustarAPantalla(this, Math.max(getWidth(), 720), getHeight(), Math.max(getWidth(), 720), 520, padre);
    }

    /** Muestra el diálogo. Devuelve las opciones elegidas, o null si se cancela. */
    static Exportador.Opciones mostrar(Window padre, Proyecto proyecto, Exportador.Opciones previas) {
        DialogoExportar d = new DialogoExportar(padre, proyecto, previas);
        d.setVisible(true);
        return d.resultado;
    }

    private static void anadir(JPanel p, GridBagConstraints g, int fila, String etiqueta, JComponent c) {
        g.gridy = fila;
        g.gridx = 0;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        JLabel l = new JLabel(etiqueta);
        l.setFont(Estilo.fuente(Font.BOLD, 12.5f));
        p.add(l, g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        p.add(c, g);
    }

    private Exportador.Opciones opciones() {
        Exportador.Opciones o = new Exportador.Opciones();
        o.csv = rbCsv.isSelected();
        o.incluirSinCatalogar = chkSinCatalogar.isSelected();
        o.incluirMarcaModelo = chkMarcaModelo.isSelected();
        o.carpeta = new File(txtCarpeta.getText().trim());
        o.prefijo = txtPrefijo.getText().trim();
        return o;
    }

    private void refrescarVista() {
        Exportador.Opciones o = opciones();
        List<Exportador.Fichero> fs = Exportador.previsualizar(proyecto, o);
        StringBuilder b = new StringBuilder();
        if (fs.isEmpty()) {
            b.append("No hay equipos que exportar con estas opciones.\n");
            if (!o.incluirSinCatalogar) {
                b.append("(Todavía no se ha catalogado ningún equipo. Marca «Incluir también los equipos sin catalogar» ")
                        .append("si quieres exportar igualmente.)");
            }
        } else {
            int existen = 0;
            for (Exportador.Fichero f : fs) {
                boolean existe = f.getFichero().exists();
                if (existe) {
                    existen++;
                }
                b.append(f.getFichero().getName()).append("   ·   ").append(Estilo.numero(f.getFilas()))
                        .append(f.getFilas() == 1 ? " equipo" : " equipos")
                        .append(existe ? "   (ya existe: se sustituirá)" : "").append('\n');
            }
            b.insert(0, fs.size() + (fs.size() == 1 ? " fichero" : " ficheros")
                    + (existen > 0 ? " · " + existen + " ya existen y se sustituirán" : "") + ":\n\n");
        }
        txtVista.setText(b.toString());
        txtVista.setCaretPosition(0);
    }

    private void elegirCarpeta() {
        File actual = new File(txtCarpeta.getText().trim());
        File elegida = null;
        if (Estilo.MAC) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true");
            FileDialog fd = new FileDialog((Frame) null, "Carpeta donde guardar", FileDialog.LOAD);
            fd.setDirectory(actual.getAbsolutePath());
            fd.setVisible(true);
            System.setProperty("apple.awt.fileDialogForDirectories", "false");
            if (fd.getFile() != null) {
                elegida = new File(fd.getDirectory(), fd.getFile());
            }
        } else {
            JFileChooser fc = new JFileChooser(actual.isDirectory() ? actual : null);
            fc.setDialogTitle("Carpeta donde guardar");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                elegida = fc.getSelectedFile();
            }
        }
        if (elegida != null) {
            txtCarpeta.setText(elegida.getAbsolutePath());
        }
    }

    private void aceptar() {
        Exportador.Opciones o = opciones();
        if (Texto.vacio(txtCarpeta.getText()) || !o.carpeta.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Elige una carpeta que exista.", "Carpeta", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (o.prefijo.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Escribe un nombre para los ficheros.", "Nombre",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Exportador.Fichero> fs = Exportador.previsualizar(proyecto, o);
        if (fs.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No hay equipos que exportar con estas opciones.", "Exportar",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        resultado = o;
        dispose();
    }

    private static DocumentListener alCambiar(Runnable r) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        };
    }
}
