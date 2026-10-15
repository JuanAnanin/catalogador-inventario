package catalogador.ui;

import catalogador.logica.Almacen;
import catalogador.logica.Memoria;
import catalogador.logica.Sugerencia;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TipoLista;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Recorre el programa como lo haría una persona y hace las capturas del manual
 * (y, de paso, comprueba que cada pantalla aparece y funciona).
 * Uso (con un DISPLAY, idioma español):
 *   java -Duser.home=HOME -Duser.language=es -Duser.country=ES -Dsun.java2d.uiScale=2
 *        -cp clases:pruebas catalogador.ui.CapturasManual SALIDA CATALOGOS.xlsx INVENTARIO.xlsx URL_IA [DATOS_PRUEBA]
 * (con un servidor simulado en URL_IA y otro en localhost:11434 que hace de Ollama)
 */
public final class CapturasManual {

    static Path salida;
    static Robot robot;
    static VentanaPrincipal v;
    static PrintWriter log;
    static String rutaBase;
    static final String RUTA_WINDOWS = "C:\\Users\\Ana\\Catalogador";

    public static void main(String[] a) throws Exception {
        salida = Paths.get(a[0]);
        Files.createDirectories(salida);
        File catalogos = new File(a[1]);
        File inventario = new File(a[2]);
        String urlIA = a[3];
        File datosPrueba = a.length > 4 ? new File(a[4]) : new File(inventario.getParentFile(), "pruebas");
        log = new PrintWriter(Files.newBufferedWriter(salida.resolve("capturas.log")), true);
        robot = new Robot();

        Almacen almacen = Almacen.predeterminado();
        rutaBase = almacen.getBase().toString();
        edt(() -> {
            try {
                for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(i.getName())) {
                        UIManager.setLookAndFeel(i.getClassName());
                    }
                }
                // Letra parecida a la de Windows 11 (Segoe UI)
                // (como hace Windows con Segoe UI: fuente compuesta, para que ✔ ⚡ ▾ salgan de otra fuente)
                // (por reflexión, para compilar con --release 11; al ejecutar hace falta
                //  --add-exports java.desktop/sun.font=ALL-UNNAMED)
                Object fuente = Class.forName("sun.font.FontUtilities")
                        .getMethod("getCompositeFontUIResource", Font.class)
                        .invoke(null, new Font("Inter", Font.PLAIN, 12));
                UIManager.getLookAndFeelDefaults().put("defaultFont", fuente);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        almacen.prepararCarpetas();

        // ============================================================ 1. Primer arranque: ¿manual o IA?
        edt(() -> {
            v = new VentanaPrincipal(almacen);
            v.setSize(1440, 900);
            v.setLocation(20, 20);
            v.setVisible(true);
        });
        async(() -> v.iniciar()); // abre diálogos modales: no se puede esperar a que termine
        JDialog modo = esperarDialogo("¿Cómo quieres trabajar?");
        dormir(600);
        capturar(modo, "modo_inicio");
        pulsar(modo, "Empezar en modo manual");
        esperarCerrado(modo);
        dormir(500);
        capturar(v, "bienvenida");

        // ============================================================ 2. Catálogos
        async(() -> llamar(v, "importarCatalogos", File.class, catalogos));
        JDialog conf = esperarDialogo("Importar catálogos");
        capturar(conf, "dlg_catalogos");
        pulsar(conf, "Aceptar");
        JDialog fin = esperarDialogo("Catálogos importados");
        apuntar("catalogos", textoDe(fin));
        capturar(fin, "dlg_catalogos_ok");
        pulsar(fin, "Aceptar");

        // ============================================================ 3. Inventario
        async(() -> llamar(v, "importarInventario", File.class, inventario));
        JDialog inv = esperarDialogo("Importar inventario");
        dormir(300);
        capturar(inv, "dlg_inventario");
        pulsar(inv, "Importar");
        JDialog res = esperarDialogo("Proyecto creado", 180000);
        apuntar("resumen", textoDe(res));
        capturar(res, "dlg_resumen");
        pulsar(res, "Aceptar");
        dormir(800);
        Proyecto p = (Proyecto) campo(v, "proyecto");
        apuntar("proyecto", p.getNombre() + " grupos=" + p.getGrupos().size() + " equipos=" + p.numEquipos());
        capturar(v, "trabajo");

        // ============================================================ 4. Buscador (Mantenimiento) y guardar
        JTextField buscar = (JTextField) campo(v, "txtBuscar");
        JTable resultados = (JTable) campo(v, "tablaResultados");
        Grupo primero = (Grupo) campo(v, "actual");
        apuntar("primer grupo", primero.getNombre() + " · " + primero.getEquipos());
        escribir(buscar, "papeler");
        esperar(() -> resultados.getRowCount() > 0, 5000);
        dormir(400);
        capturar(v, "buscador_mant");
        edt(() -> llamar(v, "guardar"));
        dormir(600);
        capturar(v, "tras_guardar");

        // Unos cuantos más, para que la memoria aprenda y haya sugerencias
        clasificarPorNombre("PERCHERO", "PERCHERO");
        clasificarPorNombre("SILLA DE OFICINA", "SILLA OFICINA");
        clasificarPorNombre("ARMARIO", "ARMARIO");
        clasificarPorNombre("CARRO DE CURAS", "CARRO CURAS");
        clasificarPorNombre("MESA AUXILIAR", "MESA AUXILIAR");

        // ============================================================ 5. Sugerencias verde / naranja / roja
        Memoria memoria = (Memoria) campo(v, "memoria");
        capturarSugerencia(p, memoria, 0.85, 1.01, "sug_verde");
        capturarSugerencia(p, memoria, 0.65, 0.85, "sug_naranja");
        capturarSugerencia(p, memoria, 0.45, 0.65, "sug_roja");

        // ============================================================ 6. GMDN: buscar con descripción
        Grupo electro = buscarGrupo(p, g -> g.getNombre().contains("AMALGAMA") && g.getEstado() == EstadoGrupo.PENDIENTE);
        mostrar(electro);
        edt(() -> llamar(v, "cambiarLista", TipoLista.class, TipoLista.GMDN, boolean.class, true));
        escribir(buscar, "amalgama");
        esperar(() -> resultados.getRowCount() > 0, 5000);
        edt(() -> resultados.setRowSelectionInterval(0, 0));
        dormir(500);
        capturar(v, "buscador_gmdn");
        Grupo bombaInf = buscarGrupo(p, g -> g.getNombre().startsWith("BOMBA DE INFUSION") && g.getEstado() == EstadoGrupo.PENDIENTE);
        mostrar(bombaInf);
        edt(() -> llamar(v, "cambiarLista", TipoLista.class, TipoLista.GMDN, boolean.class, true));
        escribir(buscar, "bomba infusion");
        dormir(900);
        capturar(v, "buscador_sin_resultados");

        // ============================================================ 7. Nueva entrada de mantenimiento
        edt(() -> llamar(v, "cambiarLista", TipoLista.class, TipoLista.MANTENIMIENTO, boolean.class, true));
        Grupo raro = buscarGrupo(p, g -> g.getNombre().startsWith("PIE DE GOTERO") && g.getEstado() == EstadoGrupo.PENDIENTE);
        if (raro != null) {
            mostrar(raro);
        }
        async(() -> llamar(v, "nuevaEntradaMantenimiento"));
        JDialog nueva = esperarDialogo("Nueva entrada de mantenimiento");
        dormir(400);
        elegirEnCombo(nueva, 0, "MOBILIARIO");
        dormir(250);
        elegirEnCombo(nueva, 1, "MOBILIARIO CLINICO");
        dormir(250);
        elegirEnCombo(nueva, 2, null);
        dormir(400);
        capturar(nueva, "dlg_nueva_entrada");
        pulsar(nueva, "Cancelar");
        esperarCerrado(nueva);

        // ============================================================ 8. Corrector y posponer
        Grupo braun = buscarGrupo(p, g -> g.getMarca().contains("BRAUN") && g.getEstado() == EstadoGrupo.PENDIENTE);
        if (braun != null) {
            mostrar(braun);
            JTextField marca = (JTextField) campo(v, "txtMarca");
            edt(() -> marca.setText("B. BRAUN"));
            dormir(300);
            capturar(v, "corrector");
            edt(() -> llamar(v, "posponer"));
            dormir(500);
        }
        Grupo otro = buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.PENDIENTE && g.getNombre().contains("LUMINARIA"));
        if (otro != null) {
            mostrar(otro);
            edt(() -> llamar(v, "posponer"));
            dormir(400);
        }
        @SuppressWarnings("unchecked")
        JComboBox<String> filtro = (JComboBox<String>) campo(v, "cmbFiltroCola");
        edt(() -> filtro.setSelectedIndex(1));
        dormir(300);
        Grupo pos = buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.POSPUESTO);
        mostrar(pos);
        capturar(v, "pospuestos");
        edt(() -> filtro.setSelectedIndex(0));
        mostrar(buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.PENDIENTE));

        // ============================================================ 9. Menús
        JMenuBar barra = v.getJMenuBar();
        capturarMenu(barra, 0, 0, "menu_archivo_importar");
        capturarMenu(barra, 0, 1, "menu_archivo_exportar");
        capturarMenu(barra, 1, -1, "menu_catalogos");
        capturarMenu(barra, 2, -1, "menu_revision");
        capturarMenu(barra, 3, -1, "menu_ia_manual");
        capturarMenu(barra, 4, -1, "menu_ayuda");

        // ============================================================ 10. Exportar y atajos
        async(() -> llamar(v, "exportar"));
        JDialog exp = esperarDialogo("Exportar");
        dormir(500);
        capturar(exp, "dlg_exportar");
        pulsar(exp, "Cancelar");
        esperarCerrado(exp);
        async(() -> llamar(v, "mostrarAtajos"));
        JDialog at = esperarDialogo("Atajos de teclado");
        capturar(at, "atajos");
        pulsar(at, "Aceptar");

        // ============================================================ 10b. Columnas con otros títulos
        File otros = new File(datosPrueba, "Inventario_PRUEBA_otros_titulos.xlsx");
        if (otros.exists()) {
            async(() -> llamar(v, "importarInventario", File.class, otros));
            JDialog col = esperarDialogo("Importar inventario");
            dormir(600);
            capturar(col, "dlg_columnas");
            pulsar(col, "Cancelar");
            esperarCerrado(col);
        }

        // ============================================================ 11. Modo IA: elegir servicio
        async(() -> llamar(v, "abrirModo"));
        JDialog dm = esperarDialogo("Modo de trabajo");
        DialogoModo d = (DialogoModo) dm;
        edt(() -> d.prepararCaptura(true, "groq", "gsk_ClaveDeEjemplo0123456789"));
        dormir(500);
        capturar(dm, "modo_ia_groq");
        pulsarSinEsperar(dm, "¿Cómo se consigue?");
        JDialog pasos = esperarDialogo("Cómo conseguir la clave");
        dormir(300);
        capturar(pasos, "dlg_pasos_groq");
        pulsar(pasos, "Cerrar");
        esperarCerrado(pasos);
        edt(() -> d.prepararCaptura(true, "gemini", ""));
        dormir(300);
        capturar(dm, "modo_ia_gemini");
        edt(() -> d.prepararCaptura(true, "openai", ""));
        dormir(300);
        capturar(dm, "modo_ia_openai");
        edt(() -> d.prepararCaptura(true, "anthropic", ""));
        dormir(300);
        capturar(dm, "modo_ia_anthropic");
        edt(() -> d.prepararCaptura(true, "ollama", null));
        dormir(300);
        capturar(dm, "modo_ia_ollama");
        pulsarSinEsperar(dm, "¿Cómo se instala?");
        JDialog pasosOllama = esperarDialogo("Cómo instalarlo");
        dormir(300);
        capturar(pasosOllama, "dlg_pasos_ollama");
        pulsar(pasosOllama, "Cerrar");
        esperarCerrado(pasosOllama);
        @SuppressWarnings("unchecked")
        JComboBox<String> modelosOllama = (JComboBox<String>) campo(d, "cmbModelo");
        JLabel estadoOllama = (JLabel) campo(d, "lblPrueba");
        pulsar(dm, "Cargar modelos");
        esperar(() -> estadoOllama.getText().contains("disponibles"), 15000);
        dormir(800);
        capturar(dm, "modo_ia_ollama_modelos");
        edt(() -> {
            modelosOllama.hidePopup();
            modelosOllama.getEditor().setItem("qwen3:8b");
        });
        pulsar(dm, "Probar conexión");
        JLabel pruebaOllama = (JLabel) campo(d, "lblPrueba");
        esperar(() -> pruebaOllama.getText().contains("Conexión correcta"), 8000);
        dormir(300);
        capturar(dm, "modo_ia_ollama_probar");
        apuntar("probar ollama", pruebaOllama.getText());
        edt(() -> d.prepararCaptura(true, "personalizado", ""));
        JTextField url = (JTextField) buscarComponente(dm, JTextField.class, c -> !(c instanceof JPasswordField)
                && c.isEditable() && c.getText().startsWith("http"));
        edt(() -> url.setText(urlIA));
        @SuppressWarnings("unchecked")
        JComboBox<String> modelos = (JComboBox<String>) campo(d, "cmbModelo");
        edt(() -> modelos.getEditor().setItem(""));
        JLabel estadoPropio = (JLabel) campo(d, "lblPrueba");
        pulsar(dm, "Cargar modelos");
        esperar(() -> estadoPropio.getText().contains("disponible"), 15000);
        dormir(800);
        capturar(dm, "modo_ia_modelos");
        edt(() -> {
            modelos.hidePopup();
            modelos.getEditor().setItem("modelo-simulado");
        });
        pulsar(dm, "Probar conexión");
        JLabel prueba = (JLabel) campo(d, "lblPrueba");
        esperar(() -> prueba.getText().contains("Conexión correcta"), 8000);
        dormir(300);
        capturar(dm, "modo_ia_probar");
        apuntar("probar", prueba.getText());
        pulsar(dm, "Aceptar");
        esperarCerrado(dm);

        // ============================================================ 12. Trabajar con la IA
        Grupo sigPend = buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.PENDIENTE && !g.isElectromedicina()
                && memoria.sugerir(g.getNombre(), g.getMarca(), g.getModelo(), 1).isEmpty());
        mostrar(sigPend);
        esperarIA();
        capturar(v, "trabajo_ia");
        apuntar("ia grupo", sigPend.getNombre());

        Grupo coincide = buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.PENDIENTE && !g.isElectromedicina()
                && !memoria.sugerir(g.getNombre(), g.getMarca(), g.getModelo(), 1).isEmpty()
                && memoria.sugerir(g.getNombre(), g.getMarca(), g.getModelo(), 1).get(0).getSimilitud() >= 0.85);
        if (coincide != null) {
            mostrar(coincide);
            esperarIA();
            capturar(v, "ia_coinciden");
        }
        Grupo conMarca = buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.PENDIENTE && g.getMarca().equals("MITSUBISHI"));
        if (conMarca != null) {
            mostrar(conMarca);
            esperarIA();
            capturar(v, "ia_marca_modelo");
        }
        capturarMenu(barra, 3, -1, "menu_ia");

        // Aceptar con Ctrl+I
        mostrar(sigPend);
        esperarIA();
        edt(() -> llamar(v, "aceptarPropuestaIA"));
        dormir(500);
        apuntar("aceptada IA", sigPend.getEstado() + " " + sigPend.getNombreOficial() + " · " + sigPend.getOrigen());

        // ============================================================ 13. Pedir todas y aceptar en bloque
        async(() -> llamar(v, "pedirTodasIA"));
        JDialog pt = esperarDialogo("Pedir propuestas a la IA");
        capturar(pt, "dlg_pedir_todas");
        pulsar(pt, "Aceptar");
        JDialog prog = esperarDialogo("Un momento");
        dormir(2500);
        capturar(prog, "dlg_pedir_progreso");
        JDialog fin2 = esperarDialogo("Propuestas de la IA", 600_000);
        apuntar("pedir todas", textoDe(fin2));
        capturar(fin2, "dlg_pedir_fin");
        pulsar(fin2, "Aceptar");

        catalogador.ia.PropuestasGuardadas pg = (catalogador.ia.PropuestasGuardadas) campo(v, "propuestas");
        Grupo sinTermino = buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.PENDIENTE && pg.get(g.getClave()) != null
                && !pg.get(g.getClave()).tieneTermino());
        if (sinTermino != null) {
            mostrar(sinTermino);
            esperarIA();
            capturar(v, "ia_sin_termino");
            apuntar("sin término", sinTermino.getNombre() + " · " + pg.get(sinTermino.getClave()).getMotivo());
        }

        async(() -> llamar(v, "aceptarEnBloqueIA"));
        JDialog bl = esperarDialogo("Aceptar en bloque las propuestas de la IA");
        dormir(300);
        capturar(bl, "dlg_bloque");
        apuntar("bloque", textoDe(bl));
        pulsar(bl, "Aceptar");
        JDialog blOk = esperarDialogo("Aceptar en bloque");
        apuntar("bloque hecho", textoDe(blOk));
        pulsar(blOk, "Aceptar");
        dormir(600);
        edt(() -> filtro.setSelectedIndex(2));
        dormir(400);
        capturar(v, "revisados_ia");
        edt(() -> filtro.setSelectedIndex(0));

        async(() -> llamar(v, "consumoIA"));
        JDialog co = esperarDialogo("Consumo de la IA");
        capturar(co, "dlg_consumo");
        apuntar("consumo", textoDe(co));
        pulsar(co, "Aceptar");

        // ============================================================ 14. Error de clave: la IA se pone en pausa
        async(() -> llamar(v, "abrirModo"));
        JDialog dm2 = esperarDialogo("Modo de trabajo");
        JPasswordField clave = (JPasswordField) campo(dm2, "txtClave");
        edt(() -> clave.setText("mal"));
        pulsar(dm2, "Aceptar");
        esperarCerrado(dm2);
        Grupo sinProp = buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.PENDIENTE && pg.get(g.getClave()) == null);
        if (sinProp == null) {
            sinProp = buscarGrupo(p, g -> g.getEstado() == EstadoGrupo.PENDIENTE);
            Grupo sp = sinProp;
            edt(() -> pg.olvidar(sp.getClave()));
        }
        mostrar(sinProp);
        esperarIA();
        capturar(v, "ia_error_clave");
        async(() -> llamar(v, "abrirModo"));
        JDialog dm3 = esperarDialogo("Modo de trabajo");
        JPasswordField clave3 = (JPasswordField) campo(dm3, "txtClave");
        edt(() -> clave3.setText(""));
        pulsar(dm3, "Aceptar");
        esperarCerrado(dm3);

        apuntar("progreso", p.contar(EstadoGrupo.REVISADO) + " revisados, " + p.contar(EstadoGrupo.POSPUESTO)
                + " pospuestos de " + p.getGrupos().size());
        log.close();
        System.out.println("Capturas terminadas en " + salida);
        System.exit(0);
    }

    // ------------------------------------------------------------------ pasos compuestos

    /** Busca un grupo pendiente por nombre, escribe la búsqueda y guarda el primer resultado. */
    static void clasificarPorNombre(String empieza, String consulta) throws Exception {
        Proyecto p = (Proyecto) campo(v, "proyecto");
        Grupo g = buscarGrupo(p, x -> x.getEstado() == EstadoGrupo.PENDIENTE && x.getNombre().startsWith(empieza));
        if (g == null) {
            apuntar("no encontrado", empieza);
            return;
        }
        mostrar(g);
        edt(() -> llamar(v, "cambiarLista", TipoLista.class, TipoLista.MANTENIMIENTO, boolean.class, true));
        JTextField buscar = (JTextField) campo(v, "txtBuscar");
        JTable resultados = (JTable) campo(v, "tablaResultados");
        escribir(buscar, consulta);
        esperar(() -> resultados.getRowCount() > 0, 5000);
        dormir(300);
        edt(() -> resultados.setRowSelectionInterval(0, 0));
        edt(() -> llamar(v, "guardar"));
        dormir(300);
        apuntar("clasificado", g.getNombre() + " → " + g.getNombreOficial());
    }

    static void capturarSugerencia(Proyecto p, Memoria m, double desde, double hasta, String nombre) throws Exception {
        Grupo elegido = null;
        for (Grupo g : p.getGrupos()) {
            if (g.getEstado() != EstadoGrupo.PENDIENTE) {
                continue;
            }
            List<Sugerencia> s = m.sugerir(g.getNombre(), g.getMarca(), g.getModelo(), 3);
            if (!s.isEmpty() && s.get(0).getSimilitud() >= desde && s.get(0).getSimilitud() < hasta) {
                elegido = g;
                if (s.size() > 1 || desde < 0.8) {
                    break;
                }
            }
        }
        if (elegido == null) {
            apuntar("sin sugerencia en el rango", nombre);
            return;
        }
        mostrar(elegido);
        dormir(400);
        capturar(v, nombre);
        apuntar(nombre, elegido.getNombre() + " " + m.sugerir(elegido.getNombre(), elegido.getMarca(),
                elegido.getModelo(), 1).get(0).porcentaje() + "%");
    }

    static void capturarMenu(JMenuBar barra, int menu, int submenu, String nombre) throws Exception {
        edt(() -> {
            JMenu m = barra.getMenu(menu);
            List<MenuElement> camino = new ArrayList<>();
            camino.add(barra);
            camino.add(m);
            camino.add(m.getPopupMenu());
            if (submenu >= 0) {
                JMenu sub = (JMenu) m.getMenuComponent(submenu);
                camino.add(sub);
                camino.add(sub.getPopupMenu());
            }
            MenuSelectionManager.defaultManager().setSelectedPath(camino.toArray(new MenuElement[0]));
        });
        dormir(500);
        capturar(v, nombre);
        edt(() -> MenuSelectionManager.defaultManager().clearSelectedPath());
        dormir(200);
    }

    static void esperarIA() throws Exception {
        @SuppressWarnings("unchecked")
        java.util.Set<String> pidiendo = (java.util.Set<String>) campo(v, "pidiendoIA");
        dormir(300);
        esperar(() -> pidiendo.isEmpty(), 20000);
        dormir(500);
    }

    static void mostrar(Grupo g) throws Exception {
        edt(() -> llamar(v, "mostrarGrupo", Grupo.class, g, boolean.class, true));
        dormir(500);
    }

    static Grupo buscarGrupo(Proyecto p, java.util.function.Predicate<Grupo> f) {
        for (Grupo g : p.getGrupos()) {
            if (f.test(g)) {
                return g;
            }
        }
        return null;
    }

    static void elegirEnCombo(JDialog d, int indice, String texto) throws Exception {
        @SuppressWarnings("unchecked")
        JComboBox<Object>[] combos = (JComboBox<Object>[]) campo(d, "combos");
        edt(() -> {
            JComboBox<Object> c = combos[indice];
            for (int i = 0; i < c.getItemCount(); i++) {
                String s = String.valueOf(c.getItemAt(i));
                if (texto == null ? s.startsWith("+") : s.startsWith(texto)) {
                    c.setSelectedIndex(i);
                    return;
                }
            }
        });
    }

    // ------------------------------------------------------------------ utilidades de interfaz

    interface Accion {
        void hacer() throws Exception;
    }

    static void edt(Accion a) throws Exception {
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                a.hacer();
            } catch (Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
    }

    static void async(Accion a) {
        SwingUtilities.invokeLater(() -> {
            try {
                a.hacer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    static void dormir(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    static void esperar(BooleanSupplier c, long ms) throws Exception {
        long fin = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < fin) {
            AtomicReference<Boolean> ok = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> ok.set(c.getAsBoolean()));
            if (ok.get()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Tiempo agotado esperando una condición");
    }

    static JDialog esperarDialogo(String titulo) throws Exception {
        return esperarDialogo(titulo, 30000);
    }

    static JDialog esperarDialogo(String titulo, long ms) throws Exception {
        long fin = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < fin) {
            AtomicReference<JDialog> r = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog && w.isShowing() && ((JDialog) w).getTitle() != null
                            && ((JDialog) w).getTitle().startsWith(titulo)) {
                        r.set((JDialog) w);
                    }
                }
            });
            if (r.get() != null) {
                Thread.sleep(400);
                return r.get();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("No aparece el diálogo «" + titulo + "»");
    }

    static void esperarCerrado(Window w) throws Exception {
        esperar(() -> !w.isShowing(), 10000);
        dormir(200);
    }

    static void pulsar(Container c, String texto) throws Exception {
        AbstractButton b = (AbstractButton) buscarComponente(c, AbstractButton.class,
                x -> x.getText() != null && x.getText().startsWith(texto) && x.isShowing());
        if (b == null) {
            throw new IllegalStateException("No hay botón «" + texto + "»");
        }
        edt(b::doClick);
        dormir(300);
    }

    /** Pulsa un botón que abre un diálogo modal (sin esperar a que se cierre). */
    static void pulsarSinEsperar(Container c, String texto) throws Exception {
        AbstractButton b = (AbstractButton) buscarComponente(c, AbstractButton.class,
                x -> x.getText() != null && x.getText().startsWith(texto) && x.isShowing());
        if (b == null) {
            throw new IllegalStateException("No hay botón «" + texto + "»");
        }
        async(b::doClick);
        dormir(300);
    }

    static void escribir(JTextField t, String texto) throws Exception {
        edt(() -> {
            t.requestFocusInWindow();
            t.setText(texto);
        });
        dormir(500);
    }

    @SuppressWarnings("unchecked")
    static <T extends Component> T buscarComponente(Container c, Class<T> tipo, java.util.function.Predicate<T> f) {
        for (Component x : c.getComponents()) {
            if (tipo.isInstance(x) && f.test((T) x)) {
                return (T) x;
            }
            if (x instanceof Container) {
                T r = buscarComponente((Container) x, tipo, f);
                if (r != null) {
                    return r;
                }
            }
        }
        if (c instanceof JMenu) {
            for (Component x : ((JMenu) c).getMenuComponents()) {
                if (tipo.isInstance(x) && f.test((T) x)) {
                    return (T) x;
                }
            }
        }
        return null;
    }

    static String textoDe(Container c) {
        StringBuilder b = new StringBuilder();
        recoger(c, b);
        return b.toString().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static void recoger(Container c, StringBuilder b) {
        for (Component x : c.getComponents()) {
            if (x instanceof JLabel && ((JLabel) x).getText() != null) {
                b.append(((JLabel) x).getText()).append(' ');
            }
            if (x instanceof Container) {
                recoger((Container) x, b);
            }
        }
    }

    /** Las rutas como se verían en Windows (C:\Users\Ana\Catalogador, C:\Users\Ana\Desktop). */
    static String ruta(String t) {
        String casa = System.getProperty("user.home");
        return t.replace(rutaBase, RUTA_WINDOWS).replace(casa + "/Desktop", "C:\\Users\\Ana\\Desktop")
                .replace(casa, "C:\\Users\\Ana");
    }

    /** Pone las rutas como se verían en Windows. */
    static void comoEnWindows(Container c) {
        String casa = System.getProperty("user.home");
        for (Component x : c.getComponents()) {
            if (x instanceof JLabel) {
                JLabel l = (JLabel) x;
                if (l.getText() != null && l.getText().contains(casa)) {
                    l.setText(ruta(l.getText()));
                }
            } else if (x instanceof AbstractButton) {
                AbstractButton b = (AbstractButton) x;
                if (b.getText() != null && b.getText().contains(casa)) {
                    b.setText(ruta(b.getText()));
                }
            } else if (x instanceof JTextField && !(x instanceof JPasswordField)) {
                JTextField t = (JTextField) x;
                if (t.getText() != null && t.getText().contains(casa)) {
                    t.setText(ruta(t.getText()));
                }
            }
            if (x instanceof Container) {
                comoEnWindows((Container) x);
            }
        }
    }

    static void capturar(Window w, String nombre) throws Exception {
        edt(() -> {
            comoEnWindows(w);
            w.toFront();
        });
        dormir(350);
        AtomicReference<Rectangle> r = new AtomicReference<>();
        edt(() -> r.set(new Rectangle(w.getLocationOnScreen(), w.getSize())));
        MultiResolutionImage mri = robot.createMultiResolutionScreenCapture(r.get());
        List<Image> vs = mri.getResolutionVariants();
        Image img = vs.get(vs.size() - 1);
        BufferedImage bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB);
        bi.getGraphics().drawImage(img, 0, 0, null);
        ImageIO.write(bi, "png", salida.resolve(nombre + ".png").toFile());
        apuntar("captura", nombre + " " + bi.getWidth() + "x" + bi.getHeight());
    }

    static void apuntar(String que, String texto) {
        log.println(que + ": " + texto);
        System.out.println(que + ": " + texto);
    }

    // ------------------------------------------------------------------ reflexión

    static Object campo(Object o, String nombre) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(nombre);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(nombre);
    }

    static Object llamar(Object o, String metodo, Object... tiposYValores) throws Exception {
        int n = tiposYValores.length / 2;
        Class<?>[] tipos = new Class<?>[n];
        Object[] vals = new Object[n];
        for (int i = 0; i < n; i++) {
            tipos[i] = (Class<?>) tiposYValores[2 * i];
            vals[i] = tiposYValores[2 * i + 1];
        }
        Method m = o.getClass().getDeclaredMethod(metodo, tipos);
        m.setAccessible(true);
        return m.invoke(o, vals);
    }
}
