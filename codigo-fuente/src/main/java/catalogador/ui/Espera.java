package catalogador.ui;

import catalogador.excel.LectorTablas;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.Window;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Ejecuta una tarea larga (leer un inventario de 600.000 filas…) en segundo
 * plano mostrando "Un momento…" y, si la tarea lo informa, cuánto lleva.
 */
final class Espera {

    interface Tarea<T> {
        T ejecutar() throws Exception;
    }

    interface TareaConAvance<T> {
        T ejecutar(Avance avance) throws Exception;
    }

    /** Lo que la tarea usa para contar cómo va (se puede llamar desde cualquier hilo). */
    static final class Avance implements LectorTablas.Progreso {
        private final JLabel mensaje;
        private final JLabel detalle;
        private final JProgressBar barra;
        private final String unidad;
        private volatile long ultimoAviso;
        private volatile boolean cancelado;
        private volatile long segundosRestantes = -1;

        Avance(JLabel mensaje, JLabel detalle, JProgressBar barra, String unidad) {
            this.mensaje = mensaje;
            this.detalle = detalle;
            this.barra = barra;
            this.unidad = unidad;
        }

        @Override
        public void avance(long hecho, long total) {
            long ahora = System.currentTimeMillis();
            if (ahora - ultimoAviso < 80) {
                return; // no hace falta refrescar más de ~12 veces por segundo
            }
            ultimoAviso = ahora;
            long resto = segundosRestantes;
            SwingUtilities.invokeLater(() -> {
                if (total > 0) {
                    barra.setIndeterminate(false);
                    int v = (int) Math.min(1000, hecho * 1000 / total);
                    barra.setValue(v);
                    detalle.setText(Estilo.numero(Math.min(hecho, total)) + " de " + Estilo.numero(total) + " " + unidad
                            + "  (" + v / 10 + " %)" + (resto >= 0 && hecho < total ? " · " + tiempoRestante(resto) : ""));
                } else {
                    barra.setIndeterminate(true);
                    detalle.setText(Estilo.numero(hecho) + " " + unidad + "…");
                }
            });
        }

        /** Como avance(hecho, total), diciendo además cuánto falta (lo calcula quien llama). */
        void avance(long hecho, long total, long segundosQueFaltan) {
            segundosRestantes = segundosQueFaltan;
            ultimoAviso = 0;
            avance(hecho, total);
        }

        /** «quedan unos 25 min», «quedan unas 1 h 20 min», «queda menos de un minuto». */
        static String tiempoRestante(long s) {
            if (s < 60) {
                return "queda menos de un minuto";
            }
            long min = (s + 30) / 60;
            if (min < 60) {
                return "quedan unos " + min + " min";
            }
            long m = min % 60;
            return "quedan unas " + min / 60 + " h" + (m == 0 ? "" : " " + m + " min");
        }

        /** true si se ha pulsado «Cancelar» (solo en las tareas que se pueden cancelar). */
        boolean cancelado() {
            return cancelado;
        }

        /** Cambia el texto principal (para tareas con varias fases). */
        void fase(String texto, String nuevaUnidad) {
            SwingUtilities.invokeLater(() -> {
                mensaje.setText(texto);
                barra.setIndeterminate(true);
                detalle.setText(nuevaUnidad == null ? " " : nuevaUnidad);
            });
        }
    }

    private Espera() {
    }

    static <T> void ejecutar(Component padre, String mensaje, Tarea<T> tarea, Consumer<T> alTerminar) {
        ejecutar(padre, mensaje, "", a -> tarea.ejecutar(), alTerminar);
    }

    static <T> void ejecutar(Component padre, String mensaje, String unidad, TareaConAvance<T> tarea,
                             Consumer<T> alTerminar) {
        ejecutar(padre, mensaje, unidad, tarea, alTerminar, false);
    }

    /**
     * Como {@link #ejecutar(Component, String, String, TareaConAvance, Consumer)}, con un botón
     * «Cancelar»: la tarea debe mirar {@link Avance#cancelado()} y terminar devolviendo lo hecho.
     */
    static <T> void ejecutarCancelable(Component padre, String mensaje, String unidad, TareaConAvance<T> tarea,
                                       Consumer<T> alTerminar) {
        ejecutar(padre, mensaje, unidad, tarea, alTerminar, true);
    }

    private static <T> void ejecutar(Component padre, String mensaje, String unidad, TareaConAvance<T> tarea,
                                     Consumer<T> alTerminar, boolean cancelable) {
        Window ventana = padre instanceof Window ? (Window) padre : SwingUtilities.getWindowAncestor(padre);
        JDialog d = new JDialog(ventana, "Un momento…", Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(18, 22, 16, 22));
        JLabel lblMensaje = new JLabel(mensaje);
        p.add(lblMensaje, BorderLayout.NORTH);
        JProgressBar barra = new JProgressBar(0, 1000);
        barra.setIndeterminate(true);
        p.add(barra, BorderLayout.CENTER);
        JLabel lblDetalle = new JLabel(" ");
        lblDetalle.setFont(Estilo.fuente(Font.PLAIN, 11.5f));
        lblDetalle.setForeground(Estilo.TEXTO_SUAVE);
        JPanel sur = new JPanel(new BorderLayout(10, 0));
        sur.add(lblDetalle, BorderLayout.CENTER);
        javax.swing.JButton cancelar = new javax.swing.JButton("Cancelar");
        if (cancelable) {
            sur.add(cancelar, BorderLayout.EAST);
        }
        p.add(sur, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setSize(Math.max(d.getWidth(), 440), d.getHeight());
        d.setLocationRelativeTo(ventana);
        Avance avance = new Avance(lblMensaje, lblDetalle, barra, unidad);
        java.util.concurrent.atomic.AtomicReference<Thread> hilo = new java.util.concurrent.atomic.AtomicReference<>();
        cancelar.addActionListener(e -> {
            avance.cancelado = true;
            cancelar.setEnabled(false);
            cancelar.setText("Cancelando…");
            Thread t = hilo.get();
            if (t != null) {
                t.interrupt(); // corta la espera de una consulta en curso
            }
        });

        SwingWorker<T, Void> trabajador = new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                hilo.set(Thread.currentThread());
                try {
                    return tarea.ejecutar(avance);
                } finally {
                    hilo.set(null);
                    Thread.interrupted(); // el hilo vuelve limpio al grupo de SwingWorker
                }
            }

            @Override
            protected void done() {
                d.dispose();
                try {
                    alTerminar.accept(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable causa = e.getCause() != null ? e.getCause() : e;
                    String texto;
                    if (causa instanceof OutOfMemoryError) {
                        texto = "No hay memoria suficiente para terminar la operación.\n\n"
                                + "Cierra otros programas y vuelve a intentarlo. Si el problema sigue,\n"
                                + "abre el Catalogador con el lanzador incluido (reserva más memoria).";
                    } else {
                        texto = catalogador.util.Ficheros.mensaje(causa);
                    }
                    JOptionPane.showMessageDialog(ventana, texto, "No se pudo completar", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        trabajador.execute();
        d.setVisible(true);
    }
}
