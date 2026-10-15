package catalogador.ui;

import catalogador.excel.LectorTablas;
import catalogador.ia.AsistenteIA;
import catalogador.ia.ClienteIA;
import catalogador.ia.ConfigIA;
import catalogador.ia.PropuestaIA;
import catalogador.ia.PropuestasGuardadas;
import catalogador.logica.Almacen;
import catalogador.logica.Catalogos;
import catalogador.logica.Exportador;
import catalogador.logica.ExportadorCatalogo;
import catalogador.logica.ImportadorCatalogos;
import catalogador.logica.ImportadorInventario;
import catalogador.logica.ImportadorResultados;
import catalogador.logica.Memoria;
import catalogador.logica.Sugerencia;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TerminoOficial;
import catalogador.modelo.TipoLista;
import catalogador.modelo.Variantes;
import catalogador.util.Texto;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ventana de trabajo: cola de grupos de equipos a la izquierda; a la derecha
 * el equipo que toca, la sugerencia automática, el corrector de marca/modelo
 * y el buscador en los catálogos oficiales (GMDN o Mantenimiento).
 */
public final class VentanaPrincipal extends JFrame {

    /** Versión del programa (se muestra en «Acerca de»). */
    public static final String VERSION = "3.1";
    private static final int MAX_RESULTADOS = 300;
    private static final int ANCHO_COLUMNA_IZQ = 400;
    private static final String[] FILTROS_COLA = {"Pendientes", "Pospuestos (dudas)", "Revisados", "Todos"};
    private static final int FILTRO_PENDIENTES = 0;
    private static final int FILTRO_POSPUESTOS = 1;
    private static final int FILTRO_REVISADOS = 2;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Almacen almacen;
    private final Catalogos catalogos;
    private final Memoria memoria;
    private final Properties config;
    private final ConfigIA configIA;

    // ---- modo IA
    private AsistenteIA asistente;               // null en modo manual
    private PropuestasGuardadas propuestas;      // las del proyecto abierto
    private final Set<String> pidiendoIA = ConcurrentHashMap.newKeySet();
    private final Map<String, String> erroresIA = new ConcurrentHashMap<>();
    private volatile String pausaIA;             // motivo por el que la IA está parada (clave, cuota…)
    private final ExecutorService hiloIA = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ia");
        t.setDaemon(true);
        return t;
    });
    private final JButton btnModo = new JButton(" ");
    private final JLabel lblIA = new JLabel(" ");
    private JLabel lblTituloSug;
    private final JLabel lblSinSug = new JLabel(" ");
    private final JButton btnMarcaIA = Estilo.enlace(" ");
    private PropuestaIA propuestaActual;
    private final List<JMenuItem> accionesIA = new ArrayList<>();

    private Proyecto proyecto;
    private Grupo actual;
    private final Deque<Grupo> historial = new ArrayDeque<>();
    private List<Sugerencia> sugerencias = Collections.emptyList();

    // ---- cabecera
    private final JLabel lblProyecto = new JLabel(" ");
    private final JProgressBar barraProgreso = new JProgressBar(0, 1000);
    private final JLabel lblProgreso = new JLabel(" ");
    private final JLabel lblListas = new JLabel(" ");

    // ---- zona central
    private final CardLayout cartas = new CardLayout();
    private final JPanel centro = new JPanel(cartas);
    private PanelBienvenida bienvenida;
    private JPanel panelCola;

    // ---- cola de trabajo
    private final JComboBox<String> cmbFiltroCola = new JComboBox<>(FILTROS_COLA);
    private final CampoTexto txtFiltroCola = new CampoTexto("Filtrar por nombre, marca…");
    private final Tablas.ModeloCola modeloCola = new Tablas.ModeloCola();
    private final JTable tablaCola = new JTable(modeloCola);
    private final JLabel lblCola = new JLabel(" ");
    private boolean actualizandoCola;

    // ---- aparato
    private final JLabel lblPosicion = new JLabel(" ");
    private final Chip chipEstado = new Chip();
    private final Chip chipElectro = new Chip();
    private final JLabel lblNombre = new JLabel(" ");
    private final JLabel valUnidades = new JLabel("—");
    private final JLabel valMarca = new JLabel("—");
    private final JLabel valModelo = new JLabel("—");
    private final JLabel valReferencia = new JLabel("—");
    private final JLabel lblVariantes = new JLabel(" ");
    private final JLabel lblAsignado = new JLabel(" ");

    // ---- sugerencia
    private final CardLayout cartasSug = new CardLayout();
    private final JPanel panelSug = new JPanel(cartasSug);
    private final InsigniaSimilitud insignia = new InsigniaSimilitud();
    private final JLabel lblNivel = new JLabel(" ", JLabel.CENTER);
    private final JLabel lblSugTermino = new JLabel(" ");
    private final JLabel lblSugBase = new JLabel(" ");
    private final JLabel lblSugDescripcion = new JLabel(" ");
    private final JButton btnAceptarSug = new JButton("Aceptar sugerencia");
    private final JButton[] btnAlternativas = {Estilo.enlace(""), Estilo.enlace("")};
    private final JLabel lblOtras = Estilo.etiqueta("Otras posibilidades:", Font.PLAIN, 11.5f, Estilo.TEXTO_SUAVE);

    // ---- buscador
    private final CampoTexto txtBuscar = new CampoTexto("Escribe para buscar: nombre, código o palabras de la descripción");
    private final JToggleButton btnGmdn = new JToggleButton("GMDN", true);
    private final JToggleButton btnMant = new JToggleButton("Mantenimiento");
    private final JButton btnNuevaEntrada = Estilo.enlace("+ Nueva entrada…");
    private TipoLista listaBuscador = TipoLista.GMDN;
    private final JCheckBox chkDescripcion = new JCheckBox("Buscar también en la descripción", true);
    private final JLabel lblSinCatalogo = new JLabel(" ");
    private final Tablas.ModeloResultados modeloResultados = new Tablas.ModeloResultados();
    private final JTable tablaResultados = new JTable(modeloResultados);
    private final JLabel lblResultados = new JLabel(" ");
    private final CardLayout cartasResultados = new CardLayout();
    private final JPanel panelResultados = new JPanel(cartasResultados);
    private final JLabel lblDescTitulo = new JLabel(" ");
    private final JTextArea txtDescripcion = new JTextArea();

    // ---- corrector
    private final JTextField txtMarca = new JTextField();
    private final JTextField txtModelo = new JTextField();
    private final JLabel lblMarcaOriginal = new JLabel(" ");
    private final JLabel lblModeloOriginal = new JLabel(" ");

    // ---- acciones
    private final JLabel lblSeleccion = new JLabel(" ");
    private final JButton btnGuardar = new JButton("Guardar  ↵");
    private final JButton btnPosponer = new JButton("Posponer (duda)  " + Estilo.atajo("P"));
    private final JButton btnAnterior = new JButton("◀ Anterior");
    private final Timer temporizadorAviso;

    // ---- barra de estado
    private final JLabel lblEstadoBarra = new JLabel(" ");

    // ---- última exportación (se recuerdan las opciones)
    private Exportador.Opciones opcionesExportar;

    // ---- búsqueda en segundo plano
    private final ExecutorService hiloBusqueda = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "buscador");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger generacion = new AtomicInteger();
    private final Timer retardoBusqueda;
    private boolean cambioProgramatico;
    private boolean busquedaTecleada;
    /** La última búsqueda no encontró nada (se explica qué hacer en el panel de descripción). */
    private boolean sinCoincidencias;

    public VentanaPrincipal(Almacen almacen) {
        super("Catalogador");
        this.almacen = almacen;
        this.catalogos = new Catalogos(almacen.carpetaCatalogos());
        this.memoria = new Memoria(almacen.getBase());
        this.config = almacen.leerConfig();
        this.configIA = new ConfigIA(almacen.getBase());

        retardoBusqueda = new Timer(110, e -> lanzarBusqueda());
        retardoBusqueda.setRepeats(false);
        temporizadorAviso = new Timer(3500, e -> actualizarSeleccion());
        temporizadorAviso.setRepeats(false);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                salir();
            }
        });
        setIconImage(Estilo.icono(64));
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(Estilo.icono(256));
            }
        } catch (RuntimeException e) {
            // no todos los sistemas lo permiten
        }

        construir();

        // macOS: «Salir» y «Acerca de» del menú de la aplicación hacen lo mismo que los del menú propio
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop escritorio = java.awt.Desktop.getDesktop();
                if (escritorio.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
                    escritorio.setQuitHandler((ev, respuesta) -> {
                        respuesta.cancelQuit();
                        SwingUtilities.invokeLater(this::salir);
                    });
                }
                if (escritorio.isSupported(java.awt.Desktop.Action.APP_ABOUT)) {
                    escritorio.setAboutHandler(ev -> SwingUtilities.invokeLater(this::acercaDe));
                }
            }
        } catch (RuntimeException e) {
            // no todos los sistemas lo permiten
        }

        // Zona útil de la pantalla (sin la barra de tareas): en portátiles con la escala de Windows al
        // 125-150 % puede ser de solo ~1.100 × 600 puntos, y la ventana tiene que caber entera.
        java.awt.Rectangle util;
        try {
            util = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        } catch (RuntimeException e) {
            Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
            util = new java.awt.Rectangle(0, 0, d.width, d.height);
        }
        setMinimumSize(new Dimension(Math.min(1080, util.width), Math.min(680, util.height)));
        setSize(Math.min(1500, (int) (util.width * 0.94)), Math.min(940, (int) (util.height * 0.94)));
        setLocationRelativeTo(null);
        if (util.width < 1200 || util.height < 760) {
            setExtendedState(getExtendedState() | MAXIMIZED_BOTH); // pantalla pequeña: aprovecharla entera
        }
    }

    // =====================================================================
    // Construcción de la interfaz
    // =====================================================================

    private void construir() {
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.setBackground(Estilo.FONDO);
        raiz.add(construirCabecera(), BorderLayout.NORTH);

        JPanel cuerpo = new JPanel(new BorderLayout(12, 0));
        cuerpo.setOpaque(false);
        cuerpo.setBorder(Estilo.relleno(12, 12, 8, 12));
        panelCola = construirCola();
        panelCola.setVisible(false);
        cuerpo.add(panelCola, BorderLayout.WEST);

        centro.setOpaque(false);
        bienvenida = new PanelBienvenida(this::importarCatalogos, this::importarInventario,
                this::elegirProyecto, almacen.getBase().toString());
        centro.add(bienvenida, "bienvenida");
        centro.add(construirTrabajo(), "trabajo");
        cuerpo.add(centro, BorderLayout.CENTER);
        raiz.add(cuerpo, BorderLayout.CENTER);

        JPanel estado = new JPanel(new BorderLayout());
        estado.setOpaque(false);
        estado.setBorder(Estilo.relleno(0, 16, 6, 16));
        lblEstadoBarra.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
        lblEstadoBarra.setForeground(Estilo.TEXTO_SUAVE);
        estado.add(lblEstadoBarra, BorderLayout.WEST);
        raiz.add(estado, BorderLayout.SOUTH);

        setContentPane(raiz);
        setJMenuBar(construirMenu());
        configurarAtajos();
        cartas.show(centro, "bienvenida");
    }

    private JPanel construirCabecera() {
        JPanel p = new JPanel(new BorderLayout(16, 0));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Estilo.BORDE), Estilo.relleno(10, 16, 10, 18)));

        JPanel izq = new JPanel(new BorderLayout(10, 0));
        izq.setOpaque(false);
        izq.add(new JLabel(new ImageIcon(Estilo.icono(34))), BorderLayout.WEST);
        JPanel textos = new JPanel(new BorderLayout());
        textos.setOpaque(false);
        textos.add(Estilo.etiqueta("Catalogador de inventario", Font.BOLD, 17f, Estilo.TEXTO), BorderLayout.NORTH);
        lblProyecto.setFont(Estilo.fuente(Font.PLAIN, 12f));
        lblProyecto.setForeground(Estilo.TEXTO_SUAVE);
        textos.add(lblProyecto, BorderLayout.SOUTH);
        izq.add(textos, BorderLayout.CENTER);
        p.add(izq, BorderLayout.WEST);

        JPanel der = new JPanel();
        der.setOpaque(false);
        der.setLayout(new BoxLayout(der, BoxLayout.Y_AXIS));
        lblProgreso.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
        lblProgreso.setForeground(Estilo.TEXTO);
        lblProgreso.setAlignmentX(Component.RIGHT_ALIGNMENT);
        barraProgreso.setAlignmentX(Component.RIGHT_ALIGNMENT);
        barraProgreso.setPreferredSize(new Dimension(300, 10));
        barraProgreso.setMaximumSize(new Dimension(300, 10));
        barraProgreso.setVisible(false);
        lblListas.setFont(Estilo.fuente(Font.PLAIN, 11f));
        lblListas.setForeground(Estilo.TEXTO_SUAVE);
        lblListas.setAlignmentX(Component.RIGHT_ALIGNMENT);
        der.add(lblProgreso);
        der.add(Box.createVerticalStrut(4));
        der.add(barraProgreso);
        der.add(Box.createVerticalStrut(4));
        der.add(lblListas);
        p.add(der, BorderLayout.EAST);

        // Modo de trabajo (manual o IA): siempre a la vista y se cambia con un clic
        JPanel medio = new JPanel(new GridBagLayout());
        medio.setOpaque(false);
        btnModo.setFont(Estilo.fuente(Font.BOLD, 12f));
        btnModo.setFocusable(false);
        btnModo.setToolTipText("Cambiar entre modo manual y modo IA, o el servicio de IA");
        btnModo.addActionListener(e -> abrirModo());
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.anchor = GridBagConstraints.EAST;
        medio.add(btnModo, c);
        p.add(medio, BorderLayout.CENTER);
        return p;
    }

    private JPanel construirCola() {
        JPanel c = new JPanel(new BorderLayout(0, 8));
        JPanel arriba = new JPanel(new BorderLayout(0, 6));
        arriba.setOpaque(false);
        arriba.add(cmbFiltroCola, BorderLayout.NORTH);
        arriba.add(txtFiltroCola, BorderLayout.SOUTH);
        c.add(arriba, BorderLayout.NORTH);

        tablaCola.setRowHeight(26);
        tablaCola.setShowGrid(false);
        tablaCola.setIntercellSpacing(new Dimension(0, 0));
        tablaCola.setFillsViewportHeight(true);
        tablaCola.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tablaCola.setDefaultRenderer(Object.class, new Tablas.RenderCola());
        tablaCola.setDefaultRenderer(Integer.class, new Tablas.RenderCola());
        tablaCola.getTableHeader().setReorderingAllowed(false);
        tablaCola.getColumnModel().getColumn(0).setMaxWidth(24);
        tablaCola.getColumnModel().getColumn(0).setMinWidth(24);
        tablaCola.getColumnModel().getColumn(2).setPreferredWidth(56);
        tablaCola.getColumnModel().getColumn(2).setMaxWidth(70);
        tablaCola.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !actualizandoCola) {
                Grupo g = modeloCola.grupo(tablaCola.getSelectedRow());
                if (g != null && g != actual) {
                    mostrarGrupo(g, true);
                    tablaCola.requestFocusInWindow();
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tablaCola);
        scroll.setBorder(BorderFactory.createLineBorder(Estilo.BORDE));
        c.add(scroll, BorderLayout.CENTER);
        lblCola.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
        lblCola.setForeground(Estilo.TEXTO_SUAVE);
        c.add(lblCola, BorderLayout.SOUTH);

        cmbFiltroCola.addActionListener(e -> refrescarCola());
        // Con decenas de miles de grupos, el filtro espera a que se deje de teclear un instante.
        Timer retardoFiltro = new Timer(180, e -> refrescarCola());
        retardoFiltro.setRepeats(false);
        txtFiltroCola.getDocument().addDocumentListener(alCambiar(retardoFiltro::restart));

        JPanel t = Estilo.tarjeta("Cola de trabajo", c);
        t.setPreferredSize(new Dimension(320, 100));
        return t;
    }

    private JPanel construirTrabajo() {
        JPanel t = new JPanel(new BorderLayout(0, 12));
        t.setOpaque(false);
        t.add(tarjetaAparato(), BorderLayout.NORTH);

        JPanel medio = new JPanel(new BorderLayout(12, 0));
        medio.setOpaque(false);
        JPanel pila = new JPanel();
        pila.setOpaque(false);
        pila.setLayout(new BoxLayout(pila, BoxLayout.Y_AXIS));
        pila.add(tarjetaSugerencia());
        pila.add(Box.createVerticalStrut(12));
        pila.add(tarjetaCorrector());
        JPanel izquierda = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(ANCHO_COLUMNA_IZQ, super.getPreferredSize().height);
            }
        };
        izquierda.setOpaque(false);
        izquierda.add(pila, BorderLayout.NORTH);
        medio.add(izquierda, BorderLayout.WEST);
        medio.add(tarjetaBuscador(), BorderLayout.CENTER);
        t.add(medio, BorderLayout.CENTER);
        t.add(barraAcciones(), BorderLayout.SOUTH);
        return t;
    }

    private JPanel tarjetaAparato() {
        JPanel c = new JPanel(new BorderLayout(0, 4));

        JPanel fila1 = new JPanel(new BorderLayout());
        fila1.setOpaque(false);
        lblPosicion.setFont(Estilo.fuente(Font.PLAIN, 12f));
        lblPosicion.setForeground(Estilo.TEXTO_SUAVE);
        fila1.add(lblPosicion, BorderLayout.WEST);
        JPanel chipCaja = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        chipCaja.setOpaque(false);
        chipElectro.setToolTipText("Equipo_codiTipoActivo = E (equipo de electromedicina)");
        chipElectro.setVisible(false);
        chipCaja.add(chipElectro);
        chipCaja.add(chipEstado);
        fila1.add(chipCaja, BorderLayout.EAST);
        c.add(fila1, BorderLayout.NORTH);

        lblNombre.setFont(Estilo.fuente(Font.BOLD, 22f));
        lblNombre.setForeground(Estilo.TEXTO);
        c.add(lblNombre, BorderLayout.CENTER);

        JPanel abajo = new JPanel();
        abajo.setOpaque(false);
        abajo.setLayout(new BoxLayout(abajo, BoxLayout.Y_AXIS));
        JPanel datos = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        datos.setOpaque(false);
        valUnidades.setFont(Estilo.fuente(Font.BOLD, 17f));
        valUnidades.setForeground(Estilo.ACENTO);
        for (JLabel l : new JLabel[]{valMarca, valModelo, valReferencia}) {
            l.setFont(Estilo.fuente(Font.BOLD, 14f));
            l.setForeground(Estilo.TEXTO);
        }
        datos.add(Estilo.dato("EQUIPOS IGUALES", valUnidades));
        datos.add(Box.createHorizontalStrut(30));
        datos.add(Estilo.dato("MARCA", valMarca));
        datos.add(Box.createHorizontalStrut(30));
        datos.add(Estilo.dato("MODELO", valModelo));
        datos.add(Box.createHorizontalStrut(30));
        datos.add(Estilo.dato("REFERENCIA", valReferencia));
        datos.setAlignmentX(Component.LEFT_ALIGNMENT);
        abajo.add(Box.createVerticalStrut(6));
        abajo.add(datos);
        lblVariantes.setFont(Estilo.fuente(Font.PLAIN, 12f));
        lblVariantes.setForeground(Estilo.TEXTO_SUAVE);
        lblVariantes.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblAsignado.setFont(Estilo.fuente(Font.BOLD, 12.5f));
        lblAsignado.setAlignmentX(Component.LEFT_ALIGNMENT);
        abajo.add(Box.createVerticalStrut(8));
        abajo.add(lblVariantes);
        abajo.add(lblAsignado);
        c.add(abajo, BorderLayout.SOUTH);
        return Estilo.tarjeta("Equipo que toca revisar", c);
    }

    private JPanel tarjetaSugerencia() {
        int ancho = ANCHO_COLUMNA_IZQ - 195;

        JPanel con = new JPanel(new BorderLayout(12, 10));
        con.setOpaque(false);
        JPanel izq = new JPanel();
        izq.setOpaque(false);
        izq.setLayout(new BoxLayout(izq, BoxLayout.Y_AXIS));
        insignia.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblNivel.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblNivel.setFont(Estilo.fuente(Font.BOLD, 11.5f));
        izq.add(insignia);
        izq.add(Box.createVerticalStrut(4));
        izq.add(lblNivel);
        con.add(izq, BorderLayout.WEST);

        JPanel textos = new JPanel();
        textos.setOpaque(false);
        textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
        lblSugTermino.setFont(Estilo.fuente(Font.PLAIN, 13.5f));
        lblSugBase.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
        lblSugBase.setForeground(Estilo.TEXTO_SUAVE);
        lblSugDescripcion.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
        lblSugDescripcion.setForeground(Estilo.TEXTO_SUAVE);
        for (JLabel l : new JLabel[]{lblSugTermino, lblSugBase, lblSugDescripcion}) {
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        textos.add(lblSugTermino);
        textos.add(Box.createVerticalStrut(4));
        textos.add(lblSugBase);
        textos.add(Box.createVerticalStrut(4));
        textos.add(lblSugDescripcion);
        con.add(textos, BorderLayout.CENTER);

        JPanel abajo = new JPanel();
        abajo.setOpaque(false);
        abajo.setLayout(new BoxLayout(abajo, BoxLayout.Y_AXIS));
        btnAceptarSug.setText("✔ Aceptar sugerencia   " + Estilo.atajo("1"));
        btnAceptarSug.setFont(btnAceptarSug.getFont().deriveFont(Font.BOLD));
        btnAceptarSug.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnAceptarSug.setMaximumSize(new Dimension(Integer.MAX_VALUE, btnAceptarSug.getPreferredSize().height + 4));
        btnAceptarSug.addActionListener(e -> aceptarSugerencia(0));
        abajo.add(btnAceptarSug);
        lblOtras.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblOtras.setBorder(Estilo.relleno(6, 0, 0, 0));
        abajo.add(lblOtras);
        for (int i = 0; i < btnAlternativas.length; i++) {
            final int indice = i + 1;
            btnAlternativas[i].setAlignmentX(Component.LEFT_ALIGNMENT);
            btnAlternativas[i].setFont(Estilo.fuente(Font.PLAIN, 12f));
            btnAlternativas[i].addActionListener(e -> aceptarSugerencia(indice));
            abajo.add(btnAlternativas[i]);
        }
        con.add(abajo, BorderLayout.SOUTH);

        lblSinSug.setForeground(Estilo.TEXTO_SUAVE);
        lblSinSug.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
        JPanel panelSin = new JPanel(new BorderLayout());
        panelSin.setOpaque(false);
        panelSin.add(lblSinSug, BorderLayout.NORTH);

        panelSug.setOpaque(false);
        panelSug.add(con, "con");
        panelSug.add(panelSin, "sin");
        panelSug.add(new JLabel(" "), "vacio");

        // Estado de la IA (solo en modo IA): consultando, sin propuesta, en pausa…
        JPanel todo = new JPanel(new BorderLayout(0, 8));
        todo.setOpaque(false);
        todo.add(panelSug, BorderLayout.CENTER);
        lblIA.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
        lblIA.setForeground(Estilo.TEXTO_SUAVE);
        lblIA.setVisible(false);
        todo.add(lblIA, BorderLayout.SOUTH);
        JPanel t = Estilo.tarjeta("Sugerencia automática", todo);
        lblTituloSug = (JLabel) ((BorderLayout) t.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        return t;
    }

    private void textoSinSugerencia() {
        int ancho = ANCHO_COLUMNA_IZQ - 110;
        if (asistente != null) {
            lblSinSug.setText(Estilo.envolver("No hay nada parecido clasificado todavía y la IA no ha propuesto "
                    + "ningún término.<br>Busca el nombre oficial en el buscador de la derecha.", ancho));
        } else {
            lblSinSug.setText(Estilo.envolver("No hay nada parecido clasificado todavía.<br>"
                    + "Busca el nombre oficial en el buscador de la derecha: a partir de ahí, "
                    + "los equipos parecidos te lo propondrán solos.", ancho));
        }
    }

    private JPanel tarjetaCorrector() {
        JPanel c = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 0, 2, 8);

        JButton restaurarMarca = Estilo.enlace("↺ original");
        JButton restaurarModelo = Estilo.enlace("↺ original");
        restaurarMarca.setToolTipText("Volver a poner la marca tal y como venía");
        restaurarModelo.setToolTipText("Volver a poner el modelo tal y como venía");
        restaurarMarca.addActionListener(e -> {
            if (actual != null) {
                txtMarca.setText(actual.variasMarcas() ? "" : actual.getMarca());
            }
        });
        restaurarModelo.addActionListener(e -> {
            if (actual != null) {
                txtModelo.setText(actual.variosModelos() ? "" : actual.getModelo());
            }
        });

        txtMarca.setFont(Estilo.fuente(Font.PLAIN, 14f));
        txtModelo.setFont(Estilo.fuente(Font.PLAIN, 14f));
        for (JLabel l : new JLabel[]{lblMarcaOriginal, lblModeloOriginal}) {
            l.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
            l.setForeground(Estilo.TEXTO_SUAVE);
        }

        g.gridy = 0;
        g.gridx = 0;
        c.add(Estilo.etiqueta("Marca", Font.BOLD, 12.5f, Estilo.TEXTO), g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        c.add(txtMarca, g);
        g.gridx = 2;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        c.add(restaurarMarca, g);
        g.gridy = 1;
        g.gridx = 1;
        g.gridwidth = 2;
        c.add(lblMarcaOriginal, g);
        g.gridwidth = 1;

        g.gridy = 2;
        g.gridx = 0;
        g.insets = new Insets(8, 0, 2, 8);
        c.add(Estilo.etiqueta("Modelo", Font.BOLD, 12.5f, Estilo.TEXTO), g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        c.add(txtModelo, g);
        g.gridx = 2;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        c.add(restaurarModelo, g);
        g.insets = new Insets(2, 0, 2, 8);
        g.gridy = 3;
        g.gridx = 1;
        g.gridwidth = 2;
        c.add(lblModeloOriginal, g);

        g.gridy = 4;
        g.gridx = 0;
        g.gridwidth = 3;
        g.insets = new Insets(6, 0, 0, 0);
        btnMarcaIA.setFont(Estilo.fuente(Font.PLAIN, 12f));
        btnMarcaIA.setVisible(false);
        btnMarcaIA.addActionListener(e -> usarMarcaModeloIA());
        c.add(btnMarcaIA, g);

        return Estilo.tarjeta("Corrector de marca y modelo", c);
    }

    private JPanel tarjetaBuscador() {
        JPanel c = new JPanel(new BorderLayout(0, 8));

        JPanel cab = new JPanel(new BorderLayout(8, 6));
        cab.setOpaque(false);
        txtBuscar.setFont(Estilo.fuente(Font.PLAIN, 15f));
        txtBuscar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Estilo.ACENTO, 2, true), Estilo.relleno(5, 8, 5, 8)));
        cab.add(txtBuscar, BorderLayout.CENTER);

        // Selector de catálogo: se busca en uno u otro, nunca en los dos a la vez
        ButtonGroup grupoListas = new ButtonGroup();
        grupoListas.add(btnGmdn);
        grupoListas.add(btnMant);
        JPanel selector = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        selector.setOpaque(false);
        for (JToggleButton b : new JToggleButton[]{btnGmdn, btnMant}) {
            b.setFont(Estilo.fuente(Font.BOLD, 12.5f));
            b.setFocusable(false);
            b.setToolTipText("Catálogo en el que se busca (" + Estilo.atajo("L") + " para cambiar)");
            selector.add(b);
        }
        btnGmdn.addActionListener(e -> cambiarLista(TipoLista.GMDN, true));
        btnMant.addActionListener(e -> cambiarLista(TipoLista.MANTENIMIENTO, true));
        cab.add(selector, BorderLayout.EAST);

        JPanel opciones = new JPanel(new BorderLayout(10, 0));
        opciones.setOpaque(false);
        chkDescripcion.setOpaque(false);
        chkDescripcion.setFont(Estilo.fuente(Font.PLAIN, 12f));
        JPanel izqOpc = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        izqOpc.setOpaque(false);
        izqOpc.add(chkDescripcion);
        btnNuevaEntrada.setFont(Estilo.fuente(Font.PLAIN, 12f));
        btnNuevaEntrada.setToolTipText("Crear una entrada nueva en el catálogo de Mantenimiento ("
                + Estilo.atajo("⇧N") + ")");
        btnNuevaEntrada.addActionListener(e -> nuevaEntradaMantenimiento());
        izqOpc.add(Box.createHorizontalStrut(14));
        izqOpc.add(btnNuevaEntrada);
        opciones.add(izqOpc, BorderLayout.WEST);
        lblResultados.setFont(Estilo.fuente(Font.PLAIN, 12f));
        lblResultados.setForeground(Estilo.TEXTO_SUAVE);
        lblResultados.setHorizontalAlignment(JLabel.RIGHT);
        opciones.add(lblResultados, BorderLayout.CENTER);
        cab.add(opciones, BorderLayout.SOUTH);
        c.add(cab, BorderLayout.NORTH);

        tablaResultados.setRowHeight(26);
        tablaResultados.setShowHorizontalLines(true);
        tablaResultados.setShowVerticalLines(false);
        tablaResultados.setGridColor(new Color(0xEEF0F3));
        tablaResultados.setFillsViewportHeight(true);
        tablaResultados.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tablaResultados.setDefaultRenderer(Object.class, new Tablas.RenderResultados());
        tablaResultados.getTableHeader().setReorderingAllowed(false);
        ajustarColumnasResultados();
        tablaResultados.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                actualizarSeleccion();
            }
        });
        tablaResultados.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tablaResultados.rowAtPoint(e.getPoint()) >= 0) {
                    guardar();
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tablaResultados);
        scroll.setBorder(BorderFactory.createLineBorder(Estilo.BORDE));

        JPanel sinListas = new JPanel(new GridBagLayout());
        sinListas.setBackground(Estilo.FONDO_SUAVE);
        sinListas.setBorder(BorderFactory.createLineBorder(Estilo.BORDE));
        JPanel cajaSin = new JPanel();
        cajaSin.setOpaque(false);
        cajaSin.setLayout(new BoxLayout(cajaSin, BoxLayout.Y_AXIS));
        lblSinCatalogo.setFont(Estilo.fuente(Font.BOLD, 13.5f));
        lblSinCatalogo.setForeground(Estilo.TEXTO);
        lblSinCatalogo.setAlignmentX(Component.CENTER_ALIGNMENT);
        cajaSin.add(lblSinCatalogo);
        cajaSin.add(Box.createVerticalStrut(10));
        JButton b1 = new JButton("Importar catálogos (GMDN y Mantenimiento)…");
        b1.addActionListener(e -> importarCatalogos());
        b1.setAlignmentX(Component.CENTER_ALIGNMENT);
        cajaSin.add(b1);
        sinListas.add(cajaSin);

        panelResultados.setOpaque(false);
        panelResultados.add(scroll, "tabla");
        panelResultados.add(sinListas, "sinlistas");
        c.add(panelResultados, BorderLayout.CENTER);

        JPanel desc = new JPanel(new BorderLayout(0, 4));
        desc.setBackground(Estilo.FONDO_SUAVE);
        desc.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Estilo.BORDE), Estilo.relleno(8, 10, 8, 10)));
        lblDescTitulo.setFont(Estilo.fuente(Font.BOLD, 13f));
        desc.add(lblDescTitulo, BorderLayout.NORTH);
        txtDescripcion.setEditable(false);
        txtDescripcion.setFocusable(false);
        txtDescripcion.setLineWrap(true);
        txtDescripcion.setWrapStyleWord(true);
        txtDescripcion.setOpaque(false);
        txtDescripcion.setFont(Estilo.fuente(Font.PLAIN, 12.5f));
        txtDescripcion.setForeground(Estilo.TEXTO);
        txtDescripcion.setRows(5);
        JScrollPane sd = new JScrollPane(txtDescripcion);
        sd.setBorder(null);
        sd.setOpaque(false);
        sd.getViewport().setOpaque(false);
        sd.setPreferredSize(new Dimension(100, 96));
        desc.add(sd, BorderLayout.CENTER);
        c.add(desc, BorderLayout.SOUTH);

        txtBuscar.getDocument().addDocumentListener(alCambiar(() -> {
            if (!cambioProgramatico) {
                busquedaTecleada = true;
                retardoBusqueda.restart();
            }
        }));
        chkDescripcion.addActionListener(e -> lanzarBusqueda());
        actualizarSelectorLista();

        return Estilo.tarjeta("Buscador en los catálogos oficiales", c);
    }

    /** Anchos de las columnas de resultados, que cambian con el catálogo. */
    private void ajustarColumnasResultados() {
        javax.swing.table.TableColumnModel cm = tablaResultados.getColumnModel();
        tablaResultados.setRowHeight(listaBuscador == TipoLista.GMDN ? 26 : 40);
        if (listaBuscador == TipoLista.GMDN) {
            cm.getColumn(0).setPreferredWidth(80);
            cm.getColumn(0).setMinWidth(70);
            cm.getColumn(0).setMaxWidth(110);
            cm.getColumn(1).setPreferredWidth(560);
        }
    }

    /**
     * Cambia el catálogo en el que se busca.
     * @param aMano true si lo ha pedido el usuario (se repite la búsqueda y se vuelve al buscador)
     */
    private void cambiarLista(TipoLista lista, boolean aMano) {
        if (lista == null) {
            return;
        }
        boolean cambia = lista != listaBuscador;
        listaBuscador = lista;
        actualizarSelectorLista();
        if (cambia) {
            modeloResultados.setLista(lista);
            ajustarColumnasResultados();
        }
        if (aMano) {
            lanzarBusqueda();
            enfocarBuscador();
        }
    }

    private void cambiarDeLista() {
        cambiarLista(listaBuscador.otra(), true);
    }

    private void actualizarSelectorLista() {
        boolean gmdn = listaBuscador == TipoLista.GMDN;
        btnGmdn.setSelected(gmdn);
        btnMant.setSelected(!gmdn);
        btnGmdn.setForeground(gmdn ? Estilo.ACENTO : Estilo.TEXTO_SUAVE);
        btnMant.setForeground(!gmdn ? Estilo.MORADO : Estilo.TEXTO_SUAVE);
        chkDescripcion.setText(gmdn ? "Buscar también en la descripción" : "Buscar también en la clasificación");
        btnNuevaEntrada.setVisible(!gmdn);
        txtBuscar.setToolTipText(gmdn ? "Busca por nombre, código GMDN o palabras de la descripción"
                : "Busca por nombre de mantenimiento o por su clasificación (tipo, uso, familia…)");
    }

    private JPanel barraAcciones() {
        JPanel b = new JPanel(new BorderLayout(16, 0));
        b.setBackground(Color.WHITE);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Estilo.BORDE, 1, true), Estilo.relleno(10, 14, 10, 14)));
        JPanel izq = new JPanel(new BorderLayout(0, 2));
        izq.setOpaque(false);
        izq.add(Estilo.tituloTarjeta("Se asignará al guardar"), BorderLayout.NORTH);
        lblSeleccion.setFont(Estilo.fuente(Font.PLAIN, 14f));
        izq.add(lblSeleccion, BorderLayout.CENTER);
        b.add(izq, BorderLayout.CENTER);

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        botones.setOpaque(false);
        btnGuardar.setFont(btnGuardar.getFont().deriveFont(Font.BOLD, 14f));
        btnGuardar.setToolTipText("Guarda la asignación y pasa al siguiente (tecla Intro)");
        btnPosponer.setToolTipText("Lo deja marcado como duda y pasa al siguiente");
        btnAnterior.setToolTipText("Vuelve al grupo anterior");
        btnGuardar.addActionListener(e -> guardar());
        btnPosponer.addActionListener(e -> posponer());
        btnAnterior.addActionListener(e -> anterior());
        botones.add(btnAnterior);
        botones.add(btnPosponer);
        botones.add(btnGuardar);
        b.add(botones, BorderLayout.EAST);
        return b;
    }

    private JMenuBar construirMenu() {
        JMenuBar barra = new JMenuBar();

        JMenu archivo = new JMenu("Archivo");
        JMenu importar = new JMenu("Importar");
        importar.add(item("Inventario del hospital (crea un proyecto nuevo)…", KeyEvent.VK_N, 0, this::importarInventario));
        importar.add(item("Catálogos oficiales (Excel con hojas GMDN y Mantenimiento)…", 0, 0, this::importarCatalogos));
        importar.addSeparator();
        importar.add(item("Resultado de una catalogación anterior (aplicarlo a este proyecto)…", 0, 0,
                this::importarResultadoAnterior));
        importar.add(item("Tabla nombre del equipo → nombre oficial (para aprender)…", 0, 0,
                this::importarClasificaciones));
        archivo.add(importar);
        JMenu exportar = new JMenu("Exportar");
        exportar.add(item("Resultado (un Excel o CSV por centro)…", KeyEvent.VK_E, 0, this::exportar));
        exportar.add(item("Catálogos con las entradas nuevas (Excel igual al original)…", 0, 0, this::exportarCatalogos));
        archivo.add(exportar);
        archivo.addSeparator();
        archivo.add(item("Abrir proyecto guardado…", KeyEvent.VK_O, 0, this::elegirProyecto));
        archivo.add(item("Abrir carpeta de datos", 0, 0, this::abrirCarpetaDatos));
        archivo.addSeparator();
        archivo.add(item("Salir", KeyEvent.VK_Q, 0, this::salir));
        barra.add(archivo);

        JMenu cat = new JMenu("Catálogos");
        cat.add(item("Importar catálogos (Excel con hojas GMDN y Mantenimiento)…", 0, 0, this::importarCatalogos));
        cat.addSeparator();
        cat.add(item("Buscar en el otro catálogo (GMDN ⇄ Mantenimiento)", KeyEvent.VK_L, 0, this::cambiarDeLista));
        cat.addSeparator();
        cat.add(item("Nueva entrada de mantenimiento…", KeyEvent.VK_N, InputEvent.SHIFT_DOWN_MASK,
                this::nuevaEntradaMantenimiento));
        cat.add(item("Corregir la clasificación de la entrada seleccionada…", 0, 0, this::corregirEntradaMantenimiento));
        cat.addSeparator();
        cat.add(item("Exportar catálogos a Excel (con las entradas nuevas)…", 0, 0, this::exportarCatalogos));
        barra.add(cat);

        JMenu revision = new JMenu("Revisión");
        JMenuItem guardar = item("Guardar y pasar al siguiente", 0, 0, this::guardar);
        guardar.setText("Guardar y pasar al siguiente   (Intro)");
        revision.add(guardar);
        revision.add(item("Aceptar la sugerencia", KeyEvent.VK_1, 0, () -> aceptarSugerencia(0)));
        revision.add(item("Posponer (dejar como duda)", KeyEvent.VK_P, 0, this::posponer));
        revision.add(item("Volver al anterior", 0, 0, this::anterior));
        revision.add(item("Saltar al siguiente pendiente", KeyEvent.VK_J, 0, this::saltarSiguiente));
        revision.addSeparator();
        revision.add(item("Ir al buscador", KeyEvent.VK_F, 0, this::enfocarBuscador));
        revision.add(item("Cambiar de catálogo (GMDN ⇄ Mantenimiento)", 0, 0, this::cambiarDeLista));
        revision.add(item("Ir a la marca", KeyEvent.VK_M, 0, () -> enfocar(txtMarca)));
        revision.add(item("Ir al modelo", KeyEvent.VK_M, InputEvent.SHIFT_DOWN_MASK, () -> enfocar(txtModelo)));
        revision.addSeparator();
        revision.add(item("Revisar los pospuestos (dudas)", 0, 0, this::revisarPospuestos));
        revision.add(item("Devolver este grupo a pendiente", 0, 0, this::volverAPendiente));
        barra.add(revision);

        JMenu ia = new JMenu("IA");
        ia.add(item("Modo de trabajo (manual o IA) y servicio de IA…", 0, 0, this::abrirModo));
        ia.addSeparator();
        accionesIA.add(item("Aceptar la propuesta de la IA", KeyEvent.VK_I, 0, this::aceptarPropuestaIA));
        accionesIA.add(item("Volver a preguntar a la IA por este grupo", KeyEvent.VK_I, InputEvent.SHIFT_DOWN_MASK,
                this::repreguntarIA));
        accionesIA.add(null);
        accionesIA.add(item("Pedir propuestas para todos los grupos pendientes…", 0, 0, this::pedirTodasIA));
        accionesIA.add(item("Aceptar en bloque las propuestas más seguras…", 0, 0, this::aceptarEnBloqueIA));
        accionesIA.add(null);
        accionesIA.add(item("Borrar las propuestas guardadas de este proyecto…", 0, 0, this::borrarPropuestasIA));
        accionesIA.add(item("Consumo de la IA en esta sesión", 0, 0, this::consumoIA));
        for (JMenuItem m : accionesIA) {
            if (m == null) {
                ia.addSeparator();
            } else {
                ia.add(m);
            }
        }
        accionesIA.removeIf(m -> m == null);
        barra.add(ia);

        JMenu ayuda = new JMenu("Ayuda");
        ayuda.add(item("Atajos de teclado", 0, 0, this::mostrarAtajos));
        ayuda.add(item("Cómo funciona", 0, 0, this::mostrarAyuda));
        ayuda.add(item("Acerca de Catalogador", 0, 0, this::acercaDe));
        barra.add(ayuda);
        return barra;
    }

    private JMenuItem item(String texto, int tecla, int modificadores, Runnable accion) {
        JMenuItem i = new JMenuItem(texto);
        if (tecla != 0) {
            i.setAccelerator(KeyStroke.getKeyStroke(tecla, Estilo.MASCARA | modificadores));
        }
        i.addActionListener(e -> accion.run());
        return i;
    }

    private void configurarAtajos() {
        JRootPane rp = getRootPane();
        rp.setDefaultButton(btnGuardar);
        enlazar(rp, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "sug1",
                () -> aceptarSugerencia(0));
        enlazar(rp, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_2, Estilo.MASCARA), "sug2",
                () -> aceptarSugerencia(1));
        enlazar(rp, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_3, Estilo.MASCARA), "sug3",
                () -> aceptarSugerencia(2));
        enlazar(rp, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "posponer",
                this::posponer);

        // En el buscador: flechas para moverse por los resultados, Esc para borrar.
        enlazar(txtBuscar, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "abajo",
                () -> moverResultado(1));
        enlazar(txtBuscar, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "arriba",
                () -> moverResultado(-1));
        enlazar(txtBuscar, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "pagAbajo",
                () -> moverResultado(10));
        enlazar(txtBuscar, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "pagArriba",
                () -> moverResultado(-10));
        enlazar(txtBuscar, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "borrar",
                () -> txtBuscar.setText(""));

        // Intro dentro de las tablas
        enlazar(tablaResultados, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "guardar", this::guardar);
        enlazar(tablaCola, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "trabajar", this::enfocarBuscador);
    }

    private static void enlazar(JComponent c, int cuando, KeyStroke tecla, String nombre, Runnable accion) {
        c.getInputMap(cuando).put(tecla, nombre);
        c.getActionMap().put(nombre, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                accion.run();
            }
        });
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

    // =====================================================================
    // Arranque
    // =====================================================================

    /** Carga listas, memoria y el último proyecto (en segundo plano). */
    public void iniciar() {
        try {
            almacen.prepararCarpetas();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se puede crear la carpeta de datos:\n" + almacen.getBase()
                    + "\n\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
        refrescarListas();
        final String[] errorProyecto = {null};
        final boolean[] antiguo = {false};
        Espera.ejecutar(this, "Cargando catálogos y el último proyecto…", "equipos", avance -> {
            catalogos.cargar();
            memoria.cargar();
            String ultimo = config.getProperty("ultimoProyecto");
            if (ultimo != null && Files.exists(Paths.get(ultimo, "inventario.tsv"))) {
                try {
                    return almacen.abrir(Paths.get(ultimo), avance);
                } catch (Almacen.ProyectoAntiguoException e) {
                    antiguo[0] = true; // de la versión anterior: simplemente no se abre
                } catch (IOException | RuntimeException e) {
                    errorProyecto[0] = e.getMessage();
                }
            }
            return null;
        }, p -> {
            refrescarListas();
            if (antiguo[0]) {
                config.remove("ultimoProyecto");
                almacen.guardarConfig(config);
            }
            if (p != null) {
                abrirProyecto(p);
            }
            if (errorProyecto[0] != null) {
                JOptionPane.showMessageDialog(this, "No se pudo abrir el último proyecto:\n" + errorProyecto[0],
                        "Aviso", JOptionPane.WARNING_MESSAGE);
            }
            avisarDatosAntiguos();
            if (configIA.isPreguntarAlEmpezar() || configIA.esPrimeraVez()) {
                DialogoModo.mostrar(this, configIA, true);
            }
            prepararIA();
        });
        lblEstadoBarra.setText("Los datos se guardan automáticamente en " + almacen.getBase());
        actualizarModo();
    }

    /** Si al cargar se apartaron datos de la versión 1 (listas de ejemplo, memoria…), se avisa una vez. */
    private void avisarDatosAntiguos() {
        List<String> apartados = new ArrayList<>(catalogos.getApartados());
        apartados.addAll(memoria.getApartados());
        if (apartados.isEmpty()) {
            return;
        }
        JOptionPane.showMessageDialog(this, "<html>Esta versión usa los catálogos y el inventario con su formato real "
                        + "(GMDN + Mantenimiento, Equipo_codiCentro, Equipo_idEquipo…).<br><br>"
                        + "Los datos de la versión anterior (listas de ejemplo y lo aprendido con ellas) se han apartado "
                        + "sin borrarlos:<br><b>" + Texto.html(String.join(", ", apartados)) + "</b><br>en "
                        + Texto.html(almacen.getBase().toString()) + "<br><br>"
                        + "Importa los catálogos y el inventario para empezar.</html>",
                "Datos de la versión anterior", JOptionPane.INFORMATION_MESSAGE);
    }

    private void abrirProyecto(Proyecto p) {
        proyecto = p;
        actual = null;
        historial.clear();
        propuestas = new PropuestasGuardadas(p.getCarpeta());
        pidiendoIA.clear();
        erroresIA.clear();
        try {
            propuestas.cargar();
        } catch (IOException e) {
            lblEstadoBarra.setText("No se pudieron leer las propuestas de la IA guardadas: " + e.getMessage());
        }
        config.setProperty("ultimoProyecto", p.getCarpeta().toString());
        almacen.guardarConfig(config);
        setTitle("Catalogador — " + p.getNombre());
        lblProyecto.setText(p.getNombre() + "  ·  " + Estilo.numero(p.numEquipos()) + " equipos  ·  "
                + Estilo.numero(p.getGrupos().size()) + " grupos distintos  ·  " + p.centros().size()
                + (p.centros().size() == 1 ? " centro" : " centros"));
        panelCola.setVisible(true);
        barraProgreso.setVisible(true);
        cartas.show(centro, "trabajo");

        Grupo primero = siguienteCon(EstadoGrupo.PENDIENTE, null);
        int filtro = FILTRO_PENDIENTES;
        if (primero == null && p.contar(EstadoGrupo.POSPUESTO) > 0) {
            filtro = FILTRO_POSPUESTOS;
            primero = siguienteCon(EstadoGrupo.POSPUESTO, null);
        }
        cmbFiltroCola.setSelectedIndex(filtro);
        refrescarCola();
        refrescarProgreso();
        if (primero == null) {
            mostrarFinal();
        } else {
            mostrarGrupo(primero, false);
        }
    }

    // =====================================================================
    // Mostrar un aparato
    // =====================================================================

    private void mostrarGrupo(Grupo g, boolean recordar) {
        if (g == null) {
            mostrarFinal();
            return;
        }
        if (recordar && actual != null && actual != g) {
            historial.push(actual);
            while (historial.size() > 500) {
                historial.removeLast();
            }
        }
        actual = g;
        habilitar(true);

        int total = proyecto.getGrupos().size();
        lblPosicion.setText("Grupo " + Estilo.numero(proyecto.posicion(g) + 1) + " de " + Estilo.numero(total)
                + "   ·   ordenados de más a menos equipos"
                + (g.getTipoActivo().isEmpty() ? "" : "   ·   tipo de activo " + g.getTipoActivo()));
        chipEstado.mostrar(g.getEstado().texto(), Estilo.colorEstado(g.getEstado()));
        if (g.isElectromedicina()) {
            chipElectro.mostrar("⚡ Electromedicina", Estilo.ACENTO);
        } else {
            chipElectro.mostrar("", Estilo.GRIS);
        }
        lblNombre.setText(g.getNombre());
        lblNombre.setToolTipText(g.getNombre());
        valUnidades.setText(Estilo.numero(g.getEquipos()) + (g.getEquipos() == 1 ? " equipo" : " equipos"));
        valMarca.setText(textoDato(g.getMarca(), g.getVariantesMarca(), g.variasMarcas()));
        valMarca.setToolTipText(listaVariantes(g.getVariantesMarca()));
        valModelo.setText(textoDato(g.getModelo(), g.getVariantesModelo(), g.variosModelos()));
        valModelo.setToolTipText(listaVariantes(g.getVariantesModelo()));
        int refs = g.getVariantesReferencia().noVacias().size();
        valReferencia.setText(refs > 1 ? "Varias (" + refs + ")"
                : g.getReferencia().isEmpty() ? "—" : Texto.recortar(g.getReferencia(), 34));
        valReferencia.setToolTipText(refs > 1 ? listaVariantes(g.getVariantesReferencia())
                : g.getReferencia().isEmpty() ? null : g.getReferencia());

        StringBuilder v = new StringBuilder();
        if (g.getVariantesNombre().noVacias().size() > 1) {
            v.append("También escrito como: ").append(variantes(g.getVariantesNombre(), g.getNombre(), 4));
        }
        if (refs > 1) {
            v.append(v.length() > 0 ? "   ·   " : "").append("Referencias: ")
                    .append(variantes(g.getVariantesReferencia(), null, 3));
        }
        lblVariantes.setText(v.length() == 0 ? " " : Texto.recortar(v.toString(), 190));
        lblVariantes.setToolTipText(v.length() == 0 ? null : v.toString());
        if (g.getEstado() == EstadoGrupo.REVISADO) {
            lblAsignado.setForeground(Estilo.VERDE);
            lblAsignado.setText("✔ Ya revisado → " + g.asignacionTexto() + "   (" + g.getFecha() + ")");
        } else if (g.getEstado() == EstadoGrupo.POSPUESTO) {
            lblAsignado.setForeground(Estilo.NARANJA);
            lblAsignado.setText("Marcado como duda el " + g.getFecha());
        } else {
            lblAsignado.setText(" ");
        }

        // Corrector: lo ya corregido, o la corrección aprendida, o el original
        boolean marcaAprendida = false;
        String marca = g.getMarcaCorregida();
        if (marca == null) {
            if (g.variasMarcas()) {
                marca = "";
            } else {
                String conocida = memoria.marcaConocida(g.getMarca());
                marcaAprendida = conocida != null && !conocida.equals(g.getMarca());
                marca = conocida != null ? conocida : g.getMarca();
            }
        }
        boolean modeloAprendido = false;
        String modelo = g.getModeloCorregido();
        if (modelo == null) {
            if (g.variosModelos()) {
                modelo = "";
            } else {
                String conocido = memoria.modeloConocido(g.getModelo());
                modeloAprendido = conocido != null && !conocido.equals(g.getModelo());
                modelo = conocido != null ? conocido : g.getModelo();
            }
        }
        txtMarca.setText(marca);
        txtModelo.setText(modelo);
        lblMarcaOriginal.setText(textoOriginal(g.getMarca(), g.getVariantesMarca(), g.variasMarcas(), marcaAprendida, "marca"));
        lblModeloOriginal.setText(textoOriginal(g.getModelo(), g.getVariantesModelo(), g.variosModelos(), modeloAprendido, "modelo"));

        // Sugerencias (memoria y, en modo IA, la propuesta de la IA)
        sugerencias = calcularSugerencias(g);
        pintarSugerencias();
        pintarEstadoIA();
        pedirIA(g);

        // Catálogo del buscador: el del grupo si ya está revisado, el de la sugerencia, o según el tipo de activo
        TipoLista lista = g.getLista() != null ? g.getLista()
                : !sugerencias.isEmpty() ? sugerencias.get(0).getLista()
                : g.isElectromedicina() ? TipoLista.GMDN : TipoLista.MANTENIMIENTO;
        cambiarLista(lista, false);

        // Buscador: se rellena con el nombre (seleccionado, para sobrescribir al teclear)
        cambioProgramatico = true;
        txtBuscar.setText(g.getNombre());
        cambioProgramatico = false;
        busquedaTecleada = false;
        retardoBusqueda.stop();
        tablaResultados.clearSelection();
        lanzarBusqueda();
        actualizarSeleccion();
        seleccionarEnCola(g);
        txtBuscar.requestFocusInWindow();
        txtBuscar.selectAll();
    }

    private static String textoDato(String valor, Variantes variantes, boolean varias) {
        if (varias) {
            return "Varias (" + variantes.noVacias().size() + ")";
        }
        return valor.isEmpty() ? "—" : Texto.recortar(valor, 34);
    }

    /** Tooltip con todas las formas de un dato y cuántas veces aparece. */
    private static String listaVariantes(Variantes v) {
        List<Map.Entry<String, Integer>> l = v.noVacias();
        if (l.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("<html>");
        int n = 0;
        for (Map.Entry<String, Integer> e : l) {
            if (n++ == 25) {
                b.append("…");
                break;
            }
            b.append(Texto.html(e.getKey())).append(" &nbsp;<font color='#8a94a6'>(")
                    .append(Estilo.numero(e.getValue())).append(")</font><br>");
        }
        return b.append("</html>").toString();
    }

    private static String textoOriginal(String valor, Variantes variantes, boolean varias,
                                        boolean aprendida, String que) {
        if (varias) {
            return Estilo.envolver("Mezcla varias: " + Texto.html(variantes(variantes, null, 3))
                    + ". Si lo dejas vacío se conserva la de cada equipo.", ANCHO_COLUMNA_IZQ - 170);
        }
        String base = "Original: " + (valor.isEmpty() ? "(vacío)" : valor);
        if (aprendida) {
            base += "  ·  corregida como la última vez";
        }
        return Texto.recortar(base, 70);
    }

    private static String variantes(Variantes v, String excluir, int max) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Integer> e : v.noVacias()) {
            if (e.getKey().equals(excluir)) {
                continue;
            }
            if (n == max) {
                b.append("…");
                break;
            }
            if (n > 0) {
                b.append(", ");
            }
            b.append("«").append(Texto.recortar(e.getKey(), 40)).append("» (").append(Estilo.numero(e.getValue()))
                    .append(")");
            n++;
        }
        return b.toString();
    }

    private void pintarSugerencias() {
        lblTituloSug.setText(asistente != null ? "SUGERENCIA AUTOMÁTICA (MEMORIA + IA)" : "SUGERENCIA AUTOMÁTICA");
        if (sugerencias.isEmpty()) {
            textoSinSugerencia();
            cartasSug.show(panelSug, "sin");
            btnAceptarSug.setEnabled(false);
            return;
        }
        int ancho = ANCHO_COLUMNA_IZQ - 195;
        Sugerencia s = sugerencias.get(0);
        insignia.setValor(s.getSimilitud());
        lblNivel.setText(Sugerencia.nivel(s.getSimilitud()));
        lblNivel.setForeground(Sugerencia.color(s.getSimilitud()));
        lblSugTermino.setText(Estilo.envolver("<b>" + Texto.html(s.getNombreOficial()) + "</b><br>"
                + "<font color='" + Estilo.hex(Estilo.TEXTO_SUAVE) + "'>" + Texto.html(s.getLista().largo())
                + (s.getLista() == TipoLista.GMDN ? " · código " + Texto.html(s.getCodigo()) : "")
                + "</font>", ancho));
        Memoria.Entrada e = s.getBasadaEn();
        String base = "";
        if (e != null) {
            String marca = (e.getMarca() + " " + e.getModelo()).trim();
            base = "Porque ya clasificaste «" + Texto.html(Texto.recortar(e.getNombre(), 70))
                    + "»" + (marca.isEmpty() ? "" : " (" + Texto.html(Texto.recortar(marca, 40)) + ")");
        }
        if (s.getOrigen() == Sugerencia.Origen.IA) {
            base = "<font color='" + Estilo.hex(Estilo.MORADO) + "'><b>Propuesta de la IA</b></font>"
                    + (s.getMotivo().isEmpty() ? "" : ": " + Texto.html(Texto.recortar(s.getMotivo(), 150)));
        } else if (s.getOrigen() == Sugerencia.Origen.MEMORIA_E_IA) {
            base += "<br><font color='" + Estilo.hex(Estilo.MORADO) + "'><b>y la IA está de acuerdo</b></font>"
                    + (s.getMotivo().isEmpty() ? "" : ": " + Texto.html(Texto.recortar(s.getMotivo(), 110)));
        }
        lblSugBase.setText(Estilo.envolver(base, ancho));
        lblSugBase.setToolTipText(s.esDeIA() ? Estilo.envolver(Texto.html(s.getMotivo()) + "<br><br><i>"
                + Texto.html(s.getFuente()) + "</i>", 380) : null);
        TerminoOficial t = catalogos.porCodigo(s.getLista(), s.getCodigo());
        if (t == null && catalogos.get(s.getLista()) != null) {
            lblSugDescripcion.setText(Estilo.envolver("<font color='" + Estilo.hex(Estilo.NARANJA)
                    + "'>Este término ya no está en el catálogo cargado.</font>", ancho));
            lblSugDescripcion.setToolTipText(null);
        } else if (t != null && !t.getDescripcion().isEmpty()) {
            lblSugDescripcion.setText(Estilo.envolver("<i>" + Texto.html(Texto.recortar(t.getDescripcion(), 200))
                    + "</i>", ancho));
            lblSugDescripcion.setToolTipText(Estilo.envolver(Texto.html(t.getDescripcion()), 400));
        } else {
            lblSugDescripcion.setText(" ");
            lblSugDescripcion.setToolTipText(null);
        }
        btnAceptarSug.setEnabled(true);
        for (int i = 0; i < btnAlternativas.length; i++) {
            if (i + 1 < sugerencias.size()) {
                Sugerencia a = sugerencias.get(i + 1);
                btnAlternativas[i].setText("<html><font color='" + Estilo.hex(Sugerencia.color(a.getSimilitud()))
                        + "'><b>" + a.porcentaje() + "%</b></font>&nbsp; "
                        + (a.esDeIA() ? "<font color='" + Estilo.hex(Estilo.MORADO) + "'><b>IA</b></font> " : "")
                        + Texto.html(Texto.recortar(a.getLista() == TipoLista.GMDN
                        ? "GMDN " + a.getCodigo() + " · " + a.getNombreOficial()
                        : "Mant. · " + a.getNombreOficial(), 44))
                        + " &nbsp;<font color='" + Estilo.hex(Estilo.TEXTO_TENUE) + "'>" + Estilo.atajo(String.valueOf(i + 2))
                        + "</font></html>");
                btnAlternativas[i].setToolTipText("Usar esta: " + a.getNombreOficial());
                btnAlternativas[i].setVisible(true);
            } else {
                btnAlternativas[i].setVisible(false);
            }
        }
        lblOtras.setVisible(sugerencias.size() > 1);
        cartasSug.show(panelSug, "con");
    }

    private void mostrarFinal() {
        actual = null;
        habilitar(false);
        chipEstado.mostrar("", Estilo.GRIS);
        boolean hayProyecto = proyecto != null;
        int pospuestos = hayProyecto ? proyecto.contar(EstadoGrupo.POSPUESTO) : 0;
        lblPosicion.setText(" ");
        lblNombre.setText(pospuestos > 0 ? "No quedan grupos pendientes" : "¡Todo revisado!");
        chipElectro.mostrar("", Estilo.GRIS);
        valUnidades.setText("—");
        valMarca.setText("—");
        valModelo.setText("—");
        valReferencia.setText("—");
        valMarca.setToolTipText(null);
        valModelo.setToolTipText(null);
        valReferencia.setToolTipText(null);
        lblVariantes.setToolTipText(null);
        lblVariantes.setText(pospuestos > 0
                ? "Quedan " + pospuestos + " pospuestos (dudas). Menú Revisión → «Revisar los pospuestos»."
                : "Puedes generar el resultado en Archivo → Exportar → Resultado (" + Estilo.atajo("E") + ").");
        lblAsignado.setText(" ");
        txtMarca.setText("");
        txtModelo.setText("");
        lblMarcaOriginal.setText(" ");
        lblModeloOriginal.setText(" ");
        sugerencias = Collections.emptyList();
        cartasSug.show(panelSug, "vacio");
        propuestaActual = null;
        lblIA.setVisible(false);
        btnMarcaIA.setVisible(false);
        cambioProgramatico = true;
        txtBuscar.setText("");
        cambioProgramatico = false;
        modeloResultados.poner(Collections.<Catalogos.Resultado>emptyList());
        sinCoincidencias = false;
        lblResultados.setText(" ");
        actualizarSeleccion();
        seleccionarEnCola(null);
    }

    private void habilitar(boolean si) {
        btnGuardar.setEnabled(si);
        btnPosponer.setEnabled(si);
        btnAceptarSug.setEnabled(si && !sugerencias.isEmpty());
        txtMarca.setEnabled(si);
        txtModelo.setEnabled(si);
        txtBuscar.setEnabled(si);
    }

    // =====================================================================
    // Buscador
    // =====================================================================

    private void lanzarBusqueda() {
        retardoBusqueda.stop();
        final String consulta = txtBuscar.getText();
        final TipoLista filtro = listaBuscador;
        final boolean enDescripcion = chkDescripcion.isSelected();
        final boolean autoSeleccionar = busquedaTecleada;
        final int gen = generacion.incrementAndGet();
        hiloBusqueda.submit(() -> {
            try {
                Catalogos.Busqueda b = catalogos.buscar(consulta, filtro, enDescripcion, MAX_RESULTADOS);
                SwingUtilities.invokeLater(() -> {
                    if (gen == generacion.get()) {
                        mostrarResultados(b, consulta, autoSeleccionar);
                    }
                });
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        });
    }

    private void mostrarResultados(Catalogos.Busqueda b, String consulta, boolean autoSeleccionar) {
        refrescarPanelResultados();
        modeloResultados.setLista(listaBuscador);
        modeloResultados.poner(b.getResultados());
        int n = b.getResultados().size();
        lblResultados.setToolTipText(null);
        if (consulta.trim().isEmpty()) {
            lblResultados.setText("Escribe para buscar en " + listaBuscador.largo());
        } else if (n == 0) {
            lblResultados.setText("Sin coincidencias");
        } else if (b.getTotal() > n) {
            lblResultados.setText("Mostrando " + Estilo.numero(n) + " de " + Estilo.numero(b.getTotal()) + " coincidencias");
        } else {
            lblResultados.setText(Estilo.numero(n) + (n == 1 ? " coincidencia" : " coincidencias"));
        }
        sinCoincidencias = n == 0 && !consulta.trim().isEmpty();
        if (autoSeleccionar && n > 0) {
            tablaResultados.setRowSelectionInterval(0, 0);
            tablaResultados.scrollRectToVisible(tablaResultados.getCellRect(0, 0, true));
        } else {
            tablaResultados.clearSelection();
            if (n > 0) {
                tablaResultados.scrollRectToVisible(tablaResultados.getCellRect(0, 0, true));
            }
        }
        actualizarSeleccion();
    }

    private void moverResultado(int delta) {
        int n = modeloResultados.getRowCount();
        if (n == 0) {
            return;
        }
        int f = tablaResultados.getSelectedRow();
        f = f < 0 ? (delta > 0 ? 0 : n - 1) : Math.max(0, Math.min(n - 1, f + delta));
        tablaResultados.setRowSelectionInterval(f, f);
        Rectangle r = tablaResultados.getCellRect(f, 0, true);
        tablaResultados.scrollRectToVisible(r);
    }

    private TerminoOficial terminoSeleccionado() {
        return modeloResultados.termino(tablaResultados.getSelectedRow());
    }

    /** Actualiza "Se asignará…" y la descripción según lo seleccionado. */
    private void actualizarSeleccion() {
        temporizadorAviso.stop();
        TerminoOficial t = terminoSeleccionado();
        if (t != null) {
            boolean gmdn = t.getLista() == TipoLista.GMDN;
            lblSeleccion.setForeground(Estilo.TEXTO);
            lblSeleccion.setText("<html><b>" + Texto.html(gmdn ? "GMDN " + t.getCodigo() : "Mantenimiento") + "</b> · "
                    + Texto.html(Texto.recortar(t.getNombre(), 90)) + "</html>");
            lblDescTitulo.setText((gmdn ? t.getCodigo() + " · " : "") + t.getNombre()
                    + (t.isCreadoEnPrograma() ? "   (creada en el programa el " + t.getFechaCreacion() + ")" : ""));
            lblDescTitulo.setToolTipText(t.getNombre());
            if (!gmdn) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < TerminoOficial.NIVELES.length; i++) {
                    TerminoOficial.Nivel n = t.nivel(i);
                    if (!n.vacio()) {
                        b.append(b.length() == 0 ? "" : "\n").append(TerminoOficial.NIVELES[i]).append(": ")
                                .append(n.getNombre()).append("  (").append(n.getCodigo()).append(")");
                    }
                }
                txtDescripcion.setForeground(b.length() == 0 ? Estilo.TEXTO_SUAVE : Estilo.TEXTO);
                txtDescripcion.setText(b.length() == 0 ? "(Esta entrada no tiene clasificación.)" : b.toString());
            } else if (t.getDescripcion().isEmpty()) {
                txtDescripcion.setForeground(Estilo.TEXTO_SUAVE);
                txtDescripcion.setText("(Esta entrada no tiene descripción en el catálogo.)");
            } else {
                txtDescripcion.setForeground(Estilo.TEXTO);
                txtDescripcion.setText(t.getDescripcion());
            }
            txtDescripcion.setCaretPosition(0);
            return;
        }
        boolean gmdn = listaBuscador == TipoLista.GMDN;
        lblDescTitulo.setToolTipText(null);
        txtDescripcion.setForeground(Estilo.TEXTO_SUAVE);
        if (sinCoincidencias) {
            lblDescTitulo.setText("Sin coincidencias en el " + listaBuscador.largo());
            txtDescripcion.setText("Prueba con menos palabras o con el comienzo de una palabra, o busca en el "
                    + listaBuscador.otra().largo() + " (" + Estilo.atajo("L") + ")."
                    + (gmdn ? "" : "\nSi el nombre no existe, créalo con «+ Nueva entrada…» (" + Estilo.atajo("⇧N") + ")."));
        } else {
            lblDescTitulo.setText(gmdn ? "Descripción" : "Clasificación");
            txtDescripcion.setText("Selecciona un resultado (con el ratón o las flechas ↑ ↓) para ver su "
                    + (gmdn ? "descripción." : "clasificación (tipo de activo, uso, familia, subfamilia y subgrupo)."));
        }
        if (actual == null) {
            lblSeleccion.setForeground(Estilo.TEXTO_SUAVE);
            lblSeleccion.setText(proyecto == null ? " " : "No hay ningún grupo seleccionado.");
        } else if (actual.getEstado() == EstadoGrupo.REVISADO) {
            lblSeleccion.setForeground(Estilo.VERDE);
            lblSeleccion.setText("<html>Se mantiene: <b>" + Texto.html(Texto.recortar(actual.asignacionTexto(), 90))
                    + "</b></html>");
        } else {
            lblSeleccion.setForeground(Estilo.TEXTO_TENUE);
            lblSeleccion.setText("Nada todavía: elige un resultado del buscador o acepta la sugerencia.");
        }
    }

    private void avisar(String texto) {
        Toolkit.getDefaultToolkit().beep();
        lblSeleccion.setForeground(Estilo.ROJO);
        lblSeleccion.setText(texto);
        temporizadorAviso.restart();
    }

    // =====================================================================
    // Acciones de revisión
    // =====================================================================

    private void guardar() {
        if (actual == null) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        TerminoOficial t = terminoSeleccionado();
        if (t != null) {
            asignar(t.getLista(), t.getCodigo(), t.getNombre(), "Buscador");
        } else if (actual.getEstado() == EstadoGrupo.REVISADO) {
            asignar(actual.getLista(), actual.getCodigo(), actual.getNombreOficial(), actual.getOrigen());
        } else {
            avisar("Primero elige un nombre oficial en el buscador (flechas ↑ ↓) o acepta la sugerencia ("
                    + Estilo.atajo("1") + ").");
            txtBuscar.requestFocusInWindow();
        }
    }

    private void aceptarSugerencia(int i) {
        if (actual == null || i >= sugerencias.size()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        Sugerencia s = sugerencias.get(i);
        String origen;
        switch (s.getOrigen()) {
            case IA:
                origen = "IA: " + s.getFuente() + " (" + s.porcentaje() + "% de confianza)";
                break;
            case MEMORIA_E_IA:
                origen = "Sugerencia (" + s.porcentaje() + "% parecido) confirmada por la IA";
                break;
            default:
                origen = "Sugerencia (" + s.porcentaje() + "% parecido)";
        }
        asignar(s.getLista(), s.getCodigo(), s.getNombreOficial(), origen);
    }

    private void asignar(TipoLista lista, String codigo, String nombreOficial, String origen) {
        String marca = txtMarca.getText().trim();
        String modelo = txtModelo.getText().trim();
        String fecha = LocalDateTime.now().format(Almacen.FECHA);
        actual.asignar(lista, codigo, nombreOficial, origen, fecha);
        actual.setMarcaCorregida(marca);
        actual.setModeloCorregido(modelo);
        memoria.aprender(actual.getNombre(), actual.getMarca(), actual.getModelo(), lista, codigo, nombreOficial, fecha);
        if (!actual.variasMarcas()) {
            memoria.aprenderMarca(actual.getMarca(), marca);
        }
        if (!actual.variosModelos()) {
            memoria.aprenderModelo(actual.getModelo(), modelo);
        }
        if (persistir(actual)) {
            avanzar();
        }
    }

    private void posponer() {
        if (actual == null) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        actual.posponer(LocalDateTime.now().format(Almacen.FECHA));
        actual.setMarcaCorregida(txtMarca.getText().trim());
        actual.setModeloCorregido(txtModelo.getText().trim());
        if (persistir(actual)) {
            avanzar();
        }
    }

    private void volverAPendiente() {
        if (actual == null) {
            return;
        }
        if (actual.getEstado() == EstadoGrupo.REVISADO) {
            memoria.olvidar(actual.getNombre(), actual.getMarca(), actual.getModelo());
        }
        actual.reiniciar();
        actual.setMarcaCorregida(null);
        actual.setModeloCorregido(null);
        if (persistir(actual)) {
            mostrarGrupo(actual, false);
        }
    }

    private void anterior() {
        while (!historial.isEmpty()) {
            Grupo g = historial.pop();
            if (g != actual) {
                mostrarGrupo(g, false);
                return;
            }
        }
        Toolkit.getDefaultToolkit().beep();
    }

    private void saltarSiguiente() {
        if (proyecto == null) {
            return;
        }
        Grupo g = buscarSiguiente(actual);
        if (g == null) {
            Toolkit.getDefaultToolkit().beep();
        } else {
            mostrarGrupo(g, true);
        }
    }

    private void revisarPospuestos() {
        if (proyecto == null) {
            return;
        }
        Grupo g = siguienteCon(EstadoGrupo.POSPUESTO, null);
        if (g == null) {
            JOptionPane.showMessageDialog(this, "No hay grupos pospuestos.", "Pospuestos",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        cmbFiltroCola.setSelectedIndex(FILTRO_POSPUESTOS);
        mostrarGrupo(g, true);
    }

    private boolean modoPospuestos() {
        return cmbFiltroCola.getSelectedIndex() == FILTRO_POSPUESTOS;
    }

    private Grupo buscarSiguiente(Grupo desde) {
        if (modoPospuestos()) {
            Grupo g = siguienteCon(EstadoGrupo.POSPUESTO, desde);
            return g != null ? g : siguienteCon(EstadoGrupo.PENDIENTE, desde);
        }
        return siguienteCon(EstadoGrupo.PENDIENTE, desde);
    }

    /** Siguiente grupo con ese estado, empezando detrás de {@code desde} y dando la vuelta. */
    private Grupo siguienteCon(EstadoGrupo estado, Grupo desde) {
        List<Grupo> gs = proyecto.getGrupos();
        int n = gs.size();
        int inicio = desde == null ? 0 : proyecto.posicion(desde) + 1;
        for (int i = 0; i < n; i++) {
            Grupo g = gs.get((inicio + i) % n);
            if (g != desde && g.getEstado() == estado) {
                return g;
            }
        }
        return null;
    }

    private void avanzar() {
        Grupo sig = buscarSiguiente(actual);
        if (sig != null) {
            mostrarGrupo(sig, true);
            return;
        }
        int pospuestos = proyecto.contar(EstadoGrupo.POSPUESTO);
        if (actual != null) {
            historial.push(actual);
        }
        mostrarFinal();
        if (pospuestos > 0 && !modoPospuestos()) {
            int r = JOptionPane.showConfirmDialog(this, "Has terminado todos los pendientes.\n"
                            + "Quedan " + pospuestos + " grupos pospuestos (dudas). ¿Quieres revisarlos ahora?",
                    "Pendientes terminados", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r == JOptionPane.YES_OPTION) {
                revisarPospuestos();
            }
        }
    }

    /**
     * Guarda en disco el cambio de un grupo y lo aprendido (se añade una
     * línea a cada diario: es instantáneo aunque el inventario sea enorme).
     * Devuelve false si falla.
     */
    private boolean persistir(Grupo g) {
        try {
            almacen.registrarCambio(proyecto, g);
            memoria.guardarCambios();
            lblEstadoBarra.setText("✔ Guardado automáticamente a las " + LocalDateTime.now().format(HORA)
                    + "   ·   " + almacen.getBase());
            refrescarProgreso();
            refrescarCola();
            refrescarListas();
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "¡No se ha podido guardar!\n\n" + e.getMessage()
                    + "\n\nComprueba que hay espacio en disco y que la carpeta\n" + almacen.getBase()
                    + "\nse puede escribir.", "Error al guardar", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    // =====================================================================
    // Refrescos
    // =====================================================================

    private void refrescarCola() {
        if (proyecto == null) {
            return;
        }
        int filtro = cmbFiltroCola.getSelectedIndex();
        String texto = Texto.normalizar(txtFiltroCola.getText());
        List<Grupo> lista = new ArrayList<>();
        for (Grupo g : proyecto.getGrupos()) {
            boolean pasa;
            switch (filtro) {
                case FILTRO_PENDIENTES: pasa = g.getEstado() == EstadoGrupo.PENDIENTE; break;
                case FILTRO_POSPUESTOS: pasa = g.getEstado() == EstadoGrupo.POSPUESTO; break;
                case FILTRO_REVISADOS: pasa = g.getEstado() == EstadoGrupo.REVISADO; break;
                default: pasa = true;
            }
            if (pasa && !texto.isEmpty()) {
                pasa = g.textoFiltro().contains(texto);
            }
            if (pasa) {
                lista.add(g);
            }
        }
        actualizandoCola = true;
        modeloCola.poner(lista);
        actualizandoCola = false;
        seleccionarEnCola(actual);
        long equipos = 0;
        for (Grupo g : lista) {
            equipos += g.getEquipos();
        }
        lblCola.setText(Estilo.numero(lista.size()) + (lista.size() == 1 ? " grupo" : " grupos") + " · "
                + Estilo.numero(equipos) + (equipos == 1 ? " equipo" : " equipos") + " en esta vista");
    }

    private void seleccionarEnCola(Grupo g) {
        actualizandoCola = true;
        int i = g == null ? -1 : modeloCola.indice(g);
        if (i >= 0) {
            tablaCola.setRowSelectionInterval(i, i);
            tablaCola.scrollRectToVisible(tablaCola.getCellRect(i, 0, true));
        } else {
            tablaCola.clearSelection();
        }
        actualizandoCola = false;
    }

    private void refrescarProgreso() {
        if (proyecto == null) {
            lblProgreso.setText(" ");
            return;
        }
        int total = proyecto.getGrupos().size();
        int revisados = proyecto.contar(EstadoGrupo.REVISADO);
        int pospuestos = proyecto.contar(EstadoGrupo.POSPUESTO);
        long ut = proyecto.equiposTotales();
        long ur = proyecto.equipos(EstadoGrupo.REVISADO);
        double pct = ut == 0 ? 0 : ur * 100.0 / ut;
        String pctTexto = pct > 0 && pct < 1 ? String.format("%.1f", pct) : String.valueOf((int) Math.floor(pct));
        barraProgreso.setValue(ut == 0 ? 0 : (int) (ur * 1000L / ut));
        lblProgreso.setText("<html><b>" + Estilo.numero(revisados) + "</b> de " + Estilo.numero(total)
                + " grupos revisados &nbsp;·&nbsp; <font color='" + Estilo.hex(Estilo.NARANJA) + "'>"
                + Estilo.numero(pospuestos) + " dudas</font> &nbsp;·&nbsp; <b>" + pctTexto
                + "%</b> de los equipos</html>");
    }

    private void refrescarListas() {
        int g = catalogos.total(TipoLista.GMDN);
        int m = catalogos.total(TipoLista.MANTENIMIENTO);
        Catalogos.Catalogo cg = catalogos.get(TipoLista.GMDN);
        Catalogos.Catalogo cm = catalogos.get(TipoLista.MANTENIMIENTO);
        int nuevas = cm == null ? 0 : cm.creadasEnPrograma();
        lblListas.setText("Catálogos: GMDN " + (g == 0 ? "sin cargar" : Estilo.numero(g)) + "  ·  Mantenimiento "
                + (m == 0 ? "sin cargar" : Estilo.numero(m) + (nuevas > 0 ? " (" + nuevas + " nuevas)" : ""))
                + "  ·  Memoria: " + Estilo.numero(memoria.tamano()) + " clasificaciones aprendidas");
        bienvenida.actualizar(
                cg == null ? "sin cargar" : Estilo.numero(g) + " términos (" + cg.getOrigen() + ")", cg != null,
                cm == null ? "sin cargar" : Estilo.numero(m) + " entradas (" + cm.getOrigen() + ")"
                        + (nuevas > 0 ? " · " + nuevas + " creadas aquí" : ""), cm != null,
                memoria.tamano() == 0 ? "La memoria está vacía: aprenderá de cada equipo que clasifiques."
                        : "Memoria: " + Estilo.numero(memoria.tamano()) + " clasificaciones aprendidas para hacer sugerencias.");
        refrescarPanelResultados();
    }

    /** Muestra la tabla de resultados, o un aviso si el catálogo elegido no está cargado. */
    private void refrescarPanelResultados() {
        if (catalogos.total(listaBuscador) == 0) {
            lblSinCatalogo.setText("Todavía no has cargado el " + listaBuscador.largo() + ".");
            cartasResultados.show(panelResultados, "sinlistas");
        } else {
            cartasResultados.show(panelResultados, "tabla");
        }
    }

    private void enfocarBuscador() {
        if (txtBuscar.isEnabled()) {
            txtBuscar.requestFocusInWindow();
            txtBuscar.selectAll();
        }
    }

    private void enfocar(JTextField campo) {
        if (campo.isEnabled()) {
            campo.requestFocusInWindow();
            campo.selectAll();
        }
    }

    // =====================================================================
    // Importar / exportar
    // =====================================================================

    private void importarInventario() {
        File f = elegirFicheroAbrir("Elige el inventario del hospital (Excel .xlsx o .csv)");
        if (f != null) {
            importarInventario(f);
        }
    }

    private void importarInventario(File f) {
        Espera.ejecutar(this, "Buscando las columnas en " + f.getName() + "…",
                () -> ImportadorInventario.detectar(f), mapeo -> {
                    if (mapeo != null && mapeo.valido()) {
                        confirmarInventario(f, mapeo);
                    } else {
                        mapearInventarioAMano(f);
                    }
                });
    }

    /** Las cabeceras se han reconocido: se enseña lo encontrado y se pide confirmación. */
    private void confirmarInventario(File f, ImportadorInventario.Mapeo m) {
        StringBuilder b = new StringBuilder("<html><div style='width:470px'>Se han reconocido las columnas del "
                + "inventario (cabeceras en la fila " + (m.filaCabecera + 1) + "):<br><br><table cellpadding='1'>");
        for (int i = 0; i < ImportadorInventario.CABECERAS.length; i++) {
            boolean ok = m.col[i] >= 0;
            b.append("<tr><td><font color='").append(Estilo.hex(ok ? Estilo.VERDE : Estilo.NARANJA)).append("'>")
                    .append(ok ? "✓" : "✗").append("</font></td><td><b>").append(ImportadorInventario.CABECERAS[i])
                    .append("</b></td><td><font color='#667085'>").append(ImportadorInventario.ETIQUETAS[i])
                    .append(ok ? "" : " — no está").append("</font></td></tr>");
        }
        b.append("</table><br>");
        if (m.col[ImportadorInventario.BAJA] >= 0) {
            b.append("Se descartarán los equipos con <b>Equipo_debaja = S</b>; solo se catalogan los que tienen <b>N</b>.");
        } else {
            b.append("<font color='").append(Estilo.hex(Estilo.NARANJA)).append("'>No hay columna Equipo_debaja: ")
                    .append("se catalogarán todos los equipos.</font>");
        }
        b.append("</div></html>");
        JCheckBox agrupar = new JCheckBox("Separar también por marca y modelo (recomendado)", true);
        agrupar.setToolTipText("Si lo desmarcas, se juntan todos los equipos con el mismo nombre aunque sean de marcas distintas");
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.add(new JLabel(b.toString()), BorderLayout.CENTER);
        panel.add(agrupar, BorderLayout.SOUTH);
        Object[] botones = {"Importar", "Elegir las columnas a mano…", "Cancelar"};
        int r = JOptionPane.showOptionDialog(this, panel, "Importar inventario: " + f.getName(),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, botones, botones[0]);
        if (r == 0) {
            crearProyecto(f, m, agrupar.isSelected());
        } else if (r == 1) {
            mapearInventarioAMano(f);
        }
    }

    /** Campos del inventario para elegir las columnas a mano, con los títulos habituales de cada uno. */
    static List<DialogoImportacion.Campo> camposInventario() {
        List<DialogoImportacion.Campo> campos = new ArrayList<>();
        // Además de la cabecera oficial, títulos habituales para proponer la columna
        String[][] sinonimos = {
                {"codicentro", "centro", "cod centro", "codigo centro"},
                {"idequipo", "id equipo", "n equipo", "numero equipo", "id", "codigo equipo"},
                {"denominacion", "nombre equipo", "nombre", "descripcion", "equipo"},
                {"referencia", "ref"},
                {"marca", "fabricante"},
                {"modelo"},
                {"tipo activo", "codi tipo activo", "tipo"},
                {"debaja", "de baja", "baja"}};
        for (int i = 0; i < ImportadorInventario.CABECERAS.length; i++) {
            String cab = ImportadorInventario.CABECERAS[i];
            String[] claves = new String[sinonimos[i].length + 2];
            claves[0] = cab;
            claves[1] = cab.replace('_', ' ');
            System.arraycopy(sinonimos[i], 0, claves, 2, sinonimos[i].length);
            campos.add(new DialogoImportacion.Campo(String.valueOf(i), ImportadorInventario.ETIQUETAS[i],
                    ImportadorInventario.OBLIGATORIAS[i], cab, claves));
        }
        return campos;
    }

    /** No se han reconocido las cabeceras: se eligen las columnas viendo las primeras filas. */
    private void mapearInventarioAMano(File f) {
        Espera.ejecutar(this, "Leyendo las primeras filas de " + f.getName() + "…", () -> {
            List<catalogador.excel.Hoja> hojas = new ArrayList<>();
            int n = LectorTablas.hojas(f).size();
            for (int i = 0; i < n; i++) {
                hojas.add(LectorTablas.leerHoja(f, i, 200));
            }
            return hojas;
        }, hojas -> {
            JCheckBox agrupar = new JCheckBox("Separar también por marca y modelo (recomendado)", true);
            List<DialogoImportacion.Campo> campos = camposInventario();
            DialogoImportacion d = new DialogoImportacion(this, "Importar inventario",
                    "No se han encontrado todas las cabeceras esperadas (Equipo_codiCentro, Equipo_idEquipo, "
                            + "Equipo_nombre…). Indica qué columna contiene cada dato. Se muestran las primeras filas.",
                    f, hojas, campos, agrupar);
            d.setVistaPrevia(true);
            if (!d.mostrar()) {
                return;
            }
            ImportadorInventario.Mapeo m = new ImportadorInventario.Mapeo();
            m.hoja = hojas.indexOf(d.hoja());
            m.filaCabecera = d.filaCabecera();
            for (int i = 0; i < m.col.length; i++) {
                m.col[i] = d.columna(String.valueOf(i));
            }
            crearProyecto(f, m, agrupar.isSelected());
        });
    }

    private void crearProyecto(File f, ImportadorInventario.Mapeo m, boolean agrupar) {
        Espera.ejecutar(this, "Leyendo el inventario " + f.getName() + "…", "filas", avance -> {
            Proyecto p = ImportadorInventario.importar(f, m, agrupar, avance);
            if (p.numEquipos() == 0) {
                throw new IOException("No hay ningún equipo que catalogar en " + f.getName() + ".\n\n"
                        + "Filas leídas: " + p.getFilasLeidas() + " · de baja: " + p.getDescartadosBaja()
                        + " · con otro valor en Equipo_debaja: " + p.getDescartadosOtros());
            }
            avance.fase("Guardando el proyecto…", null);
            p.setNombre(f.getName().replaceFirst("\\.[^.]+$", ""));
            p.setFicheroOrigen(f.getName());
            p.setCreado(LocalDateTime.now().format(Almacen.FECHA));
            p.setCarpeta(almacen.nuevaCarpetaProyecto(p.getNombre()));
            almacen.guardarInventario(p);
            almacen.guardarEstado(p);
            return p;
        }, listo -> {
            abrirProyecto(listo);
            StringBuilder b = new StringBuilder("<html><div style='width:430px'>Inventario importado.<br><br>");
            b.append("Filas leídas: <b>").append(Estilo.numero(listo.getFilasLeidas())).append("</b><br>");
            if (listo.getDescartadosBaja() > 0) {
                b.append("De baja (Equipo_debaja = S), descartadas: <b>")
                        .append(Estilo.numero(listo.getDescartadosBaja())).append("</b><br>");
            }
            if (listo.getDescartadosOtros() > 0) {
                b.append("<font color='").append(Estilo.hex(Estilo.NARANJA)).append("'>Con Equipo_debaja vacío o distinto ")
                        .append("de S/N, descartadas: <b>").append(Estilo.numero(listo.getDescartadosOtros()))
                        .append("</b></font><br>");
            }
            b.append("Equipos a catalogar: <b>").append(Estilo.numero(listo.numEquipos())).append("</b>, en <b>")
                    .append(Estilo.numero(listo.getGrupos().size())).append("</b> grupos de equipos iguales y <b>")
                    .append(listo.centros().size()).append("</b> ").append(listo.centros().size() == 1 ? "centro" : "centros")
                    .append(".<br><br>Empezamos por los grupos con más equipos.</div></html>");
            JOptionPane.showMessageDialog(this, b.toString(), "Proyecto creado", JOptionPane.INFORMATION_MESSAGE);
            if (catalogos.total() == 0) {
                JOptionPane.showMessageDialog(this, "Recuerda importar también los catálogos oficiales\n"
                                + "(menú Catálogos → Importar catálogos) para poder buscar los nombres.",
                        "Faltan los catálogos", JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    /** Importa el Excel de catálogos (hojas GMDN y Mantenimiento) y sustituye los que haya. */
    private void importarCatalogos() {
        File f = elegirFicheroAbrir("Elige el Excel de catálogos (hojas GMDN y Mantenimiento)");
        if (f != null) {
            importarCatalogos(f);
        }
    }

    private void importarCatalogos(File f) {
        Espera.ejecutar(this, "Leyendo " + f.getName() + "…", () -> ImportadorCatalogos.leer(f), r -> {
            StringBuilder b = new StringBuilder("<html><div style='width:460px'>En <b>" + Texto.html(f.getName())
                    + "</b> se ha encontrado:<br><br>");
            if (r.gmdn != null) {
                b.append("• <b>GMDN</b> (hoja «").append(Texto.html(r.hojaGmdn)).append("»): <b>")
                        .append(Estilo.numero(r.gmdn.size())).append("</b> términos");
                if (r.gmdnRepetidos > 0) {
                    b.append(" · ").append(r.gmdnRepetidos).append(" filas con código repetido, omitidas");
                }
                if (r.gmdnIncompletos > 0) {
                    b.append(" · ").append(r.gmdnIncompletos).append(" sin código o sin nombre, omitidas");
                }
                b.append(catalogos.get(TipoLista.GMDN) != null ? "<br>&nbsp;&nbsp;&nbsp;Sustituirá al catálogo GMDN actual ("
                        + Estilo.numero(catalogos.total(TipoLista.GMDN)) + " términos)." : "").append("<br>");
            }
            if (r.mantenimiento != null) {
                b.append("• <b>Mantenimiento</b> (hoja «").append(Texto.html(r.hojaMantenimiento)).append("»): <b>")
                        .append(Estilo.numero(r.mantenimiento.size())).append("</b> entradas");
                if (r.mantenimientoSinNombre > 0) {
                    b.append(" · ").append(r.mantenimientoSinNombre).append(" sin nombre, omitidas");
                }
                Catalogos.Catalogo cm = catalogos.get(TipoLista.MANTENIMIENTO);
                if (cm != null) {
                    b.append("<br>&nbsp;&nbsp;&nbsp;Sustituirá al catálogo actual (").append(Estilo.numero(cm.getTerminos().size()))
                            .append(" entradas)");
                    if (cm.creadasEnPrograma() > 0) {
                        b.append("; se conservan las entradas creadas en el programa que no vengan en el fichero");
                    }
                    b.append(".");
                }
                if (!r.nombresRepetidos.isEmpty()) {
                    b.append("<br><font color='").append(Estilo.hex(Estilo.NARANJA)).append("'>&nbsp;&nbsp;&nbsp;Atención: ")
                            .append(r.nombresRepetidos.size()).append(" nombres aparecen más de una vez (")
                            .append(Texto.html(Texto.recortar(String.join(", ", r.nombresRepetidos), 120)))
                            .append("). Al asignarlos se usará la primera.</font>");
                }
                b.append("<br>");
            }
            if (r.gmdn == null || r.mantenimiento == null) {
                b.append("<br><font color='").append(Estilo.hex(Estilo.NARANJA)).append("'>No hay hoja de ")
                        .append(r.gmdn == null ? "GMDN" : "Mantenimiento").append(": ese catálogo no se cambia.</font><br>");
            }
            b.append("<br>¿Importar?</div></html>");
            int ok = JOptionPane.showConfirmDialog(this, b.toString(), "Importar catálogos", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return;
            }
            Espera.ejecutar(this, "Guardando los catálogos…", () -> {
                int conservadas = 0;
                if (r.gmdn != null) {
                    catalogos.reemplazar(TipoLista.GMDN, r.gmdn, f.getName(), r.hojaGmdn);
                }
                if (r.mantenimiento != null) {
                    conservadas = catalogos.reemplazar(TipoLista.MANTENIMIENTO, r.mantenimiento, f.getName(),
                            r.hojaMantenimiento);
                }
                return conservadas;
            }, conservadas -> {
                refrescarListas();
                lanzarBusqueda();
                if (actual != null) {
                    pintarSugerencias();
                }
                String m = "Catálogos importados: GMDN " + Estilo.numero(catalogos.total(TipoLista.GMDN))
                        + " términos · Mantenimiento " + Estilo.numero(catalogos.total(TipoLista.MANTENIMIENTO))
                        + " entradas." + (conservadas > 0 ? "\n\nSe han conservado " + conservadas
                        + " entradas creadas en el programa que no estaban en el fichero." : "");
                JOptionPane.showMessageDialog(this, m, "Catálogos importados", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    /**
     * Aplica al proyecto abierto un resultado exportado antes (Equipo_codiCentro,
     * Equipo_idEquipo, CÓDIGO GMDN, NOMBRE…), cruzando por centro + identificador.
     */
    private void importarResultadoAnterior() {
        if (proyecto == null) {
            JOptionPane.showMessageDialog(this, "<html>Primero abre el proyecto del inventario.<br><br>El resultado no "
                            + "trae el nombre de los equipos: se cruza con el inventario por centro e identificador.</html>",
                    "Importar resultado anterior", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File f = elegirFicheroAbrir("Elige un resultado de catalogación anterior (Excel o CSV)");
        if (f == null) {
            return;
        }
        String fecha = LocalDateTime.now().format(Almacen.FECHA);
        Espera.ejecutar(this, "Aplicando " + f.getName() + "…", "filas", avance -> {
            ImportadorResultados.Resumen r = ImportadorResultados.aplicar(f, proyecto, catalogos, memoria,
                    "Importado de " + f.getName(), fecha, avance);
            avance.fase("Guardando…", null);
            if (!r.cambiados.isEmpty()) {
                almacen.registrarCambios(proyecto, r.cambiados);
            }
            memoria.guardarCambios();
            return r;
        }, r -> {
            refrescarProgreso();
            refrescarCola();
            refrescarListas();
            if (actual != null) {
                mostrarGrupo(actual, false);
            }
            StringBuilder b = new StringBuilder("<html><div style='width:440px'>");
            b.append("Filas leídas: <b>").append(Estilo.numero(r.filas)).append("</b> (con clasificación: ")
                    .append(Estilo.numero(r.conClasificacion)).append(")<br>");
            if (r.sinEquipo > 0) {
                b.append("Equipos que no están en este proyecto: ").append(Estilo.numero(r.sinEquipo)).append("<br>");
            }
            if (r.fueraDeCatalogo > 0) {
                b.append("<font color='").append(Estilo.hex(Estilo.NARANJA)).append("'>Términos que no están en los ")
                        .append("catálogos cargados: ").append(Estilo.numero(r.fueraDeCatalogo)).append("</font><br>");
            }
            b.append("<br>Grupos marcados como revisados: <b>").append(Estilo.numero(r.gruposRevisados)).append("</b><br>");
            if (r.gruposYaRevisados > 0) {
                b.append("Grupos que ya estaban revisados o en duda (no se tocan): ")
                        .append(Estilo.numero(r.gruposYaRevisados)).append("<br>");
            }
            if (r.gruposEnConflicto > 0) {
                b.append("<font color='").append(Estilo.hex(Estilo.NARANJA)).append("'>Grupos con clasificaciones ")
                        .append("distintas entre sus equipos (se dejan como estaban): ")
                        .append(Estilo.numero(r.gruposEnConflicto)).append("</font><br>");
            }
            b.append("Clasificaciones aprendidas para sugerir: <b>").append(Estilo.numero(r.aprendidas)).append("</b>");
            b.append("</div></html>");
            JOptionPane.showMessageDialog(this, b.toString(), "Resultado anterior aplicado",
                    JOptionPane.INFORMATION_MESSAGE);
        });
    }

    /** Aprende de una tabla con el nombre original del equipo y el término oficial que se le dio. */
    private void importarClasificaciones() {
        File f = elegirFicheroAbrir("Elige un Excel con clasificaciones ya hechas (nombre del equipo → nombre oficial)");
        if (f == null) {
            return;
        }
        Espera.ejecutar(this, "Leyendo " + f.getName() + "…", () -> LectorTablas.leer(f), hojas -> {
            List<DialogoImportacion.Campo> campos = Arrays.asList(
                    new DialogoImportacion.Campo("nombre", "Nombre original del equipo", true, "Como venía en el inventario",
                            "equipo_nombre", "equipo nombre", "nombre original", "denominacion", "nombre", "equipo"),
                    new DialogoImportacion.Campo("nombreOficial", "Nombre oficial asignado", true,
                            "Nombre GMDN o nombre de mantenimiento", "nombre gmdn y nombre mantenimiento",
                            "nombre oficial", "nombre gmdn", "nombre mantenimiento", "termino oficial"),
                    new DialogoImportacion.Campo("codigo", "Código GMDN", false,
                            "Vacío = es del catálogo de Mantenimiento", "codigo gmdn", "cod gmdn", "gmdn", "codigo"),
                    new DialogoImportacion.Campo("marca", "Marca", false, "Opcional", "refmarca_nombre", "marca",
                            "fabricante"),
                    new DialogoImportacion.Campo("modelo", "Modelo", false, "Opcional", "equipo_modelo", "modelo",
                            "model"));
            DialogoImportacion d = new DialogoImportacion(this, "Aprender de clasificaciones anteriores",
                    "El programa aprenderá de estas clasificaciones para hacer sugerencias. Si hay código GMDN, "
                            + "se toma del catálogo GMDN; si no, el nombre se busca en el de Mantenimiento.",
                    f, hojas, campos, null);
            if (!d.mostrar()) {
                return;
            }
            int aprendidas = 0;
            int desconocidas = 0;
            String fecha = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            for (String[] fila : d.filasDatos()) {
                String nombre = celda(fila, d.columna("nombre"));
                String oficial = celda(fila, d.columna("nombreOficial"));
                String codigo = celda(fila, d.columna("codigo"));
                if (nombre.isEmpty() || (oficial.isEmpty() && codigo.isEmpty())) {
                    continue;
                }
                TipoLista lista = codigo.isEmpty() ? TipoLista.MANTENIMIENTO : TipoLista.GMDN;
                TerminoOficial t = catalogos.porCodigo(lista, codigo.isEmpty() ? oficial : codigo);
                if (t == null) {
                    desconocidas++;
                } else {
                    oficial = t.getNombre();
                    codigo = t.getCodigo();
                }
                if (lista == TipoLista.MANTENIMIENTO) {
                    codigo = oficial;
                }
                memoria.aprender(nombre, celda(fila, d.columna("marca")), celda(fila, d.columna("modelo")),
                        lista, codigo, oficial, fecha);
                aprendidas++;
            }
            try {
                memoria.guardarCambios();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "No se pudo guardar la memoria: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            refrescarListas();
            if (actual != null) {
                sugerencias = calcularSugerencias(actual);
                pintarSugerencias();
            }
            JOptionPane.showMessageDialog(this, "Se han aprendido " + Estilo.numero(aprendidas) + " clasificaciones."
                            + (desconocidas > 0 ? "\n(" + Estilo.numero(desconocidas)
                            + " no están en los catálogos cargados; se aprenden igualmente.)" : ""),
                    "Memoria actualizada", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private static String celda(String[] fila, int col) {
        return col >= 0 && col < fila.length && fila[col] != null ? fila[col].trim() : "";
    }

    private void exportar() {
        if (proyecto == null) {
            JOptionPane.showMessageDialog(this, "Primero abre o crea un proyecto.", "Exportar",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (opcionesExportar == null) {
            opcionesExportar = new Exportador.Opciones();
            Path escritorio = catalogador.util.Ficheros.escritorio();
            opcionesExportar.carpeta = new File(config.getProperty("carpetaExportar",
                    Files.isDirectory(escritorio) ? escritorio.toString() : System.getProperty("user.home")));
            opcionesExportar.csv = "csv".equals(config.getProperty("formatoExportar"));
        }
        opcionesExportar.prefijo = "Catalogacion_" + proyecto.getNombre().replaceAll("[\\\\/:*?\"<>|\\s\\p{Cntrl}]+", "_") + "_"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Exportador.Opciones o = DialogoExportar.mostrar(this, proyecto, opcionesExportar);
        if (o == null) {
            return;
        }
        opcionesExportar = o;
        config.setProperty("carpetaExportar", o.carpeta.getAbsolutePath());
        config.setProperty("formatoExportar", o.csv ? "csv" : "xlsx");
        almacen.guardarConfig(config);
        Espera.ejecutar(this, "Generando " + (o.csv ? "los CSV" : "los Excel") + " por centro…", "equipos",
                avance -> Exportador.exportar(proyecto, o, avance), creados -> {
                    long filas = 0;
                    for (Exportador.Fichero x : creados) {
                        filas += x.getFilas();
                    }
                    int abrir = JOptionPane.showConfirmDialog(this, "Se han creado " + creados.size()
                                    + (creados.size() == 1 ? " fichero" : " ficheros") + " (" + Estilo.numero(filas)
                                    + " equipos) en:\n" + o.carpeta.getAbsolutePath() + "\n\n¿Quieres abrir la carpeta?",
                            "Exportado", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                    if (abrir == JOptionPane.YES_OPTION) {
                        abrirEnSistema(o.carpeta);
                    }
                });
    }

    /** Exporta los catálogos con el mismo formato que el Excel original (con las entradas nuevas). */
    private void exportarCatalogos() {
        if (catalogos.total() == 0) {
            JOptionPane.showMessageDialog(this, "No hay catálogos cargados.", "Exportar catálogos",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String sugerido = "Catalogos_actualizados_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                + ".xlsx";
        File f = elegirFicheroGuardar("Guardar los catálogos", sugerido);
        if (f == null) {
            return;
        }
        if (!f.getName().toLowerCase().endsWith(".xlsx")) {
            f = new File(f.getParentFile(), f.getName() + ".xlsx");
        }
        final File destino = f;
        Espera.ejecutar(this, "Generando el Excel de catálogos…", () -> {
            ExportadorCatalogo.exportar(catalogos, Arrays.asList(TipoLista.values()), destino);
            return destino;
        }, hecho -> {
            Catalogos.Catalogo cm = catalogos.get(TipoLista.MANTENIMIENTO);
            int nuevas = cm == null ? 0 : cm.creadasEnPrograma();
            int abrir = JOptionPane.showConfirmDialog(this, "Catálogos guardados en:\n" + hecho.getAbsolutePath()
                            + (nuevas > 0 ? "\n\nIncluye " + nuevas + " entradas de mantenimiento creadas en el programa." : "")
                            + "\n\n¿Quieres abrirlo ahora?", "Exportado", JOptionPane.YES_NO_OPTION,
                    JOptionPane.INFORMATION_MESSAGE);
            if (abrir == JOptionPane.YES_OPTION) {
                abrirEnSistema(hecho);
            }
        });
    }

    private void nuevaEntradaMantenimiento() {
        String sugerido = actual != null ? actual.getNombre() : txtBuscar.getText();
        TerminoOficial t = DialogoEntradaMantenimiento.crear(this, catalogos, sugerido);
        if (t != null) {
            trasCambiarCatalogo(t);
        }
    }

    private void corregirEntradaMantenimiento() {
        TerminoOficial sel = terminoSeleccionado();
        if (sel == null || sel.getLista() != TipoLista.MANTENIMIENTO) {
            JOptionPane.showMessageDialog(this, "Selecciona primero una entrada del catálogo de Mantenimiento en el buscador.",
                    "Corregir entrada", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        TerminoOficial t = DialogoEntradaMantenimiento.modificar(this, catalogos, sel);
        if (t != null) {
            trasCambiarCatalogo(t);
        }
    }

    /** Tras crear o corregir una entrada: se busca y se deja seleccionada, lista para guardar con Intro. */
    private void trasCambiarCatalogo(TerminoOficial t) {
        refrescarListas();
        cambiarLista(TipoLista.MANTENIMIENTO, false);
        cambioProgramatico = true;
        txtBuscar.setText(t.getNombre());
        cambioProgramatico = false;
        Catalogos.Busqueda b = catalogos.buscar(t.getNombre(), TipoLista.MANTENIMIENTO, false, MAX_RESULTADOS);
        mostrarResultados(b, t.getNombre(), false);
        int i = modeloResultados.indice(t);
        if (i >= 0) {
            tablaResultados.setRowSelectionInterval(i, i);
            tablaResultados.scrollRectToVisible(tablaResultados.getCellRect(i, 0, true));
        }
        txtBuscar.requestFocusInWindow();
        lblEstadoBarra.setText("✔ Entrada «" + t.getNombre() + "» guardada en el catálogo de Mantenimiento"
                + (actual != null ? ". Pulsa Intro para asignarla." : "."));
    }

    private void elegirProyecto() {
        List<Almacen.InfoProyecto> lista = almacen.listarProyectos();
        if (lista.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Todavía no hay proyectos guardados.\nCrea uno desde Archivo → Nuevo proyecto.",
                    "Abrir proyecto", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JList<Almacen.InfoProyecto> jl = new JList<>(lista.toArray(new Almacen.InfoProyecto[0]));
        jl.setSelectedIndex(0);
        jl.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foco) {
                super.getListCellRendererComponent(l, v, i, sel, foco);
                Almacen.InfoProyecto p = (Almacen.InfoProyecto) v;
                setText("<html><b>" + Texto.html(p.getNombre()) + "</b>"
                        + (p.getEquipos() >= 0 ? " &nbsp;<font color='#667085'>" + Estilo.numero(p.getEquipos())
                        + " equipos</font>" : "") + "<br><font size='-1'>Fichero: "
                        + Texto.html(p.getOrigen()) + " · creado " + Texto.html(p.getCreado())
                        + " · último cambio " + Texto.html(p.getModificado()) + "</font></html>");
                setBorder(Estilo.relleno(6, 8, 6, 8));
                return this;
            }
        });
        JScrollPane sp = new JScrollPane(jl);
        sp.setPreferredSize(new Dimension(560, 300));
        int r = JOptionPane.showConfirmDialog(this, sp, "Abrir proyecto guardado", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        Almacen.InfoProyecto elegido = jl.getSelectedValue();
        if (r != JOptionPane.OK_OPTION || elegido == null) {
            return;
        }
        Espera.ejecutar(this, "Abriendo el proyecto «" + elegido.getNombre() + "»…", "equipos",
                avance -> almacen.abrir(elegido.getCarpeta(), avance), this::abrirProyecto);
    }

    private void abrirCarpetaDatos() {
        abrirEnSistema(almacen.getBase().toFile());
    }

    private void abrirEnSistema(File f) {
        if (!catalogador.util.Ficheros.abrir(f)) {
            JOptionPane.showMessageDialog(this, "No se pudo abrir automáticamente. Está en:\n" + f.getAbsolutePath(),
                    "Aviso", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ---- Selección de ficheros (en Mac se usa el diálogo nativo)

    private File elegirFicheroAbrir(String titulo) {
        String carpeta = config.getProperty("ultimaCarpeta", System.getProperty("user.home"));
        File f;
        if (Estilo.MAC) {
            FileDialog fd = new FileDialog(this, titulo, FileDialog.LOAD);
            fd.setDirectory(carpeta);
            fd.setVisible(true);
            f = fd.getFile() == null ? null : new File(fd.getDirectory(), fd.getFile());
        } else {
            JFileChooser fc = new JFileChooser(carpeta);
            fc.setDialogTitle(titulo);
            fc.setFileFilter(new FileNameExtensionFilter("Excel o CSV (*.xlsx, *.csv)", "xlsx", "xlsm", "csv", "txt"));
            f = fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
        }
        if (f != null && f.getParentFile() != null) {
            config.setProperty("ultimaCarpeta", f.getParentFile().getAbsolutePath());
            almacen.guardarConfig(config);
        }
        return f;
    }

    private File elegirFicheroGuardar(String titulo, String nombreSugerido) {
        Path escritorio = catalogador.util.Ficheros.escritorio();
        String carpeta = config.getProperty("carpetaExportar",
                Files.isDirectory(escritorio) ? escritorio.toString() : System.getProperty("user.home"));
        File f;
        if (Estilo.MAC) {
            FileDialog fd = new FileDialog(this, titulo, FileDialog.SAVE);
            fd.setDirectory(carpeta);
            fd.setFile(nombreSugerido);
            fd.setVisible(true);
            f = fd.getFile() == null ? null : new File(fd.getDirectory(), fd.getFile());
        } else {
            JFileChooser fc = new JFileChooser(carpeta);
            fc.setDialogTitle(titulo);
            fc.setSelectedFile(new File(carpeta, nombreSugerido));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return null;
            }
            f = fc.getSelectedFile();
            if (f.exists() && JOptionPane.showConfirmDialog(this, "Ya existe " + f.getName() + ". ¿Sustituirlo?",
                    "Sustituir", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return null;
            }
        }
        if (f != null && f.getParentFile() != null) {
            config.setProperty("carpetaExportar", f.getParentFile().getAbsolutePath());
            almacen.guardarConfig(config);
        }
        return f;
    }

    // =====================================================================
    // Modo IA
    // =====================================================================

    /** Texto del botón de modo de la cabecera y estado de los menús de IA. */
    private void actualizarModo() {
        boolean ia = configIA.getModo() == ConfigIA.Modo.IA;
        if (ia) {
            btnModo.setText("Modo IA · " + configIA.resumen() + (pausaIA != null ? " (en pausa)" : "") + "  ▾");
            btnModo.setForeground(pausaIA != null || asistente == null ? Estilo.NARANJA : Estilo.MORADO);
        } else {
            btnModo.setText("Modo manual  ▾");
            btnModo.setForeground(Estilo.TEXTO_SUAVE);
        }
        for (JMenuItem m : accionesIA) {
            m.setEnabled(asistente != null || m.getText().startsWith("Borrar") || m.getText().startsWith("Consumo"));
        }
    }

    private void abrirModo() {
        if (DialogoModo.mostrar(this, configIA, false)) {
            prepararIA();
        }
    }

    /** Crea el asistente de IA según la configuración (o lo quita en modo manual). */
    private void prepararIA() {
        pausaIA = null;
        erroresIA.clear();
        asistente = null;
        if (configIA.getModo() == ConfigIA.Modo.IA) {
            if (configIA.estaListo(configIA.getProveedor())) {
                asistente = new AsistenteIA(ClienteIA.desde(configIA, configIA.getProveedor()), catalogos, memoria);
                lblEstadoBarra.setText("Modo IA con " + configIA.resumen() + ". Las propuestas se guardan en el proyecto.");
            } else {
                pausaIA = "falta la clave o el modelo del servicio (menú IA → Modo de trabajo).";
            }
        }
        actualizarModo();
        if (actual != null) {
            sugerencias = calcularSugerencias(actual);
            pintarSugerencias();
            pintarEstadoIA();
            pedirIA(actual);
        }
    }

    /** Sugerencias de la memoria y, en modo IA, la propuesta guardada de la IA. */
    private List<Sugerencia> calcularSugerencias(Grupo g) {
        List<Sugerencia> deMemoria = memoria.sugerir(g.getNombre(), g.getMarca(), g.getModelo(), 3);
        propuestaActual = asistente == null || propuestas == null ? null : propuestas.get(g.getClave());
        if (propuestaActual == null || !propuestaActual.tieneTermino()) {
            return deMemoria;
        }
        Sugerencia ia = Sugerencia.deIA(propuestaActual.getLista(), propuestaActual.getCodigo(),
                propuestaActual.getNombreOficial(), propuestaActual.getConfianza(), propuestaActual.getMotivo(),
                propuestaActual.getFuente());
        return Sugerencia.combinar(deMemoria, ia, 3);
    }

    /** Línea de estado de la IA bajo la sugerencia, y la marca/modelo que propone. */
    private void pintarEstadoIA() {
        btnMarcaIA.setVisible(false);
        if (configIA.getModo() != ConfigIA.Modo.IA || actual == null) {
            lblIA.setVisible(false);
            return;
        }
        int ancho = 265;
        String clave = actual.getClave();
        String texto;
        Color color = Estilo.TEXTO_SUAVE;
        if (pausaIA != null) {
            texto = "<b>IA en pausa:</b> " + Texto.html(Texto.recortar(pausaIA.split("\n")[0], 200));
            color = Estilo.NARANJA;
        } else if (asistente == null) {
            texto = "La IA no está configurada.";
        } else if (actual.getEstado() == EstadoGrupo.REVISADO && propuestaActual == null) {
            texto = "Grupo ya revisado: no se consulta a la IA.";
        } else if (pidiendoIA.contains(clave)) {
            texto = "Consultando a la IA (" + Texto.html(asistente.getCliente().descripcion()) + ")…";
            color = Estilo.MORADO;
        } else if (erroresIA.containsKey(clave)) {
            texto = "<b>La IA no ha podido responder:</b> " + Texto.html(Texto.recortar(erroresIA.get(clave).split("\n")[0], 160))
                    + " (" + Estilo.atajo("⇧I") + " para reintentar)";
            color = Estilo.ROJO;
        } else if (propuestaActual == null) {
            texto = "La IA todavía no ha visto este grupo.";
        } else if (!propuestaActual.tieneTermino()) {
            texto = "<b>La IA no encuentra un término adecuado:</b> " + Texto.html(Texto.recortar(propuestaActual.getMotivo(), 160));
            color = Estilo.NARANJA;
        } else {
            boolean primera = !sugerencias.isEmpty() && sugerencias.get(0).esDeIA();
            texto = (primera ? "Propuesta de " : "La propuesta (" + propuestaActual.getConfianza()
                    + " %) está en «Otras posibilidades». IA: ") + Texto.html(propuestaActual.getFuente())
                    + ". " + Estilo.atajo("I") + " la acepta.";
        }
        lblIA.setForeground(color);
        lblIA.setText(Estilo.envolver(texto, ancho));
        lblIA.setVisible(true);

        // Marca y modelo limpios que propone la IA (si son distintos de lo que hay)
        if (propuestaActual != null && asistente != null) {
            String m = propuestaActual.getMarca();
            String mo = propuestaActual.getModelo();
            boolean marcaNueva = !m.isEmpty() && !m.equals(txtMarca.getText().trim());
            boolean modeloNuevo = !mo.isEmpty() && !mo.equals(txtModelo.getText().trim());
            if (marcaNueva || modeloNuevo) {
                btnMarcaIA.setText("<html>IA propone " + (marcaNueva ? "marca «<b>" + Texto.html(Texto.recortar(m, 30)) + "</b>»" : "")
                        + (marcaNueva && modeloNuevo ? " y " : "") + (modeloNuevo ? "modelo «<b>"
                        + Texto.html(Texto.recortar(mo, 30)) + "</b>»" : "") + " — <u>usar</u></html>");
                btnMarcaIA.setVisible(true);
            }
        }
    }

    private void usarMarcaModeloIA() {
        if (propuestaActual == null) {
            return;
        }
        if (!propuestaActual.getMarca().isEmpty()) {
            txtMarca.setText(propuestaActual.getMarca());
        }
        if (!propuestaActual.getModelo().isEmpty()) {
            txtModelo.setText(propuestaActual.getModelo());
        }
        btnMarcaIA.setVisible(false);
        enfocarBuscador();
    }

    private boolean necesitaIA(Grupo g) {
        return g.getEstado() != EstadoGrupo.REVISADO && propuestas != null && !propuestas.tiene(g.getClave())
                && !pidiendoIA.contains(g.getClave()) && !erroresIA.containsKey(g.getClave());
    }

    /**
     * Pide a la IA el grupo que se está viendo (si no tiene propuesta) y, de paso,
     * los siguientes de la cola: así la propuesta suele estar lista antes de llegar.
     */
    private void pedirIA(Grupo g) {
        if (asistente == null || propuestas == null || pausaIA != null || g == null || proyecto == null) {
            return;
        }
        int max = configIA.getLote(asistente.getCliente().getProveedor());
        List<Grupo> lote = new ArrayList<>();
        boolean faltaEste = necesitaIA(g);
        if (faltaEste) {
            lote.add(g);
        }
        EstadoGrupo estado = modoPospuestos() ? EstadoGrupo.POSPUESTO : EstadoGrupo.PENDIENTE;
        List<Grupo> gs = proyecto.getGrupos();
        List<Grupo> siguientes = new ArrayList<>();
        int mirados = 0;
        for (int i = proyecto.posicion(g) + 1; i < gs.size() && mirados < max * 2 && siguientes.size() < max; i++) {
            Grupo x = gs.get(i);
            if (x.getEstado() != estado) {
                continue;
            }
            mirados++;
            if (necesitaIA(x)) {
                siguientes.add(x);
            }
        }
        // Se adelanta trabajo solo cuando compensa una consulta (o si falta el que se está viendo).
        if (!faltaEste && siguientes.size() < Math.max(1, max / 2)) {
            return;
        }
        for (Grupo x : siguientes) {
            if (lote.size() >= max) {
                break;
            }
            lote.add(x);
        }
        if (!lote.isEmpty()) {
            enviarIA(lote);
        }
    }

    private void enviarIA(List<Grupo> lote) {
        AsistenteIA a = asistente;
        PropuestasGuardadas destino = propuestas;
        for (Grupo x : lote) {
            pidiendoIA.add(x.getClave());
        }
        String fecha = LocalDateTime.now().format(Almacen.FECHA);
        hiloIA.submit(() -> {
            long t0 = System.nanoTime();
            AsistenteIA.Resultado r = null;
            ClienteIA.ErrorIA error = null;
            try {
                r = a.proponer(lote, fecha);
                destino.guardar(r.getPropuestas());
            } catch (ClienteIA.ErrorIA e) {
                error = e;
            } catch (IOException | RuntimeException e) {
                error = new ClienteIA.ErrorIA(ClienteIA.ErrorIA.Tipo.RESPUESTA, 0,
                        "Error al procesar la respuesta: " + e.getMessage());
            }
            for (Grupo x : lote) {
                pidiendoIA.remove(x.getClave());
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            AsistenteIA.Resultado fr = r;
            ClienteIA.ErrorIA fe = error;
            SwingUtilities.invokeLater(() -> trasRespuestaIA(destino, lote, fr, ms, fe));
        });
        if (actual != null && lote.contains(actual)) {
            pintarEstadoIA();
        }
    }

    private void trasRespuestaIA(PropuestasGuardadas destino, List<Grupo> lote, AsistenteIA.Resultado r, long ms,
                                 ClienteIA.ErrorIA error) {
        if (destino != propuestas) {
            return; // se ha cambiado de proyecto mientras tanto
        }
        if (error != null) {
            if (error.getTipo() == ClienteIA.ErrorIA.Tipo.CANCELADO) {
                return;
            }
            if (error.detieneLaIA()) {
                pausaIA = error.getMessage();
                actualizarModo();
            } else {
                for (Grupo x : lote) {
                    erroresIA.put(x.getClave(), error.getMessage());
                }
            }
            lblEstadoBarra.setText("IA: " + error.getMessage().split("\n")[0]);
        } else {
            for (Grupo x : r.getSinRespuesta()) {
                erroresIA.put(x.getClave(), "La IA no ha respondido para este grupo.");
            }
            ClienteIA c = asistente != null ? asistente.getCliente() : null;
            lblEstadoBarra.setText("IA: " + r.getPropuestas().size() + (r.getPropuestas().size() == 1 ? " propuesta" : " propuestas")
                    + " en " + String.format("%.1f", ms / 1000.0) + " s"
                    + (c != null && c.getTokensEntrada() > 0 ? "  ·  " + Estilo.numero(c.getTokensEntrada() + c.getTokensSalida())
                    + " tokens en esta sesión" : ""));
        }
        if (actual != null && lote.contains(actual)) {
            sugerencias = calcularSugerencias(actual);
            pintarSugerencias();
        }
        pintarEstadoIA();
        if (error == null && actual != null) {
            pedirIA(actual); // seguir adelantando la cola
        }
    }

    /** Asigna la propuesta de la IA (aunque no sea la primera sugerencia). */
    private void aceptarPropuestaIA() {
        if (actual == null) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        for (int i = 0; i < sugerencias.size(); i++) {
            if (sugerencias.get(i).esDeIA()) {
                aceptarSugerencia(i);
                return;
            }
        }
        avisar(asistente == null ? "El modo IA no está activo (menú IA → Modo de trabajo)."
                : "La IA no tiene una propuesta para este grupo.");
    }

    /** Olvida la propuesta del grupo y vuelve a preguntar (también reanuda la IA si estaba en pausa). */
    private void repreguntarIA() {
        if (asistente == null || actual == null || propuestas == null) {
            avisar("El modo IA no está activo (menú IA → Modo de trabajo).");
            return;
        }
        if (pidiendoIA.contains(actual.getClave())) {
            return;
        }
        if (pausaIA != null) {
            pausaIA = null;
            actualizarModo();
        }
        erroresIA.remove(actual.getClave());
        try {
            propuestas.olvidar(actual.getClave());
        } catch (IOException e) {
            lblEstadoBarra.setText("No se pudo borrar la propuesta anterior: " + e.getMessage());
        }
        sugerencias = calcularSugerencias(actual);
        pintarSugerencias();
        List<Grupo> l = new ArrayList<>();
        l.add(actual);
        enviarIA(l);
    }

    /** Resumen de una petición en bloque. */
    private static final class ResumenIA {
        int grupos;
        int conTermino;
        int sinTermino;
        int consultas;
        String fallo;
        boolean cancelado;
        long ms;
    }

    /** Pide propuestas para todos los grupos pendientes (y pospuestos) que no tengan. */
    private void pedirTodasIA() {
        if (proyecto == null || asistente == null) {
            JOptionPane.showMessageDialog(this, proyecto == null ? "Primero abre o crea un proyecto."
                    : "Activa el modo IA en el menú IA → Modo de trabajo.", "IA", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        erroresIA.clear();
        pausaIA = null;
        actualizarModo();
        List<Grupo> faltan = new ArrayList<>();
        long equipos = 0;
        for (Grupo g : proyecto.getGrupos()) {
            if (g.getEstado() != EstadoGrupo.REVISADO && !propuestas.tiene(g.getClave())) {
                faltan.add(g);
                equipos += g.getEquipos();
            }
        }
        if (faltan.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Todos los grupos pendientes tienen ya propuesta de la IA.", "IA",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int lote = configIA.getLote(asistente.getCliente().getProveedor());
        int consultas = (faltan.size() + lote - 1) / lote;
        ClienteIA cli = asistente.getCliente();
        catalogador.ia.ProveedorIA.Categoria cat = cli.getProveedor().getCategoria();
        boolean gratis = cat == catalogador.ia.ProveedorIA.Categoria.GRATUITA
                || cat == catalogador.ia.ProveedorIA.Categoria.CON_VERSION_GRATUITA;
        int r = JOptionPane.showConfirmDialog(this, "<html><div style='width:460px'>Se pedirá a <b>"
                        + Texto.html(cli.descripcion()) + "</b> una propuesta para <b>" + Estilo.numero(faltan.size())
                        + "</b> grupos (" + Estilo.numero(equipos) + " equipos), en unas <b>" + Estilo.numero(consultas)
                        + "</b> consultas de " + lote + " grupos.<br><br>"
                        + (gratis && !cli.getProveedor().isLocal() ? "Con un servicio gratuito puede agotarse el límite diario: "
                        + "lo que esté hecho queda guardado y se puede seguir otro día.<br><br>" : "")
                        + "Las propuestas <b>no se aplican solas</b>: después las revisas grupo a grupo (o aceptas en bloque "
                        + "las más seguras). ¿Empezar?</div></html>",
                "Pedir propuestas a la IA", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        AsistenteIA a = asistente;
        PropuestasGuardadas destino = propuestas;
        String fecha = LocalDateTime.now().format(Almacen.FECHA);
        Espera.ejecutarCancelable(this, "Pidiendo propuestas a " + cli.descripcion() + "…", "grupos", avance -> {
            ResumenIA res = new ResumenIA();
            long t0 = System.nanoTime();
            int fallosSeguidos = 0;
            for (int i = 0; i < faltan.size() && !avance.cancelado(); i += lote) {
                List<Grupo> parte = new ArrayList<>();
                for (Grupo g : faltan.subList(i, Math.min(faltan.size(), i + lote))) {
                    if (!destino.tiene(g.getClave()) && !pidiendoIA.contains(g.getClave())) {
                        parte.add(g); // los que no haya traído ya la consulta automática
                    }
                }
                try {
                    if (!parte.isEmpty()) {
                        AsistenteIA.Resultado x = a.proponer(parte, fecha);
                        destino.guardar(x.getPropuestas());
                        res.consultas += x.getConsultas();
                        for (PropuestaIA pr : x.getPropuestas()) {
                            if (pr.tieneTermino()) {
                                res.conTermino++;
                            } else {
                                res.sinTermino++;
                            }
                        }
                    }
                    fallosSeguidos = 0;
                } catch (ClienteIA.ErrorIA e) {
                    if (e.getTipo() == ClienteIA.ErrorIA.Tipo.CANCELADO) {
                        res.cancelado = true;
                        break;
                    }
                    res.fallo = e.getMessage();
                    if (e.detieneLaIA() || ++fallosSeguidos >= 3) {
                        break;
                    }
                }
                res.grupos = Math.min(faltan.size(), i + lote);
                if (i > 0) {
                    // Tiempo que falta, según lo que van tardando (desde la segunda consulta: la primera carga el modelo)
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    avance.avance(res.grupos, faltan.size(), ms * (faltan.size() - res.grupos) / res.grupos / 1000);
                } else {
                    avance.avance(res.grupos, faltan.size());
                }
            }
            res.cancelado |= avance.cancelado();
            res.ms = (System.nanoTime() - t0) / 1_000_000;
            return res;
        }, res -> {
            StringBuilder b = new StringBuilder("<html><div style='width:430px'>");
            b.append(res.cancelado ? "Cancelado. " : "").append("Propuestas recibidas: <b>")
                    .append(Estilo.numero(res.conTermino)).append("</b> con término");
            if (res.sinTermino > 0) {
                b.append(" y ").append(Estilo.numero(res.sinTermino)).append(" en las que ningún término encaja");
            }
            b.append(", en ").append(Estilo.numero(res.consultas)).append(" consultas (")
                    .append(Estilo.numero(res.ms / 1000)).append(" s).<br>");
            ClienteIA c = a.getCliente();
            b.append("Tokens usados en esta sesión: ").append(Estilo.numero(c.getTokensEntrada())).append(" de entrada y ")
                    .append(Estilo.numero(c.getTokensSalida())).append(" de salida.");
            if (res.fallo != null) {
                b.append("<br><br><font color='").append(Estilo.hex(Estilo.ROJO)).append("'>Se ha parado por un error:</font> ")
                        .append(Texto.html(res.fallo).replace("\n", "<br>"));
            }
            b.append("<br><br>Revisa las propuestas grupo a grupo (").append(Estilo.atajo("I"))
                    .append(" acepta la de la IA) o usa «IA → Aceptar en bloque las propuestas más seguras».</div></html>");
            if (actual != null) {
                sugerencias = calcularSugerencias(actual);
                pintarSugerencias();
                pintarEstadoIA();
            }
            JOptionPane.showMessageDialog(this, b.toString(), "Propuestas de la IA",
                    res.fallo != null ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        });
    }

    /** Grupos pendientes cuya propuesta de la IA tiene al menos esa confianza (y existe en el catálogo). */
    private List<Grupo> seguros(int umbral) {
        List<Grupo> l = new ArrayList<>();
        if (proyecto == null || propuestas == null) {
            return l;
        }
        for (Grupo g : proyecto.getGrupos()) {
            PropuestaIA p = g.getEstado() == EstadoGrupo.PENDIENTE ? propuestas.get(g.getClave()) : null;
            if (p != null && p.tieneTermino() && p.getConfianza() >= umbral
                    && catalogos.porCodigo(p.getLista(), p.getCodigo()) != null) {
                l.add(g);
            }
        }
        return l;
    }

    /** Marca como revisados, de una vez, los grupos pendientes con propuesta muy segura de la IA. */
    private void aceptarEnBloqueIA() {
        if (proyecto == null || propuestas == null || propuestas.tamano() == 0) {
            JOptionPane.showMessageDialog(this, "No hay propuestas de la IA en este proyecto.\n"
                    + "Usa antes «IA → Pedir propuestas para todos los grupos pendientes».", "Aceptar en bloque",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JSpinner umbral = new JSpinner(new SpinnerNumberModel(configIA.getUmbralBloque(), 50, 100, 1));
        JLabel cuenta = new JLabel(" ");
        Runnable contar = () -> {
            List<Grupo> l = seguros((Integer) umbral.getValue());
            long eq = 0;
            for (Grupo g : l) {
                eq += g.getEquipos();
            }
            cuenta.setText("<html>Se aceptarán <b>" + Estilo.numero(l.size()) + "</b> grupos (" + Estilo.numero(eq)
                    + " equipos).</html>");
        };
        umbral.addChangeListener(e -> contar.run());
        contar.run();
        JPanel fila = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fila.add(new JLabel("Confianza mínima de la IA:"));
        fila.add(umbral);
        fila.add(new JLabel("%"));
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.add(new JLabel("<html><div style='width:440px'>Los grupos <b>pendientes</b> cuya propuesta de la IA tenga "
                + "al menos esa confianza se marcarán como revisados con ese término (los pospuestos no se tocan).<br><br>"
                + "Quedan anotados con el origen «IA en bloque»: puedes verlos con el filtro «Revisados» y devolver "
                + "cualquiera a pendiente (Revisión → Devolver este grupo a pendiente).</div></html>"), BorderLayout.NORTH);
        panel.add(fila, BorderLayout.CENTER);
        panel.add(cuenta, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, panel, "Aceptar en bloque las propuestas de la IA",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        int u = (Integer) umbral.getValue();
        List<Grupo> l = seguros(u);
        if (l.isEmpty()) {
            return;
        }
        String fecha = LocalDateTime.now().format(Almacen.FECHA);
        long eq = 0;
        for (Grupo g : l) {
            PropuestaIA p = propuestas.get(g.getClave());
            g.asignar(p.getLista(), p.getCodigo(), p.getNombreOficial(), "IA en bloque: " + p.getFuente() + " ("
                    + p.getConfianza() + "% de confianza)", fecha);
            memoria.aprender(g.getNombre(), g.getMarca(), g.getModelo(), p.getLista(), p.getCodigo(), p.getNombreOficial(),
                    fecha);
            eq += g.getEquipos();
        }
        try {
            almacen.registrarCambios(proyecto, l);
            memoria.guardarCambios();
            configIA.setUmbralBloque(u);
            configIA.guardar();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "¡No se ha podido guardar!\n\n" + e.getMessage(), "Error al guardar",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        refrescarProgreso();
        refrescarCola();
        refrescarListas();
        if (actual != null && actual.getEstado() == EstadoGrupo.REVISADO) {
            avanzar();
        } else if (actual != null) {
            mostrarGrupo(actual, false);
        }
        JOptionPane.showMessageDialog(this, Estilo.numero(l.size()) + " grupos (" + Estilo.numero(eq)
                        + " equipos) marcados como revisados con la propuesta de la IA.", "Aceptar en bloque",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void borrarPropuestasIA() {
        if (propuestas == null || propuestas.tamano() == 0) {
            JOptionPane.showMessageDialog(this, "No hay propuestas de la IA guardadas en este proyecto.", "IA",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Se borrarán las " + Estilo.numero(propuestas.tamano())
                        + " propuestas de la IA guardadas en este proyecto\n(lo ya revisado no cambia). ¿Seguro?",
                "Borrar propuestas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            propuestas.borrarTodas();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudieron borrar: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
        erroresIA.clear();
        if (actual != null) {
            mostrarGrupo(actual, false);
        }
    }

    private void consumoIA() {
        StringBuilder b = new StringBuilder("<html><div style='width:380px'>");
        b.append("Modo: <b>").append(configIA.getModo() == ConfigIA.Modo.IA ? "IA" : "manual").append("</b><br>");
        if (asistente != null) {
            ClienteIA c = asistente.getCliente();
            b.append("Servicio: <b>").append(Texto.html(c.descripcion())).append("</b><br><br>")
                    .append("Consultas en esta sesión: <b>").append(Estilo.numero(c.getConsultas())).append("</b><br>")
                    .append("Tokens de entrada: ").append(Estilo.numero(c.getTokensEntrada())).append("<br>")
                    .append("Tokens de salida: ").append(Estilo.numero(c.getTokensSalida())).append("<br>");
        }
        if (propuestas != null) {
            b.append("<br>Propuestas guardadas en este proyecto: <b>").append(Estilo.numero(propuestas.tamano()))
                    .append("</b>");
        }
        b.append("<br><br><font color='#667085'>El precio depende del servicio y del modelo: consúltalo en su web.</font>")
                .append("</div></html>");
        JOptionPane.showMessageDialog(this, b.toString(), "Consumo de la IA", JOptionPane.INFORMATION_MESSAGE);
    }

    // =====================================================================
    // Ayuda y salida
    // =====================================================================

    private void mostrarAtajos() {
        Map<String, String> atajos = new LinkedHashMap<>();
        atajos.put("Intro", "Guardar el nombre oficial seleccionado y pasar al siguiente grupo");
        atajos.put(Estilo.atajo("1") + "  (o F1)", "Aceptar la sugerencia automática");
        atajos.put(Estilo.atajo("2") + " / " + Estilo.atajo("3"), "Aceptar la 2ª / 3ª posibilidad");
        atajos.put(Estilo.atajo("I"), "Modo IA: aceptar la propuesta de la IA");
        atajos.put(Estilo.atajo("⇧I"), "Modo IA: volver a preguntar a la IA por este grupo");
        atajos.put(Estilo.atajo("P") + "  (o F5)", "Posponer: dejar como duda y pasar al siguiente");
        atajos.put("↑ ↓  RePág AvPág", "Moverse por los resultados del buscador");
        atajos.put("Esc", "Borrar el texto del buscador");
        atajos.put(Estilo.atajo("F"), "Ir al buscador");
        atajos.put(Estilo.atajo("L"), "Buscar en el otro catálogo (GMDN ⇄ Mantenimiento)");
        atajos.put(Estilo.atajo("⇧N"), "Crear una entrada nueva en el catálogo de Mantenimiento");
        atajos.put(Estilo.atajo("M"), "Ir a la marca (Tab para pasar al modelo)");
        atajos.put(Estilo.atajo("⇧M"), "Ir al modelo");
        atajos.put(Estilo.atajo("J"), "Saltar al siguiente pendiente sin guardar");
        atajos.put(Estilo.atajo("E"), "Exportar el resultado (Excel o CSV, uno por centro)");
        atajos.put("Doble clic", "En un resultado: asignarlo y guardar");
        StringBuilder h = new StringBuilder("<html><table cellpadding='4'>");
        for (Map.Entry<String, String> e : atajos.entrySet()) {
            h.append("<tr><td><b>").append(Texto.html(e.getKey())).append("</b></td><td>")
                    .append(Texto.html(e.getValue())).append("</td></tr>");
        }
        h.append("</table></html>");
        JOptionPane.showMessageDialog(this, h.toString(), "Atajos de teclado", JOptionPane.PLAIN_MESSAGE);
    }

    private void mostrarAyuda() {
        String t = "<html><div style='width:480px'>"
                + "<p><b>1. Catálogos.</b> Importa una vez el Excel de catálogos, con sus dos hojas: GMDN y "
                + "Mantenimiento (Archivo → Importar → Catálogos oficiales). Quedan guardados.</p><br>"
                + "<p><b>2. Inventario.</b> Archivo → Importar → Inventario del hospital (" + Estilo.atajo("N")
                + "). Se reconocen las columnas Equipo_codiCentro, "
                + "Equipo_idEquipo, Equipo_nombre…; se descartan los equipos de baja (Equipo_debaja = S) y los "
                + "iguales se juntan en un grupo, de más a menos equipos.</p><br>"
                + "<p><b>3. Revisión.</b> Para cada grupo: si hay <b>sugerencia</b> (verde = casi seguro, naranja = "
                + "revisar, rojo = dudoso) acéptala con " + Estilo.atajo("1") + ". Si no, busca en el catálogo "
                + "(GMDN o Mantenimiento, " + Estilo.atajo("L") + " para cambiar), elige con las flechas y pulsa "
                + "<b>Intro</b>. Si en Mantenimiento no existe, créala con «+ Nueva entrada…». "
                + "Si dudas, <b>Posponer</b>.</p><br>"
                + "<p><b>4. Modo IA (opcional).</b> En el menú IA → Modo de trabajo eliges trabajar a mano o con una IA "
                + "(gratuita, con versión gratuita o de pago: solo hay que pegar la clave API). La IA elige entre los "
                + "términos parecidos de los catálogos y su propuesta aparece como sugerencia (" + Estilo.atajo("I")
                + " para aceptarla). Nunca se le envían el centro ni el identificador de los equipos.</p><br>"
                + "<p><b>5. Guardado.</b> Cada acción se guarda al instante: si el programa se cierra, no se pierde nada.</p><br>"
                + "<p><b>6. Resultado.</b> Archivo → Exportar → Resultado: un Excel o CSV por centro con "
                + "Equipo_codiCentro, Equipo_idEquipo, CÓDIGO GMDN y el nombre.</p>"
                + "</div></html>";
        JOptionPane.showMessageDialog(this, t, "Cómo funciona", JOptionPane.PLAIN_MESSAGE);
    }

    private void acercaDe() {
        JOptionPane.showMessageDialog(this, "<html><b>Catalogador de inventario</b> " + VERSION + "<br><br>"
                        + "Asistente para catalogar el inventario de un hospital con los catálogos GMDN y de Mantenimiento.<br>"
                        + "Modo: " + (configIA.getModo() == ConfigIA.Modo.IA ? "IA (" + Texto.html(configIA.resumen()) + ")"
                        : "manual") + "<br>"
                        + "Java " + System.getProperty("java.version") + " · " + Texto.html(System.getProperty("os.name"))
                        + "<br><br>"
                        + "Datos: " + Texto.html(almacen.getBase().toString()) + "</html>",
                "Acerca de", JOptionPane.INFORMATION_MESSAGE, new ImageIcon(Estilo.icono(64)));
    }

    private void salir() {
        almacen.guardarConfig(config);
        hiloBusqueda.shutdownNow();
        hiloIA.shutdownNow();
        dispose();
        System.exit(0);
    }
}
