package catalogador.ui;

import catalogador.excel.Hoja;
import catalogador.util.Texto;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diálogo para importar un Excel/CSV eligiendo qué columna es cada dato.
 * Intenta adivinar las columnas por el título y enseña una vista previa.
 */
final class DialogoImportacion extends JDialog {

    /** Un dato que se quiere sacar del fichero. */
    static final class Campo {
        final String id;
        final String etiqueta;
        final boolean obligatorio;
        final String ayuda;
        final String[] claves;

        Campo(String id, String etiqueta, boolean obligatorio, String ayuda, String... claves) {
            this.id = id;
            this.etiqueta = etiqueta;
            this.obligatorio = obligatorio;
            this.ayuda = ayuda;
            this.claves = claves;
        }
    }

    private static final String NINGUNA = "(ninguna)";
    private static final int FILAS_VISTA = 60;

    private final List<Hoja> hojas;
    private final List<Campo> campos;
    private final JComboBox<String> cmbHoja = new JComboBox<>();
    private final JSpinner spFila = new JSpinner(new SpinnerNumberModel(1, 1, 1_000_000, 1));
    private final Map<String, JComboBox<String>> combos = new LinkedHashMap<>();
    private final DefaultTableModel modeloVista = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable tablaVista = new JTable(modeloVista);
    private final JLabel lblFilas = new JLabel();
    private final Set<Integer> columnasUsadas = new HashSet<>();
    private boolean aceptado;
    private boolean cargando;
    /** true si las hojas solo traen las primeras filas (ficheros grandes): no se cuentan las filas. */
    private boolean vistaPrevia;

    DialogoImportacion(Window padre, String titulo, String explicacion, File fichero, List<Hoja> hojas,
                       List<Campo> campos, JComponent extra) {
        super(padre, titulo, Dialog.ModalityType.APPLICATION_MODAL);
        this.hojas = hojas;
        this.campos = campos;

        JPanel raiz = new JPanel(new BorderLayout(0, 12));
        raiz.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        // ---- Explicación
        JPanel arriba = new JPanel(new BorderLayout(0, 4));
        JLabel lblExp = new JLabel(Estilo.envolver(explicacion, 760));
        arriba.add(lblExp, BorderLayout.NORTH);
        JLabel lblFichero = Estilo.etiqueta("Fichero: " + fichero.getName(), Font.PLAIN, 12f, Estilo.TEXTO_SUAVE);
        arriba.add(lblFichero, BorderLayout.SOUTH);
        raiz.add(arriba, BorderLayout.NORTH);

        // ---- Configuración
        JPanel config = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 0, 3, 10);
        g.anchor = GridBagConstraints.WEST;
        int fila = 0;

        for (Hoja h : hojas) {
            cmbHoja.addItem(h.getNombre() + "  (" + h.numFilas() + " filas)");
        }
        cmbHoja.setSelectedIndex(hojaProbable());
        anadirFila(config, g, fila++, "Hoja", cmbHoja, hojas.size() > 1 ? "El fichero tiene varias hojas" : "");
        anadirFila(config, g, fila++, "Fila con los títulos", spFila, "Normalmente la 1");

        for (Campo c : campos) {
            JComboBox<String> cmb = new JComboBox<>();
            combos.put(c.id, cmb);
            anadirFila(config, g, fila++, c.etiqueta + (c.obligatorio ? " *" : ""), cmb, c.ayuda);
            cmb.addActionListener(e -> {
                if (!cargando) {
                    refrescarVista();
                }
            });
        }
        if (extra != null) {
            g.gridx = 1;
            g.gridy = fila++;
            g.gridwidth = 2;
            g.insets = new Insets(8, 0, 3, 10);
            config.add(extra, g);
        }

        JPanel centro = new JPanel(new BorderLayout(0, 10));
        centro.add(config, BorderLayout.NORTH);

        tablaVista.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tablaVista.setRowHeight(22);
        tablaVista.setDefaultRenderer(Object.class, new RenderVista());
        tablaVista.getTableHeader().setReorderingAllowed(false);
        JScrollPane scroll = new JScrollPane(tablaVista);
        JPanel vista = new JPanel(new BorderLayout(0, 4));
        JPanel tituloVista = new JPanel(new BorderLayout());
        tituloVista.add(Estilo.etiqueta("Vista previa (en azul las columnas elegidas)", Font.BOLD, 12f, Estilo.TEXTO),
                BorderLayout.WEST);
        lblFilas.setForeground(Estilo.TEXTO_SUAVE);
        tituloVista.add(lblFilas, BorderLayout.EAST);
        vista.add(tituloVista, BorderLayout.NORTH);
        vista.add(scroll, BorderLayout.CENTER);
        centro.add(vista, BorderLayout.CENTER);
        raiz.add(centro, BorderLayout.CENTER);

        // ---- Botones
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancelar = new JButton("Cancelar");
        JButton importar = new JButton("Importar");
        cancelar.addActionListener(e -> dispose());
        importar.addActionListener(e -> aceptar());
        botones.add(cancelar);
        botones.add(importar);
        raiz.add(botones, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(importar);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setContentPane(raiz);

        cmbHoja.addActionListener(e -> {
            if (!cargando) {
                cargando = true;
                spFila.setValue(hoja().filaCabeceraProbable() + 1);
                cargando = false;
                recargarColumnas();
            }
        });
        spFila.addChangeListener(e -> {
            if (!cargando) {
                recargarColumnas();
            }
        });

        cargando = true;
        spFila.setValue(hoja().filaCabeceraProbable() + 1);
        cargando = false;
        recargarColumnas();

        Estilo.ajustarAPantalla(this, 900, 680, 700, 520, padre);
    }

    private static void anadirFila(JPanel p, GridBagConstraints g, int fila, String etiqueta, JComponent comp,
                                   String ayuda) {
        g.gridy = fila;
        g.gridwidth = 1;
        g.gridx = 0;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        JLabel l = new JLabel(etiqueta);
        l.setFont(Estilo.fuente(Font.BOLD, 12.5f));
        p.add(l, g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        comp.setPreferredSize(new java.awt.Dimension(320, comp.getPreferredSize().height));
        p.add(comp, g);
        g.gridx = 2;
        g.weightx = 1;
        g.fill = GridBagConstraints.NONE;
        p.add(Estilo.etiqueta(ayuda == null ? "" : ayuda, Font.PLAIN, 11.5f, Estilo.TEXTO_SUAVE), g);
    }

    private int hojaProbable() {
        for (int i = 0; i < hojas.size(); i++) {
            if (hojas.get(i).numFilas() > 1) {
                return i;
            }
        }
        return 0;
    }

    void setVistaPrevia(boolean v) {
        vistaPrevia = v;
        cargando = true;
        cmbHoja.removeAllItems();
        for (Hoja h : hojas) {
            cmbHoja.addItem(h.getNombre());
        }
        cmbHoja.setSelectedIndex(hojaProbable());
        cargando = false;
        recargarColumnas();
    }

    boolean mostrar() {
        setVisible(true);
        return aceptado;
    }

    Hoja hoja() {
        return hojas.get(Math.max(0, cmbHoja.getSelectedIndex()));
    }

    int filaCabecera() {
        return ((Number) spFila.getValue()).intValue() - 1;
    }

    /** Columna elegida para un campo, o -1 si "(ninguna)". */
    int columna(String idCampo) {
        JComboBox<String> c = combos.get(idCampo);
        return c == null ? -1 : c.getSelectedIndex() - 1;
    }

    List<String> cabecera() {
        Hoja h = hoja();
        int n = h.numColumnas();
        List<String> cab = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            String t = h.celda(filaCabecera(), c).trim();
            cab.add(t.isEmpty() ? "Columna " + Hoja.letra(c) : t);
        }
        return cab;
    }

    /** Filas de datos (debajo de la cabecera), sin las completamente vacías. */
    List<String[]> filasDatos() {
        Hoja h = hoja();
        int n = h.numColumnas();
        List<String[]> filas = new ArrayList<>();
        for (int f = filaCabecera() + 1; f < h.numFilas(); f++) {
            if (h.filaVacia(f)) {
                continue;
            }
            String[] v = new String[n];
            for (int c = 0; c < n; c++) {
                v[c] = h.celda(f, c);
            }
            filas.add(v);
        }
        return filas;
    }

    // ------------------------------------------------------------------

    private void recargarColumnas() {
        cargando = true;
        Hoja h = hoja();
        int n = h.numColumnas();
        List<String> cab = new ArrayList<>();
        String[] opciones = new String[n + 1];
        opciones[0] = NINGUNA;
        for (int c = 0; c < n; c++) {
            String t = h.celda(filaCabecera(), c).trim();
            cab.add(t);
            opciones[c + 1] = Hoja.letra(c) + " · " + (t.isEmpty() ? "(sin título)" : Texto.recortar(t, 50));
        }
        Map<String, Integer> elegidas = adivinar(campos, cab);
        for (Campo campo : campos) {
            JComboBox<String> cmb = combos.get(campo.id);
            cmb.removeAllItems();
            for (String o : opciones) {
                cmb.addItem(o);
            }
            Integer col = elegidas.get(campo.id);
            cmb.setSelectedIndex(col == null ? 0 : col + 1);
        }
        cargando = false;
        refrescarVista();
    }

    /** Asigna a cada campo la columna cuyo título más se parece a sus palabras clave. */
    static Map<String, Integer> adivinar(List<Campo> campos, List<String> cabecera) {
        Map<String, Integer> resultado = new HashMap<>();
        Set<Integer> usadas = new HashSet<>();
        for (Campo campo : campos) {
            int mejor = -1;
            int mejorPuntos = 0;
            for (int c = 0; c < cabecera.size(); c++) {
                if (usadas.contains(c)) {
                    continue;
                }
                String h = Texto.normalizar(cabecera.get(c));
                if (h.isEmpty()) {
                    continue;
                }
                String hs = h.replace(" ", "");
                for (int k = 0; k < campo.claves.length; k++) {
                    String clave = Texto.normalizar(campo.claves[k]);
                    String cs = clave.replace(" ", "");
                    int puntos = 0;
                    if (h.equals(clave) || hs.equals(cs)) {
                        puntos = 200 - k;
                    } else if (hs.startsWith(cs)) {
                        puntos = 120 - k;
                    } else if (hs.contains(cs)) {
                        puntos = 80 - k;
                    }
                    if (puntos > mejorPuntos) {
                        mejorPuntos = puntos;
                        mejor = c;
                    }
                }
            }
            if (mejor >= 0) {
                resultado.put(campo.id, mejor);
                usadas.add(mejor);
            }
        }
        return resultado;
    }

    private void refrescarVista() {
        Hoja h = hoja();
        int n = h.numColumnas();
        columnasUsadas.clear();
        Map<Integer, String> nombreCampo = new HashMap<>();
        for (Campo campo : campos) {
            int c = columna(campo.id);
            if (c >= 0) {
                columnasUsadas.add(c);
                nombreCampo.merge(c, campo.etiqueta, (a, b) -> a + " + " + b);
            }
        }
        String[] titulos = new String[n];
        for (int c = 0; c < n; c++) {
            String t = h.celda(filaCabecera(), c).trim();
            String base = Hoja.letra(c) + (t.isEmpty() ? "" : " · " + Texto.recortar(t, 30));
            titulos[c] = nombreCampo.containsKey(c) ? "→ " + nombreCampo.get(c) + "  (" + base + ")" : base;
        }
        List<Object[]> datos = new ArrayList<>();
        int total = 0;
        for (int f = filaCabecera() + 1; f < h.numFilas(); f++) {
            if (h.filaVacia(f)) {
                continue;
            }
            total++;
            if (datos.size() < FILAS_VISTA) {
                Object[] v = new Object[n];
                for (int c = 0; c < n; c++) {
                    v[c] = h.celda(f, c);
                }
                datos.add(v);
            }
        }
        modeloVista.setDataVector(datos.toArray(new Object[0][]), titulos);
        for (int c = 0; c < n; c++) {
            int ancho = columnasUsadas.contains(c) ? 220 : 130;
            tablaVista.getColumnModel().getColumn(c).setPreferredWidth(ancho);
        }
        lblFilas.setText(vistaPrevia ? "Primeras filas del fichero"
                : Estilo.numero(total) + " filas de datos" + (total > FILAS_VISTA ? " (se muestran " + FILAS_VISTA + ")" : ""));
    }

    private void aceptar() {
        for (Campo c : campos) {
            if (c.obligatorio && columna(c.id) < 0) {
                JOptionPane.showMessageDialog(this, "Elige la columna para «" + c.etiqueta + "».",
                        "Falta un dato", JOptionPane.WARNING_MESSAGE);
                combos.get(c.id).requestFocusInWindow();
                return;
            }
        }
        Set<Integer> vistas = new HashSet<>();
        for (Campo c : campos) {
            int col = columna(c.id);
            if (col >= 0 && !vistas.add(col)) {
                JOptionPane.showMessageDialog(this, "Has elegido la misma columna (" + Hoja.letra(col)
                        + ") para dos datos distintos. Revísalo.", "Columnas repetidas", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        if (filasDatos().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No hay filas de datos debajo de la fila de títulos.",
                    "Sin datos", JOptionPane.WARNING_MESSAGE);
            return;
        }
        aceptado = true;
        dispose();
    }

    private final class RenderVista extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int f, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, f, c);
            boolean usada = columnasUsadas.contains(t.convertColumnIndexToModel(c));
            if (!sel) {
                setBackground(usada ? Estilo.ACENTO_CLARO : Color.WHITE);
            }
            setFont(usada ? getFont().deriveFont(Font.BOLD) : getFont().deriveFont(Font.PLAIN));
            return this;
        }
    }
}
