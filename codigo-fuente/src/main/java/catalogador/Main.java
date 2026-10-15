package catalogador;

import catalogador.logica.Almacen;
import catalogador.ui.VentanaPrincipal;

import javax.swing.InputMap;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.DefaultEditorKit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Catalogador de inventario hospitalario.
 * Punto de entrada: prepara el aspecto visual y abre la ventana principal.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Catalogador");
        // Modo IA: usar el proxy configurado en el sistema (habitual en la red de un hospital)
        if (System.getProperty("java.net.useSystemProxies") == null) {
            System.setProperty("java.net.useSystemProxies", "true");
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        Almacen almacen = Almacen.predeterminado();

        // Cualquier error inesperado se muestra y se apunta en errores.log en vez de cerrar el programa.
        Thread.setDefaultUncaughtExceptionHandler((hilo, error) -> {
            registrar(almacen, error);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Ha ocurrido un error inesperado:\n" + error + "\n\nEl trabajo guardado no se ha perdido.\n"
                            + "Detalles en " + almacen.getBase().resolve("errores.log"),
                    "Error", JOptionPane.ERROR_MESSAGE));
        });

        SwingUtilities.invokeLater(() -> {
            elegirAspecto();
            try {
                almacen.prepararCarpetas();
            } catch (IOException e) {
                // se avisará al iniciar
            }
            if (!almacen.bloquearInstancia()) {
                JOptionPane.showMessageDialog(null, "El Catalogador ya está abierto en otra ventana.",
                        "Catalogador", JOptionPane.INFORMATION_MESSAGE);
                System.exit(0);
            }
            VentanaPrincipal v = new VentanaPrincipal(almacen);
            v.setVisible(true);
            v.iniciar();
        });
    }

    /**
     * Aspecto nativo en Windows; «Nimbus» en Linux (más agradable que el estándar) y en macOS, donde el
     * aspecto nativo de Java no dibuja en macOS 26 el borde de los campos de texto ni la flecha de las
     * listas desplegables. Nimbus se ve igual en todas las versiones (y como en las capturas del manual).
     */
    private static void elegirAspecto() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        try {
            String sistema = UIManager.getSystemLookAndFeelClassName();
            if (sistema.contains("Metal") || mac) {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        sistema = info.getClassName();
                    }
                }
            }
            UIManager.setLookAndFeel(sistema);
            if (mac && !sistema.contains("Aqua")) {
                atajosMac();
            }
        } catch (Exception e) {
            // se queda el aspecto por defecto
        }
    }

    /**
     * Con un aspecto que no es el de macOS, los campos de texto solo copian y pegan con Ctrl: se añaden
     * los atajos de macOS (⌘C, ⌘V, ⌘X, ⌘A, ⌘← ⌘→ y ⌥← ⌥→), para pegar la clave de la IA como siempre.
     */
    private static void atajosMac() {
        int cmd = InputEvent.META_DOWN_MASK;
        int opc = InputEvent.ALT_DOWN_MASK;
        int may = InputEvent.SHIFT_DOWN_MASK;
        Object[][] atajos = {
                {KeyEvent.VK_C, cmd, DefaultEditorKit.copyAction},
                {KeyEvent.VK_V, cmd, DefaultEditorKit.pasteAction},
                {KeyEvent.VK_X, cmd, DefaultEditorKit.cutAction},
                {KeyEvent.VK_A, cmd, DefaultEditorKit.selectAllAction},
                {KeyEvent.VK_LEFT, cmd, DefaultEditorKit.beginLineAction},
                {KeyEvent.VK_RIGHT, cmd, DefaultEditorKit.endLineAction},
                {KeyEvent.VK_LEFT, cmd | may, DefaultEditorKit.selectionBeginLineAction},
                {KeyEvent.VK_RIGHT, cmd | may, DefaultEditorKit.selectionEndLineAction},
                {KeyEvent.VK_LEFT, opc, DefaultEditorKit.previousWordAction},
                {KeyEvent.VK_RIGHT, opc, DefaultEditorKit.nextWordAction},
                {KeyEvent.VK_LEFT, opc | may, DefaultEditorKit.selectionPreviousWordAction},
                {KeyEvent.VK_RIGHT, opc | may, DefaultEditorKit.selectionNextWordAction},
                {KeyEvent.VK_BACK_SPACE, opc, DefaultEditorKit.deletePrevWordAction},
                {KeyEvent.VK_BACK_SPACE, cmd, DefaultEditorKit.deletePrevWordAction},
        };
        for (String mapa : new String[]{"TextField.focusInputMap", "PasswordField.focusInputMap",
                "FormattedTextField.focusInputMap", "TextArea.focusInputMap", "TextPane.focusInputMap",
                "EditorPane.focusInputMap"}) {
            Object o = UIManager.get(mapa);
            if (o instanceof InputMap) {
                InputMap im = (InputMap) o;
                for (Object[] a : atajos) {
                    im.put(KeyStroke.getKeyStroke((Integer) a[0], (Integer) a[1]), a[2]);
                }
            }
        }
    }

    private static void registrar(Almacen almacen, Throwable error) {
        try {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            Files.createDirectories(almacen.getBase());
            Files.write(almacen.getBase().resolve("errores.log"),
                    ("[" + LocalDateTime.now() + "]\n" + sw + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignorado) {
            // nada más que hacer
        }
    }
}
