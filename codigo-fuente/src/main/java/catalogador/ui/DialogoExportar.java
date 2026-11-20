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
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opciones de exportación: formato (Excel o CSV), qué equipos incluir, si se
 * añaden la marca y el modelo, carpeta y nombre de los ficheros. Enseña
 * antes de exportar los ficheros que se pueden crear (uno por centro) y
 * permite elegir cuáles se generan: uno, varios o todos.
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
    private final ModeloFicheros modeloFicheros = new ModeloFicheros();
    private final JTable tablaFicheros = new JTable(modeloFicheros);
    private final JLabel lblElegidos = new JLabel(" ");
    private Exportador.Opciones resultado;

    /** Ficheros que se pueden crear, con una casilla para elegir cuáles se generan. */
    private final class ModeloFicheros extends AbstractTableModel {
        private List<Exportador.Fichero> ficheros = new ArrayList<>();
        /** Centros elegidos; null = todos (también los que aparezcan al cambiar las opciones). */
        private Set<String> elegidos;

        void poner(List<Exportador.Fichero> fs) {
            ficheros = fs;
            fireTableDataChanged();
            refrescarElegidos();
        }

        boolean elegido(Exportador.Fichero f) {
            return elegidos == null || elegidos.contains(f.getCentro());
        }

        void todos(boolean si) {
            elegidos = si ? null : new HashSet<>();
            fireTableDataChanged();
            refrescarElegidos();
        }

        /** Centros elegidos de los que hay ahora; null si están todos. */
        Set<String> centros() {
            if (elegidos == null) {
                return null;
            }
            Set<String> c = new LinkedHashSet<>();
            boolean todos = true;
            for (Exportador.Fichero f : ficheros) {
                if (elegidos.contains(f.getCentro())) {
                    c.add(f.getCentro());
                } else {
                    todos = false;
                }
            }
            return todos && !ficheros.isEmpty() ? null : c;
        }

        Exportador.Fichero fichero(int fila) {
            return ficheros.get(fila);
        }

        @Override
        public int getRowCount() {
            return ficheros.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "Generar" : c == 1 ? "Centro" : c == 2 ? "Fichero" : "Equipos";
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : c == 3 ? Integer.class : String.class;
        }

        @Override
        public boolean isCellEditable(int f, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int f, int c) {
            Exportador.Fichero x = ficheros.get(f);
            switch (c) {
                case 0: return elegido(x);
                case 1: return x.getCentro().isEmpty() ? "(sin centro)" : x.getCentro();
                case 2: return x.getFichero().getName() + (x.getFichero().exists() ? "   (ya existe: se sustituirá)" : "");
                default: return x.getFilas();
            }
        }

        @Override
        public void setValueAt(Object v, int f, int c) {
            if (c != 0) {
                return;
            }
            if (elegidos == null) {
                elegidos = new HashSet<>();
                for (Exportador.Fichero x : ficheros) {
                    elegidos.add(x.getCentro());
                }
            }
            String centro = ficheros.get(f).getCentro();
            if (Boolean.TRUE.equals(v)) {
                elegidos.add(centro);
            } else {
                elegidos.remove(centro);
            }
            fireTableRowsUpdated(f, f);
            refrescarElegidos();
        }
    }

    private DialogoExportar(Window padre, Proyecto proyecto, Exportador.Opciones previas) {
        super(padre, "Exportar el resultado", Dialog.ModalityType.APPLICATION_MODAL);
        this.proyecto = proyecto;

        JPanel raiz = new JPanel(new BorderLayout(0, 12));
        raiz.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        long revisados = proyecto.equipos(EstadoGrupo.REVISADO);
        raiz.add(new JLabel(Estilo.envolver("Se puede generar un fichero por cada centro (Equipo_codiCentro) con las columnas "
                + "<b>Equipo_codiCentro · Equipo_idEquipo · CÓDIGO GMDN · NOMBRE GMDN y NOMBRE MANTENIMIENTO</b>. "
                + "Los equipos del catálogo de Mantenimiento no llevan código GMDN. Abajo eliges qué ficheros generar: "
                + "uno, varios o todos.<br><br>"
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

        tablaFicheros.setRowHeight(24);
        tablaFicheros.setFillsViewportHeight(true);
        tablaFicheros.getTableHeader().setReorderingAllowed(false);
        tablaFicheros.getColumnModel().getColumn(0).setMaxWidth(70);
        tablaFicheros.getColumnModel().getColumn(0).setMinWidth(62);
        tablaFicheros.getColumnModel().getColumn(1).setPreferredWidth(150);
        tablaFicheros.getColumnModel().getColumn(2).setPreferredWidth(380);
        tablaFicheros.getColumnModel().getColumn(3).setPreferredWidth(80);
        tablaFicheros.getColumnModel().getColumn(3).setMaxWidth(110);
        tablaFicheros.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco,
                                                                    int f, int c) {
                super.getTableCellRendererComponent(t, v instanceof Integer ? Estilo.numero((Integer) v) : v, sel,
                        foco, f, c);
                setHorizontalAlignment(RIGHT);
                return this;
            }
        });
        tablaFicheros.setToolTipText("Marca la casilla de los ficheros que quieras generar");
        JScrollPane sv = new JScrollPane(tablaFicheros);
        sv.setPreferredSize(new Dimension(640, 170));
        JPanel vista = new JPanel(new BorderLayout(0, 4));
        JPanel tituloVista = new JPanel(new BorderLayout(8, 0));
        tituloVista.add(Estilo.etiqueta("Ficheros que se pueden crear (marca los que quieras generar)", Font.BOLD, 12f,
                Estilo.TEXTO), BorderLayout.WEST);
        JPanel botonesTodos = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton todos = new JButton("Todos");
        JButton ninguno = new JButton("Ninguno");
        todos.setToolTipText("Marca todos los ficheros");
        ninguno.setToolTipText("Desmarca todos (después marca solo los que quieras)");
        todos.addActionListener(e -> modeloFicheros.todos(true));
        ninguno.addActionListener(e -> modeloFicheros.todos(false));
        botonesTodos.add(todos);
        botonesTodos.add(ninguno);
        tituloVista.add(botonesTodos, BorderLayout.EAST);
        vista.add(tituloVista, BorderLayout.NORTH);
        vista.add(sv, BorderLayout.CENTER);
        lblElegidos.setFont(Estilo.fuente(Font.PLAIN, 12f));
        vista.add(lblElegidos, BorderLayout.SOUTH);

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
        modeloFicheros.elegidos = previas.centros == null ? null : new HashSet<>(previas.centros);

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
        o.centros = modeloFicheros.centros();
        return o;
    }

    private void refrescarVista() {
        modeloFicheros.poner(Exportador.previsualizar(proyecto, opciones()));
    }

    /** Línea bajo la lista: cuántos ficheros y equipos se van a generar. */
    private void refrescarElegidos() {
        List<Exportador.Fichero> fs = modeloFicheros.ficheros;
        if (fs.isEmpty()) {
            lblElegidos.setForeground(Estilo.NARANJA);
            lblElegidos.setText(Estilo.envolver("No hay equipos que exportar con estas opciones."
                    + (chkSinCatalogar.isSelected() ? "" : " Todavía no se ha catalogado ningún equipo: marca «Incluir "
                    + "también los equipos sin catalogar» si quieres exportar igualmente."), 640));
            return;
        }
        int n = 0;
        int existen = 0;
        long equipos = 0;
        for (Exportador.Fichero f : fs) {
            if (modeloFicheros.elegido(f)) {
                n++;
                equipos += f.getFilas();
                if (f.getFichero().exists()) {
                    existen++;
                }
            }
        }
        lblElegidos.setForeground(n == 0 ? Estilo.NARANJA : Estilo.TEXTO);
        lblElegidos.setText(n == 0 ? "No has elegido ningún fichero: marca al menos uno."
                : "<html>Se generarán <b>" + n + "</b> de " + fs.size() + (fs.size() == 1 ? " fichero" : " ficheros")
                + " (" + Estilo.numero(equipos) + (equipos == 1 ? " equipo" : " equipos") + ")"
                + (existen > 0 ? " · " + existen + (existen == 1 ? " ya existe y se sustituirá" : " ya existen y se sustituirán")
                : "") + ".</html>");
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
        if (tablaFicheros.isEditing()) {
            tablaFicheros.getCellEditor().stopCellEditing();
        }
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
        if (Exportador.previsualizar(proyecto, o).isEmpty()) {
            JOptionPane.showMessageDialog(this, "No hay equipos que exportar con estas opciones.", "Exportar",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (Exportador.seleccionados(proyecto, o).isEmpty()) {
            JOptionPane.showMessageDialog(this, "Marca al menos un fichero en la lista (o pulsa «Todos»).",
                    "Elegir ficheros", JOptionPane.WARNING_MESSAGE);
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
