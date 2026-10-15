package catalogador.ui;

import catalogador.logica.Catalogos;
import catalogador.modelo.TerminoOficial;
import catalogador.modelo.TerminoOficial.Nivel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Crear (o corregir) una entrada del catálogo de Mantenimiento: el nombre y
 * su clasificación en cinco niveles. En cada nivel se elige una opción que ya
 * existe o se crea una nueva («+ Nuevo…»), con el código que le
 * correspondería ya propuesto.
 */
final class DialogoEntradaMantenimiento extends JDialog {

    private static final int NIVELES = TerminoOficial.NIVELES.length;
    /** Los tres primeros niveles (tipo de activo, uso y familia) son obligatorios. */
    private static final int OBLIGATORIOS = 3;

    /** Opción de un desplegable de nivel. */
    private static final class Opcion {
        static final int ELIGE = 0;
        static final int EXISTENTE = 1;
        static final int NUEVO = 2;
        static final int VACIO = 3;

        final int tipo;
        final Nivel nivel;

        Opcion(int tipo, Nivel nivel) {
            this.tipo = tipo;
            this.nivel = nivel;
        }

        @Override
        public String toString() {
            switch (tipo) {
                case ELIGE: return "— elige —";
                case NUEVO: return "+ Nuevo…";
                case VACIO: return "(ninguno)";
                default: return nivel.getNombre() + "   ·   " + nivel.getCodigo();
            }
        }
    }

    private final Catalogos catalogos;
    private final TerminoOficial editando;
    private final JTextField txtNombre = new JTextField(34);
    @SuppressWarnings("unchecked")
    private final JComboBox<Opcion>[] combos = new JComboBox[NIVELES];
    private final JTextField[] txtCodigo = new JTextField[NIVELES];
    private final JTextField[] txtNombreNivel = new JTextField[NIVELES];
    private final JPanel[] panelNuevo = new JPanel[NIVELES];
    private final boolean[] codigoTocado = new boolean[NIVELES];
    private final JLabel lblResumen = new JLabel(" ");
    private final JLabel lblAviso = new JLabel(" ");
    private boolean cargando;
    private TerminoOficial resultado;

    private DialogoEntradaMantenimiento(Window padre, Catalogos catalogos, TerminoOficial editando, String nombre) {
        super(padre, editando == null ? "Nueva entrada de mantenimiento" : "Corregir entrada de mantenimiento",
                Dialog.ModalityType.APPLICATION_MODAL);
        this.catalogos = catalogos;
        this.editando = editando;

        JPanel raiz = new JPanel(new BorderLayout(0, 12));
        raiz.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        String explicacion = editando == null
                ? "La entrada se añade al catálogo de Mantenimiento del programa y se incluye al exportarlo. "
                + "En cada nivel elige una opción que ya existe o crea una nueva: el código se propone solo."
                : "Puedes cambiar la clasificación. El nombre no se puede cambiar porque es lo que identifica "
                + "la entrada en las catalogaciones ya hechas.";
        raiz.add(new JLabel(Estilo.envolver(explicacion, 600)), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 0, 4, 10);
        int fila = 0;

        g.gridy = fila++;
        g.gridx = 0;
        form.add(etiqueta("Nombre de mantenimiento *"), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        txtNombre.setFont(Estilo.fuente(Font.BOLD, 14f));
        txtNombre.setText(editando != null ? editando.getNombre() : nombre == null ? "" : nombre.trim().toUpperCase());
        txtNombre.setEditable(editando == null);
        form.add(txtNombre, g);
        g.fill = GridBagConstraints.NONE;
        g.weightx = 0;
        g.gridy = fila++;
        g.gridx = 1;
        g.insets = new Insets(0, 0, 10, 10);
        form.add(Estilo.etiqueta("Debe ser único: es el nombre que aparecerá en el resultado.", Font.PLAIN, 11.5f,
                Estilo.TEXTO_SUAVE), g);
        g.insets = new Insets(4, 0, 4, 10);

        for (int i = 0; i < NIVELES; i++) {
            final int nivel = i;
            combos[i] = new JComboBox<>();
            combos[i].setPreferredSize(new Dimension(430, combos[i].getPreferredSize().height));
            combos[i].setMaximumRowCount(18);
            txtCodigo[i] = new JTextField(9);
            txtNombreNivel[i] = new JTextField(26);
            panelNuevo[i] = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            panelNuevo[i].add(Estilo.etiqueta("Código", Font.PLAIN, 12f, Estilo.TEXTO_SUAVE));
            panelNuevo[i].add(txtCodigo[i]);
            panelNuevo[i].add(Estilo.etiqueta("Nombre", Font.PLAIN, 12f, Estilo.TEXTO_SUAVE));
            panelNuevo[i].add(txtNombreNivel[i]);
            panelNuevo[i].setVisible(false);

            g.gridy = fila++;
            g.gridx = 0;
            form.add(etiqueta(TerminoOficial.NIVELES[i] + (i < OBLIGATORIOS ? " *" : "")), g);
            g.gridx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            form.add(combos[i], g);
            g.gridy = fila++;
            g.insets = new Insets(0, 0, 4, 10);
            form.add(panelNuevo[i], g);
            g.insets = new Insets(4, 0, 4, 10);
            g.fill = GridBagConstraints.NONE;

            combos[i].addActionListener(e -> {
                if (!cargando) {
                    alCambiarNivel(nivel);
                }
            });
            txtCodigo[i].getDocument().addDocumentListener(alCambiar(() -> {
                if (!cargando) {
                    codigoTocado[nivel] = true;
                    proponerCodigosDesde(nivel + 1);
                    refrescarResumen();
                }
            }));
            txtNombreNivel[i].getDocument().addDocumentListener(alCambiar(() -> {
                if (!cargando) {
                    refrescarResumen();
                }
            }));
        }
        JPanel centro = new JPanel(new BorderLayout(0, 8));
        centro.add(form, BorderLayout.NORTH);
        lblResumen.setFont(Estilo.fuente(Font.PLAIN, 12f));
        lblResumen.setForeground(Estilo.TEXTO_SUAVE);
        lblAviso.setFont(Estilo.fuente(Font.BOLD, 12f));
        lblAviso.setForeground(Estilo.ROJO);
        JPanel textos = new JPanel(new BorderLayout(0, 4));
        textos.add(lblResumen, BorderLayout.NORTH);
        textos.add(lblAviso, BorderLayout.SOUTH);
        centro.add(textos, BorderLayout.CENTER);
        raiz.add(centro, BorderLayout.CENTER);

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancelar = new JButton("Cancelar");
        JButton aceptar = new JButton(editando == null ? "Crear entrada" : "Guardar cambios");
        aceptar.setFont(aceptar.getFont().deriveFont(Font.BOLD));
        cancelar.addActionListener(e -> dispose());
        aceptar.addActionListener(e -> aceptar());
        botones.add(cancelar);
        botones.add(aceptar);
        raiz.add(botones, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(aceptar);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setContentPane(raiz);

        cargando = true;
        rellenar(0);
        if (editando != null) {
            for (int i = 0; i < NIVELES; i++) {
                seleccionarExistente(i, editando.nivel(i));
                rellenar(i + 1);
            }
        }
        cargando = false;
        actualizarPaneles();
        refrescarResumen();

        pack();
        Estilo.ajustarAPantalla(this, Math.max(getWidth(), 720), getHeight(), Math.max(getWidth(), 720), getHeight(), padre);
        SwingUtilities.invokeLater(() -> (editando == null ? txtNombre : combos[0]).requestFocusInWindow());
    }

    /** Pide los datos de una entrada nueva. Devuelve la entrada creada, o null si se cancela. */
    static TerminoOficial crear(Window padre, Catalogos catalogos, String nombreSugerido) {
        DialogoEntradaMantenimiento d = new DialogoEntradaMantenimiento(padre, catalogos, null, nombreSugerido);
        d.setVisible(true);
        return d.resultado;
    }

    /** Corrige la clasificación de una entrada. Devuelve la entrada nueva, o null si se cancela. */
    static TerminoOficial modificar(Window padre, Catalogos catalogos, TerminoOficial t) {
        DialogoEntradaMantenimiento d = new DialogoEntradaMantenimiento(padre, catalogos, t, null);
        d.setVisible(true);
        return d.resultado;
    }

    private static JLabel etiqueta(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(Estilo.fuente(Font.BOLD, 12.5f));
        return l;
    }

    // ------------------------------------------------------------------ niveles

    private Opcion elegida(int i) {
        return i < 0 || i >= NIVELES ? null : (Opcion) combos[i].getSelectedItem();
    }

    /** Código del nivel i tal y como está elegido o escrito ("" si no hay). */
    private String codigo(int i) {
        Opcion o = elegida(i);
        if (o == null) {
            return "";
        }
        if (o.tipo == Opcion.EXISTENTE) {
            return o.nivel.getCodigo();
        }
        return o.tipo == Opcion.NUEVO ? txtCodigo[i].getText().trim() : "";
    }

    private String nombreNivel(int i) {
        Opcion o = elegida(i);
        if (o == null) {
            return "";
        }
        if (o.tipo == Opcion.EXISTENTE) {
            return o.nivel.getNombre();
        }
        return o.tipo == Opcion.NUEVO ? txtNombreNivel[i].getText().trim() : "";
    }

    /** Rellena el desplegable del nivel i según lo elegido en el nivel anterior. */
    private void rellenar(int i) {
        if (i >= NIVELES) {
            return;
        }
        JComboBox<Opcion> c = combos[i];
        c.removeAllItems();
        Opcion padre = elegida(i - 1);
        boolean sinPadre = i > 0 && (padre == null || padre.tipo == Opcion.ELIGE);
        boolean padreVacio = i > 0 && padre != null && padre.tipo == Opcion.VACIO;
        if (padreVacio) {
            c.addItem(new Opcion(Opcion.VACIO, null));
            c.setEnabled(false);
        } else {
            c.setEnabled(!sinPadre);
            c.addItem(new Opcion(i < OBLIGATORIOS ? Opcion.ELIGE : Opcion.VACIO, null));
            if (!sinPadre && (i == 0 || padre.tipo == Opcion.EXISTENTE)) {
                for (Nivel n : catalogos.opcionesNivel(i, i == 0 ? "" : padre.nivel.getCodigo())) {
                    c.addItem(new Opcion(Opcion.EXISTENTE, n));
                }
            }
            if (!sinPadre) {
                c.addItem(new Opcion(Opcion.NUEVO, null));
            }
        }
        c.setSelectedIndex(0);
        txtCodigo[i].setText("");
        txtNombreNivel[i].setText("");
        codigoTocado[i] = false;
    }

    private void seleccionarExistente(int i, Nivel n) {
        if (n.vacio()) {
            return; // se queda «(ninguno)»
        }
        JComboBox<Opcion> c = combos[i];
        for (int k = 0; k < c.getItemCount(); k++) {
            Opcion o = c.getItemAt(k);
            if (o.tipo == Opcion.EXISTENTE && o.nivel.equals(n)) {
                c.setSelectedIndex(k);
                return;
            }
        }
        Opcion o = new Opcion(Opcion.EXISTENTE, n); // por si el dato no encaja en la jerarquía
        c.insertItemAt(o, 1);
        c.setSelectedItem(o);
    }

    private void alCambiarNivel(int i) {
        cargando = true;
        Opcion o = elegida(i);
        if (o != null && o.tipo == Opcion.NUEVO) {
            codigoTocado[i] = false;
            txtCodigo[i].setText(catalogos.siguienteCodigo(i, codigo(i - 1)));
        }
        for (int k = i + 1; k < NIVELES; k++) {
            rellenar(k);
        }
        cargando = false;
        actualizarPaneles();
        refrescarResumen();
        if (o != null && o.tipo == Opcion.NUEVO) {
            txtNombreNivel[i].requestFocusInWindow();
        }
    }

    /** Si el código de un nivel nuevo cambia, se recalculan los de sus niveles nuevos (no tocados a mano). */
    private void proponerCodigosDesde(int desde) {
        boolean antes = cargando;
        cargando = true;
        for (int k = desde; k < NIVELES; k++) {
            Opcion o = elegida(k);
            if (o != null && o.tipo == Opcion.NUEVO && !codigoTocado[k]) {
                txtCodigo[k].setText(catalogos.siguienteCodigo(k, codigo(k - 1)));
            }
        }
        cargando = antes;
    }

    private void actualizarPaneles() {
        for (int i = 0; i < NIVELES; i++) {
            Opcion o = elegida(i);
            panelNuevo[i].setVisible(o != null && o.tipo == Opcion.NUEVO);
        }
        if (isShowing()) {
            pack();
        }
    }

    private void refrescarResumen() {
        List<String> partes = new ArrayList<>();
        for (int i = 0; i < NIVELES; i++) {
            String n = nombreNivel(i);
            if (!n.isEmpty()) {
                partes.add(n);
            }
        }
        lblResumen.setText(Estilo.envolver("Clasificación: " + (partes.isEmpty() ? "(sin elegir)"
                : catalogador.util.Texto.html(String.join(" › ", partes))), 640));
        lblAviso.setText(" ");
    }

    // ------------------------------------------------------------------ aceptar

    private void aceptar() {
        String nombre = txtNombre.getText().trim();
        if (nombre.isEmpty()) {
            aviso("Escribe el nombre de mantenimiento.", txtNombre);
            return;
        }
        if (editando == null && catalogos.existeNombreMantenimiento(nombre)) {
            aviso("Ya existe una entrada llamada «" + nombre + "». Búscala en el catálogo.", txtNombre);
            return;
        }
        String[] once = new String[1 + 2 * NIVELES];
        once[0] = nombre;
        for (int i = 0; i < NIVELES; i++) {
            Opcion o = elegida(i);
            if (o == null || o.tipo == Opcion.ELIGE) {
                aviso("Elige el nivel «" + TerminoOficial.NIVELES[i] + "».", combos[i]);
                return;
            }
            String cod = codigo(i);
            String nom = nombreNivel(i);
            if (o.tipo == Opcion.NUEVO) {
                if (cod.isEmpty()) {
                    aviso("Escribe el código del nuevo «" + TerminoOficial.NIVELES[i] + "».", txtCodigo[i]);
                    return;
                }
                if (nom.isEmpty()) {
                    aviso("Escribe el nombre del nuevo «" + TerminoOficial.NIVELES[i] + "».", txtNombreNivel[i]);
                    return;
                }
                String ya = catalogos.nombreDeCodigo(i, cod);
                if (ya != null && !ya.equalsIgnoreCase(nom)) {
                    aviso("El código " + cod + " ya se usa para «" + ya + "». Elige otro código.", txtCodigo[i]);
                    return;
                }
                String padre = codigo(i - 1);
                if (i >= 2 && !padre.isEmpty() && !cod.startsWith(padre)) {
                    int r = JOptionPane.showConfirmDialog(this, "El código " + cod + " no empieza por el del nivel "
                                    + "anterior (" + padre + "), como el resto del catálogo.\n¿Lo dejas así?",
                            "Código", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (r != JOptionPane.YES_OPTION) {
                        txtCodigo[i].requestFocusInWindow();
                        return;
                    }
                }
            }
            once[1 + 2 * i] = cod;
            once[2 + 2 * i] = nom;
        }
        try {
            resultado = editando == null ? catalogos.anadirMantenimiento(once)
                    : catalogos.modificarMantenimiento(editando, once);
            dispose();
        } catch (IllegalArgumentException e) {
            aviso(e.getMessage(), txtNombre);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar el catálogo:\n" + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void aviso(String texto, JComponent foco) {
        java.awt.Toolkit.getDefaultToolkit().beep();
        lblAviso.setText(texto);
        foco.requestFocusInWindow();
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
