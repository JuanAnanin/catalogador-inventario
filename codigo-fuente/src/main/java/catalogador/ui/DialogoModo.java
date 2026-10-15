package catalogador.ui;

import catalogador.ia.ClienteIA;
import catalogador.ia.ConfigIA;
import catalogador.ia.ProveedorIA;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Pregunta cómo se quiere trabajar: en <b>modo manual</b> o en <b>modo IA</b>.
 * En modo IA se elige el servicio —gratuitos, de pago con versión gratuita o de
 * pago—, se pega la clave API y, si se quiere, se cambia el modelo y se prueba
 * la conexión. Se abre al empezar (salvo que se desmarque) y desde el menú IA.
 */
final class DialogoModo extends JDialog {

    private static final int ANCHO_DETALLE = 390;

    private final ConfigIA cfg;
    private boolean aceptado;

    private final JRadioButton rbManual = new JRadioButton();
    private final JRadioButton rbIA = new JRadioButton();
    private final Opcion opManual;
    private final Opcion opIA;
    private final CardLayout cartas = new CardLayout();
    private final JPanel abajo = new JPanel(cartas);

    private final Map<ProveedorIA, JRadioButton> botones = new LinkedHashMap<>();
    private ProveedorIA elegido;

    private final JLabel lblNombre = Estilo.etiqueta(" ", Font.BOLD, 16f, Estilo.TEXTO);
    private final JLabel lblCategoria = Estilo.etiqueta(" ", Font.BOLD, 11f, Estilo.TEXTO_SUAVE);
    private final JLabel lblNota = new JLabel(" ");
    private final JLabel lblUrl = Estilo.etiqueta("Dirección", Font.BOLD, 12.5f, Estilo.TEXTO);
    private final JTextField txtUrl = new JTextField();
    private final JLabel lblClave = Estilo.etiqueta("Clave API", Font.BOLD, 12.5f, Estilo.TEXTO);
    private final JPasswordField txtClave = new JPasswordField();
    private final JCheckBox chkVer = new JCheckBox("Ver");
    private final JButton btnConseguir = Estilo.enlace("Conseguir una clave →");
    private final JButton btnPasos = Estilo.enlace("¿Cómo se consigue?");
    private final JLabel lblEntorno = new JLabel(" ");
    private final JComboBox<String> cmbModelo = new JComboBox<>();
    private final JButton btnModelos = new JButton("Cargar modelos");
    private final JButton btnProbar = new JButton("Probar conexión");
    private final JLabel lblPrueba = new JLabel(" ");
    private final JLabel lblPrivacidad = new JLabel(" ");
    private final JCheckBox chkPreguntar = new JCheckBox("Preguntar el modo cada vez que se abra el programa");
    private final JButton btnEmpezar = new JButton("Empezar");
    private char ocultar;

    private DialogoModo(Window padre, ConfigIA cfg, boolean alEmpezar) {
        super(padre, alEmpezar ? "¿Cómo quieres trabajar?" : "Modo de trabajo e IA", ModalityType.APPLICATION_MODAL);
        this.cfg = cfg;
        opManual = new Opcion(rbManual, "Modo manual",
                "Tú eliges el nombre oficial de cada grupo con el buscador. El programa te sugiere lo que ya "
                        + "clasificaste antes. No se envía nada fuera del ordenador.");
        opIA = new Opcion(rbIA, "Modo IA",
                "Una inteligencia artificial propone el nombre oficial de cada grupo (y la marca y el modelo limpios). "
                        + "Tú lo revisas y lo aceptas con un botón: la decisión sigue siendo tuya.");
        ButtonGroup g = new ButtonGroup();
        g.add(rbManual);
        g.add(rbIA);

        JPanel raiz = new JPanel(new BorderLayout(0, 12));
        raiz.setBackground(Estilo.FONDO);
        raiz.setBorder(Estilo.relleno(16, 18, 14, 18));

        JPanel arriba = new JPanel(new BorderLayout(0, 10));
        arriba.setOpaque(false);
        JPanel titulos = new JPanel(new GridLayout(0, 1, 0, 2));
        titulos.setOpaque(false);
        titulos.add(Estilo.etiqueta("¿Cómo quieres trabajar?", Font.BOLD, 18f, Estilo.TEXTO));
        titulos.add(Estilo.etiqueta("Puedes cambiarlo cuando quieras en el menú IA → Modo de trabajo.",
                Font.PLAIN, 12f, Estilo.TEXTO_SUAVE));
        arriba.add(titulos, BorderLayout.NORTH);
        JPanel modos = new JPanel(new GridLayout(1, 2, 12, 0));
        modos.setOpaque(false);
        modos.add(opManual);
        modos.add(opIA);
        arriba.add(modos, BorderLayout.CENTER);
        raiz.add(arriba, BorderLayout.NORTH);

        abajo.setOpaque(false);
        abajo.add(panelManual(), "manual");
        abajo.add(panelIA(), "ia");
        raiz.add(abajo, BorderLayout.CENTER);

        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        chkPreguntar.setOpaque(false);
        chkPreguntar.setSelected(cfg.isPreguntarAlEmpezar());
        pie.add(chkPreguntar, BorderLayout.WEST);
        JPanel bots = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bots.setOpaque(false);
        JButton cancelar = new JButton(alEmpezar ? "Seguir como estaba" : "Cancelar");
        cancelar.addActionListener(e -> dispose());
        btnEmpezar.setText(alEmpezar ? "Empezar" : "Aceptar");
        btnEmpezar.setFont(btnEmpezar.getFont().deriveFont(Font.BOLD));
        btnEmpezar.addActionListener(e -> aceptar());
        bots.add(cancelar);
        bots.add(btnEmpezar);
        pie.add(bots, BorderLayout.EAST);
        raiz.add(pie, BorderLayout.SOUTH);

        setContentPane(raiz);
        getRootPane().setDefaultButton(btnEmpezar);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cerrar");
        getRootPane().getActionMap().put("cerrar", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        rbManual.addActionListener(e -> elegirModo(false));
        rbIA.addActionListener(e -> elegirModo(true));
        boolean ia = cfg.getModo() == ConfigIA.Modo.IA;
        (ia ? rbIA : rbManual).setSelected(true);
        elegirProveedor(cfg.getProveedor());
        elegirModo(ia);

        Estilo.ajustarAPantalla(this, 940, 690, 860, 600, padre);
    }

    /**
     * Muestra el diálogo. Devuelve true si se ha aceptado (la configuración ya
     * está guardada en disco).
     */
    static boolean mostrar(Window padre, ConfigIA cfg, boolean alEmpezar) {
        DialogoModo d = new DialogoModo(padre, cfg, alEmpezar);
        d.setVisible(true);
        if (!d.aceptado) {
            cfg.recargar(); // se descarta lo escrito
        }
        return d.aceptado;
    }

    // ------------------------------------------------------------------ construcción

    /** Tarjeta grande con un botón de radio, un título y una explicación. */
    private static final class Opcion extends JPanel {
        private final JRadioButton rb;

        Opcion(JRadioButton rb, String titulo, String texto) {
            super(new BorderLayout(8, 4));
            this.rb = rb;
            setBackground(Color.WHITE);
            rb.setOpaque(false);
            rb.setText(titulo);
            rb.setFont(Estilo.fuente(Font.BOLD, 15f));
            add(rb, BorderLayout.NORTH);
            JLabel l = new JLabel(Estilo.envolver(texto, 275));
            l.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
            l.setForeground(Estilo.TEXTO_SUAVE);
            l.setBorder(Estilo.relleno(0, 26, 0, 0));
            add(l, BorderLayout.CENTER);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    rb.doClick();
                }
            });
            marcar(false);
        }

        void marcar(boolean si) {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(si ? Estilo.ACENTO : Estilo.BORDE, si ? 2 : 1, true),
                    si ? Estilo.relleno(11, 13, 13, 13) : Estilo.relleno(12, 14, 14, 14)));
            setBackground(si ? Estilo.ACENTO_CLARO : Color.WHITE);
        }
    }

    private JComponent panelManual() {
        JLabel l = new JLabel(Estilo.envolver("<b>Modo manual</b><br><br>"
                + "Para cada grupo de equipos: si ya clasificaste algo parecido, aparece la <b>sugerencia</b> con su "
                + "porcentaje y color (verde, naranja o rojo) y la aceptas con un botón; si no, escribes en el "
                + "<b>buscador</b> y eliges el nombre oficial con las flechas e <b>Intro</b>.<br><br>"
                + "Todo ocurre en este ordenador: no hace falta Internet ni ninguna clave.", 610));
        l.setFont(Estilo.fuente(Font.PLAIN, 13f));
        l.setForeground(Estilo.TEXTO);
        JPanel p = new JPanel(new BorderLayout());
        p.add(l, BorderLayout.NORTH);
        return Estilo.tarjeta(null, p);
    }

    private JComponent panelIA() {
        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setOpaque(false);

        // --- lista de servicios por categoría
        JPanel lista = new JPanel();
        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        lista.setBackground(Color.WHITE);
        lista.setBorder(Estilo.relleno(8, 10, 8, 10));
        ButtonGroup grupo = new ButtonGroup();
        for (ProveedorIA.Categoria c : ProveedorIA.Categoria.values()) {
            List<ProveedorIA> ps = ProveedorIA.deCategoria(c);
            if (ps.isEmpty()) {
                continue;
            }
            JLabel t = Estilo.etiqueta(c.titulo().toUpperCase(), Font.BOLD, 11f,
                    c == ProveedorIA.Categoria.GRATUITA ? Estilo.VERDE
                            : c == ProveedorIA.Categoria.CON_VERSION_GRATUITA ? Estilo.ACENTO
                            : c == ProveedorIA.Categoria.DE_PAGO ? Estilo.MORADO : Estilo.TEXTO_SUAVE);
            t.setBorder(Estilo.relleno(lista.getComponentCount() == 0 ? 2 : 12, 0, 3, 0));
            t.setToolTipText(c.explicacion());
            t.setAlignmentX(Component.LEFT_ALIGNMENT);
            lista.add(t);
            for (ProveedorIA pr : ps) {
                JRadioButton rb = new JRadioButton();
                rb.setOpaque(false);
                rb.setFont(Estilo.fuente(Font.PLAIN, 13f));
                rb.setAlignmentX(Component.LEFT_ALIGNMENT);
                rb.addActionListener(e -> elegirProveedor(pr));
                grupo.add(rb);
                botones.put(pr, rb);
                lista.add(rb);
            }
        }
        JScrollPane sl = new JScrollPane(lista);
        sl.setBorder(BorderFactory.createLineBorder(Estilo.BORDE));
        sl.getVerticalScrollBar().setUnitIncrement(16);
        sl.setPreferredSize(new Dimension(320, 100));
        sl.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel izq = new JPanel(new BorderLayout(0, 6));
        izq.setOpaque(false);
        izq.add(Estilo.tituloTarjeta("Servicio de IA"), BorderLayout.NORTH);
        izq.add(sl, BorderLayout.CENTER);
        JLabel consejo = new JLabel(Estilo.envolver("Para empezar gratis: <b>Groq</b> o <b>Google Gemini</b>. "
                + "Sin enviar datos fuera: <b>Ollama</b>. Más precisión: <b>OpenAI</b> o <b>Anthropic</b>.", 210));
        consejo.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
        consejo.setForeground(Estilo.TEXTO_SUAVE);
        izq.add(consejo, BorderLayout.SOUTH);
        p.add(izq, BorderLayout.WEST);

        // --- detalle del servicio elegido
        JPanel d = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 2, 0);
        d.add(lblCategoria, c);
        c.gridy++;
        d.add(lblNombre, c);
        c.gridy++;
        c.insets = new Insets(4, 0, 10, 0);
        lblNota.setFont(Estilo.fuente(Font.PLAIN, 12f));
        lblNota.setForeground(Estilo.TEXTO_SUAVE);
        d.add(lblNota, c);

        c.gridwidth = 1;
        c.insets = new Insets(4, 0, 4, 10);
        c.gridy++;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        d.add(lblUrl, c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        txtUrl.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
        d.add(txtUrl, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        d.add(lblClave, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        txtClave.setFont(Estilo.fuente(Font.PLAIN, 13f));
        ocultar = txtClave.getEchoChar();
        d.add(txtClave, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        chkVer.setOpaque(false);
        chkVer.addActionListener(e -> txtClave.setEchoChar(chkVer.isSelected() ? (char) 0 : ocultar));
        d.add(chkVer, c);
        c.gridy++;
        c.gridx = 1;
        c.gridwidth = 2;
        c.insets = new Insets(0, 0, 4, 0);
        JPanel filaClave = new JPanel(new BorderLayout(10, 0));
        filaClave.setOpaque(false);
        btnConseguir.setFont(Estilo.fuente(Font.PLAIN, 12f));
        btnConseguir.addActionListener(e -> abrirWeb(elegido.getUrlClave()));
        btnPasos.setFont(Estilo.fuente(Font.PLAIN, 12f));
        btnPasos.setToolTipText("Pasos para conseguir la clave (o instalar el programa)");
        btnPasos.addActionListener(e -> mostrarPasos());
        JPanel enlaces = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        enlaces.setOpaque(false);
        enlaces.add(btnConseguir);
        enlaces.add(btnPasos);
        filaClave.add(enlaces, BorderLayout.WEST);
        lblEntorno.setFont(Estilo.fuente(Font.PLAIN, 11f));
        lblEntorno.setForeground(Estilo.TEXTO_SUAVE);
        filaClave.add(lblEntorno, BorderLayout.CENTER);
        d.add(filaClave, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.insets = new Insets(6, 0, 4, 10);
        d.add(Estilo.etiqueta("Modelo", Font.BOLD, 12.5f, Estilo.TEXTO), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        cmbModelo.setEditable(true);
        cmbModelo.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
        d.add(cmbModelo, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        btnModelos.setToolTipText("Pide al servicio la lista de modelos que tiene ahora mismo");
        btnModelos.addActionListener(e -> cargarModelos());
        d.add(btnModelos, c);

        c.gridy++;
        c.gridx = 1;
        c.gridwidth = 2;
        c.insets = new Insets(12, 0, 4, 0);
        btnProbar.addActionListener(e -> probar());
        d.add(btnProbar, c);
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 0, 0, 0);
        lblPrueba.setFont(Estilo.fuente(Font.PLAIN, 12f));
        d.add(lblPrueba, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        c.weighty = 1;
        c.anchor = GridBagConstraints.SOUTHWEST;
        c.insets = new Insets(12, 0, 0, 0);
        lblPrivacidad.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
        lblPrivacidad.setForeground(Estilo.TEXTO_SUAVE);
        d.add(lblPrivacidad, c);

        p.add(Estilo.tarjeta(null, d), BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ comportamiento

    private void elegirModo(boolean ia) {
        opManual.marcar(!ia);
        opIA.marcar(ia);
        cartas.show(abajo, ia ? "ia" : "manual");
        btnEmpezar.setText(btnEmpezar.getText().startsWith("Empezar") ? (ia ? "Empezar en modo IA" : "Empezar en modo manual")
                : "Aceptar");
    }

    /** Guarda en la configuración lo escrito para el servicio que se deja. */
    private void recogerProveedor() {
        if (elegido == null) {
            return;
        }
        cfg.setClave(elegido, new String(txtClave.getPassword()));
        Object m = cmbModelo.getEditor().getItem();
        cfg.setModelo(elegido, m == null ? "" : m.toString());
        cfg.setUrl(elegido, txtUrl.getText());
    }

    private void elegirProveedor(ProveedorIA pr) {
        recogerProveedor();
        elegido = pr;
        botones.get(pr).setSelected(true);
        refrescarEtiquetas();
        lblCategoria.setText(pr.getCategoria().titulo().toUpperCase() + (pr.isLocal() ? " · EN TU ORDENADOR" : ""));
        lblNombre.setText(pr.getNombre());
        lblNota.setText(Estilo.envolver(html(pr.getNota()), ANCHO_DETALLE));
        txtUrl.setText(cfg.getUrl(pr));
        txtUrl.setEditable(pr.urlEditable());
        txtUrl.setForeground(pr.urlEditable() ? Estilo.TEXTO : Estilo.TEXTO_SUAVE);
        txtClave.setText(cfg.getClaveGuardada(pr));
        boolean clave = pr.necesitaClave() || pr.getCategoria() == ProveedorIA.Categoria.PERSONALIZADA;
        lblClave.setText(pr.necesitaClave() ? "Clave API" : "Clave API (opcional)");
        txtClave.setEnabled(clave);
        chkVer.setEnabled(clave);
        btnConseguir.setVisible(!pr.getUrlClave().isEmpty());
        btnPasos.setVisible(!pr.getPasos().isEmpty());
        btnPasos.setText(pr.isLocal() ? "¿Cómo se instala?" : pr.getCategoria() == ProveedorIA.Categoria.PERSONALIZADA
                ? "¿Qué necesito?" : "¿Cómo se consigue?");
        btnConseguir.setText(pr.isLocal() ? "Descargar " + pr.getNombreCorto() + " →"
                : pr.getCategoria() == ProveedorIA.Categoria.DE_PAGO ? "Conseguir una clave →"
                : "Conseguir una clave gratis →");
        lblEntorno.setText(cfg.claveDeEntorno(pr) ? "Se usa la de la variable " + pr.getVariableEntorno()
                : pr.isLocal() ? "No necesita clave" : " ");
        DefaultComboBoxModel<String> modelos = new DefaultComboBoxModel<>();
        for (String m : pr.getModelos()) {
            modelos.addElement(m);
        }
        String actual = cfg.getModelo(pr);
        if (!actual.isEmpty() && modelos.getIndexOf(actual) < 0) {
            modelos.insertElementAt(actual, 0);
        }
        cmbModelo.setModel(modelos);
        cmbModelo.getEditor().setItem(actual);
        lblPrueba.setText(" ");
        lblPrivacidad.setText(Estilo.envolver(pr.isLocal()
                ? "Privacidad: los datos no salen de este ordenador."
                : "Privacidad: se envía a " + html(pr.getNombreCorto()) + " solo el nombre, la marca, "
                + "el modelo, la referencia y el tipo de activo de cada grupo, y los términos candidatos de los "
                + "catálogos. Nunca el centro ni el identificador de los equipos.", ANCHO_DETALLE));
    }

    private static String html(String s) {
        return catalogador.util.Texto.html(s);
    }

    /** Los pasos para conseguir la clave del servicio elegido (o para instalarlo, si es local). */
    private void mostrarPasos() {
        StringBuilder b = new StringBuilder("<b>").append(html(elegido.getNombre())).append("</b><ol>");
        for (String paso : elegido.getPasos()) {
            b.append("<li>").append(html(paso)).append("</li>");
        }
        b.append("</ol>");
        if (!elegido.isLocal() && elegido.getVariableEntorno() != null) {
            b.append("<font color='").append(Estilo.hex(Estilo.TEXTO_SUAVE)).append("'>También se puede dejar la clave en la "
                    + "variable de entorno ").append(elegido.getVariableEntorno()).append(" y no escribirla aquí.</font>");
        }
        JLabel l = new JLabel(Estilo.envolver(b.toString(), 470));
        l.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
        String[] opciones = elegido.getUrlClave().isEmpty() ? new String[]{"Cerrar"}
                : new String[]{elegido.isLocal() ? "Abrir la página de descarga" : "Abrir la página", "Cerrar"};
        int r = JOptionPane.showOptionDialog(this, l, elegido.isLocal() ? "Cómo instalarlo" : "Cómo conseguir la clave",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opciones, opciones[0]);
        if (r == 0 && opciones.length > 1) {
            abrirWeb(elegido.getUrlClave());
        }
    }

    /** Pone «✓» junto a los servicios que ya tienen clave (o no la necesitan). */
    private void refrescarEtiquetas() {
        for (Map.Entry<ProveedorIA, JRadioButton> e : botones.entrySet()) {
            ProveedorIA pr = e.getKey();
            String tag = pr.isLocal() ? ""
                    : pr.getCategoria() == ProveedorIA.Categoria.GRATUITA ? "gratis"
                    : pr.getCategoria() == ProveedorIA.Categoria.CON_VERSION_GRATUITA ? "nivel gratuito"
                    : pr.getCategoria() == ProveedorIA.Categoria.DE_PAGO ? "de pago" : "";
            boolean listo = pr.necesitaClave() && !cfg.getClave(pr).isEmpty();
            e.getValue().setText("<html>" + html(pr.getNombre()) + (tag.isEmpty() ? "" : " <font color='"
                    + Estilo.hex(Estilo.TEXTO_TENUE) + "' size='-1'>" + tag + "</font>")
                    + (listo ? " <font color='" + Estilo.hex(Estilo.VERDE) + "'>✓</font>" : "") + "</html>");
        }
    }

    private ClienteIA clienteActual() {
        recogerProveedor();
        return ClienteIA.desde(cfg, elegido);
    }

    private void cargarModelos() {
        ClienteIA cli = clienteActual();
        String antes = String.valueOf(cmbModelo.getEditor().getItem());
        ponerPrueba("Pidiendo la lista de modelos…", Estilo.TEXTO_SUAVE);
        btnModelos.setEnabled(false);
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return cli.modelos();
            }

            @Override
            protected void done() {
                btnModelos.setEnabled(true);
                try {
                    List<String> l = get();
                    DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
                    for (String s : l) {
                        m.addElement(s);
                    }
                    cmbModelo.setModel(m);
                    cmbModelo.getEditor().setItem(l.contains(antes) || l.isEmpty() ? antes : l.get(0));
                    ponerPrueba(l.isEmpty() ? (elegido.getProtocolo() == ProveedorIA.Protocolo.OLLAMA
                            ? "Ollama funciona, pero no tiene ningún modelo descargado. Abre una terminal y ejecuta, por "
                            + "ejemplo:  ollama pull " + elegido.getModeloPorDefecto()
                            : "El servicio no ha devuelto ningún modelo.")
                            : l.size() + (l.size() == 1 ? " modelo disponible." : " modelos disponibles. Elige uno en la lista."),
                            l.isEmpty() ? Estilo.NARANJA : Estilo.VERDE);
                    if (!l.isEmpty()) {
                        cmbModelo.showPopup();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    ponerPrueba(e.getCause().getMessage(), Estilo.ROJO);
                }
            }
        }.execute();
    }

    private void probar() {
        ClienteIA cli = clienteActual();
        if (elegido.necesitaClave() && cfg.getClave(elegido).isEmpty()) {
            ponerPrueba("Falta la clave API.", Estilo.ROJO);
            txtClave.requestFocusInWindow();
            return;
        }
        ponerPrueba("Probando " + cli.descripcion() + "…", Estilo.TEXTO_SUAVE);
        btnProbar.setEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return cli.probar();
            }

            @Override
            protected void done() {
                btnProbar.setEnabled(true);
                try {
                    ponerPrueba("✔ " + get(), Estilo.VERDE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    ponerPrueba("✘ " + e.getCause().getMessage(), Estilo.ROJO);
                }
            }
        }.execute();
    }

    private void ponerPrueba(String texto, Color color) {
        lblPrueba.setForeground(color);
        lblPrueba.setText(Estilo.envolver(html(texto).replace("\n", "<br>"), ANCHO_DETALLE));
        refrescarEtiquetas();
    }

    private void abrirWeb(String url) {
        if (!catalogador.util.Ficheros.navegar(url)) {
            JOptionPane.showMessageDialog(this, "Abre esta dirección en el navegador:\n" + url, "Conseguir una clave",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void aceptar() {
        recogerProveedor();
        boolean ia = rbIA.isSelected();
        if (ia && !cfg.estaListo(elegido)) {
            String falta = elegido.necesitaClave() && cfg.getClave(elegido).isEmpty() ? "la clave API"
                    : cfg.getModelo(elegido).isEmpty() ? "el modelo" : "una dirección válida";
            ponerPrueba("Falta " + falta + " para usar " + elegido.getNombre() + ".", Estilo.ROJO);
            java.awt.Toolkit.getDefaultToolkit().beep();
            return;
        }
        cfg.setModo(ia ? ConfigIA.Modo.IA : ConfigIA.Modo.MANUAL);
        if (ia) {
            cfg.setProveedor(elegido);
        }
        cfg.setPreguntarAlEmpezar(chkPreguntar.isSelected());
        try {
            cfg.guardar();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar la configuración:\n" + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        aceptado = true;
        dispose();
    }

    /** Para las capturas del manual: elegir el modo y el servicio sin ratón. */
    void prepararCaptura(boolean ia, String idProveedor, String clave) {
        (ia ? rbIA : rbManual).doClick();
        ProveedorIA pr = ProveedorIA.porId(idProveedor);
        if (pr != null) {
            elegirProveedor(pr);
            if (clave != null) {
                txtClave.setText(clave);
            }
        }
    }

    static DialogoModo paraCaptura(Window padre, ConfigIA cfg) {
        return new DialogoModo(padre, cfg, true);
    }

    JButton botonProbar() {
        return btnProbar;
    }

    JComponent cajaModelos() {
        return cmbModelo;
    }
}
