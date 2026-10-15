package catalogador.ui;

import catalogador.logica.Catalogos;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;
import catalogador.modelo.TerminoOficial;
import catalogador.modelo.TipoLista;
import catalogador.util.Texto;

import javax.swing.Icon;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Modelos y "pintores" de las tablas de la ventana principal. */
final class Tablas {

    private Tablas() {
    }

    // ================================================================== Cola de trabajo

    static final class ModeloCola extends AbstractTableModel {
        private List<Grupo> grupos = new ArrayList<>();

        void poner(List<Grupo> nuevos) {
            grupos = nuevos;
            fireTableDataChanged();
        }

        Grupo grupo(int fila) {
            return fila >= 0 && fila < grupos.size() ? grupos.get(fila) : null;
        }

        int indice(Grupo g) {
            return grupos.indexOf(g);
        }

        @Override
        public int getRowCount() {
            return grupos.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "" : c == 1 ? "Equipo" : "Equipos";
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 2 ? Integer.class : Object.class;
        }

        @Override
        public Object getValueAt(int f, int c) {
            Grupo g = grupos.get(f);
            switch (c) {
                case 0: return g.getEstado();
                case 1: return g;
                default: return g.getEquipos();
            }
        }
    }

    static final class RenderCola extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int f, int c) {
            super.getTableCellRendererComponent(t, "", sel, false, f, c);
            setIcon(null);
            setToolTipText(null);
            setHorizontalAlignment(LEFT);
            if (v instanceof EstadoGrupo) {
                setIcon(new Punto(Estilo.colorEstado((EstadoGrupo) v)));
                setHorizontalAlignment(CENTER);
                setToolTipText(((EstadoGrupo) v).texto());
            } else if (v instanceof Grupo) {
                Grupo g = (Grupo) v;
                String extra = (g.getMarca() + " " + g.getModelo()).trim();
                String gris = sel ? "#dddddd" : "#8a94a6";
                setText("<html><nobr>" + Texto.html(g.getNombre())
                        + (extra.isEmpty() ? "" : " <font color='" + gris + "'>· " + Texto.html(extra) + "</font>")
                        + "</nobr></html>");
                setToolTipText(g.getNombre() + (extra.isEmpty() ? "" : " — " + extra));
            } else if (v instanceof Integer) {
                setText(Estilo.numero((Integer) v));
                setHorizontalAlignment(RIGHT);
            }
            return this;
        }
    }

    /** Círculo de color para el estado. */
    static final class Punto implements Icon {
        private final Color color;

        Punto(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, 10, 10);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 10;
        }

        @Override
        public int getIconHeight() {
            return 10;
        }
    }

    // ================================================================== Resultados del buscador

    /**
     * Resultados de un catálogo. Las columnas dependen del catálogo:
     * GMDN → Código | Nombre; Mantenimiento → una columna con el nombre y,
     * debajo, su clasificación (tipo de activo › uso › familia › subfamilia ›
     * subgrupo), que así se lee entera aunque el panel sea estrecho.
     */
    static final class ModeloResultados extends AbstractTableModel {
        static final String[] COLUMNAS_GMDN = {"Código", "Nombre GMDN"};
        static final String[] COLUMNAS_MANT = {
                "Nombre de mantenimiento   ·   tipo de activo › uso › familia › subfamilia › subgrupo"};

        private List<Catalogos.Resultado> resultados = Collections.emptyList();
        private TipoLista lista = TipoLista.GMDN;

        /** Cambia de catálogo (cambian las columnas). Devuelve true si ha cambiado. */
        boolean setLista(TipoLista nueva) {
            if (nueva == lista) {
                return false;
            }
            lista = nueva;
            resultados = Collections.emptyList();
            fireTableStructureChanged();
            return true;
        }

        TipoLista getLista() {
            return lista;
        }

        void poner(List<Catalogos.Resultado> nuevos) {
            resultados = nuevos;
            fireTableDataChanged();
        }

        TerminoOficial termino(int fila) {
            return fila >= 0 && fila < resultados.size() ? resultados.get(fila).getTermino() : null;
        }

        int indice(TerminoOficial t) {
            for (int i = 0; i < resultados.size(); i++) {
                if (resultados.get(i).getTermino() == t) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return resultados.size();
        }

        @Override
        public int getColumnCount() {
            return lista == TipoLista.GMDN ? COLUMNAS_GMDN.length : COLUMNAS_MANT.length;
        }

        @Override
        public String getColumnName(int c) {
            return lista == TipoLista.GMDN ? COLUMNAS_GMDN[c] : COLUMNAS_MANT[c];
        }

        @Override
        public Object getValueAt(int f, int c) {
            TerminoOficial t = resultados.get(f).getTermino();
            if (lista == TipoLista.GMDN) {
                return c == 0 ? new Codigo(t.getCodigo()) : t;
            }
            return t;
        }
    }

    /** Envoltorio para pintar el código GMDN con otro estilo. */
    static final class Codigo {
        final String texto;

        Codigo(String texto) {
            this.texto = texto;
        }

        @Override
        public String toString() {
            return texto;
        }
    }

    static final class RenderResultados extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int f, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, f, c);
            setToolTipText(null);
            setFont(t.getFont());
            setBorder(Estilo.relleno(0, 6, 0, 6));
            if (v instanceof Codigo) {
                setFont(t.getFont().deriveFont(Font.BOLD, t.getFont().getSize2D() - 0.5f));
                if (!sel) {
                    setForeground(Estilo.ACENTO);
                }
            } else if (v instanceof TerminoOficial && ((TerminoOficial) v).getLista() == TipoLista.MANTENIMIENTO) {
                TerminoOficial term = (TerminoOficial) v;
                String gris = sel ? "#e8ecf2" : Estilo.hex(Estilo.TEXTO_SUAVE);
                String morado = sel ? "#ffffff" : Estilo.hex(Estilo.MORADO);
                setText("<html><nobr><b><font color='" + morado + "'>" + Texto.html(term.getNombre()) + "</font></b>"
                        + (term.isCreadoEnPrograma() ? " &nbsp;<font color='" + (sel ? "#ffffff" : Estilo.hex(Estilo.VERDE))
                        + "' size='-2'><b>NUEVA</b></font>" : "")
                        + "<br><font color='" + gris + "' size='-1'>"
                        + Texto.html(term.getDescripcion().isEmpty() ? "(sin clasificación)" : term.getDescripcion())
                        + "</font></nobr></html>");
                setToolTipText(Estilo.envolver("<b>" + Texto.html(term.getNombre()) + "</b><br>"
                        + Texto.html(term.getDescripcion()) + (term.isCreadoEnPrograma()
                        ? "<br><i>Entrada creada en el programa el " + Texto.html(term.getFechaCreacion()) + "</i>" : ""), 380));
            } else if (v instanceof TerminoOficial) {
                TerminoOficial term = (TerminoOficial) v;
                setText(term.getNombre());
                if (!sel) {
                    setForeground(Estilo.TEXTO);
                }
                String extra = term.isCreadoEnPrograma() ? "<br><i>Entrada creada en el programa el "
                        + Texto.html(term.getFechaCreacion()) + "</i>" : "";
                if (!term.getDescripcion().isEmpty() || !extra.isEmpty()) {
                    setToolTipText(Estilo.envolver("<b>" + Texto.html(term.getNombre()) + "</b><br>"
                            + Texto.html(Texto.recortar(term.getDescripcion(), 500)) + extra, 380));
                }
            } else {
                if (!sel) {
                    setForeground(Estilo.TEXTO_SUAVE);
                }
                String s = v == null ? "" : v.toString();
                setToolTipText(s.isEmpty() ? null : s);
            }
            setHorizontalAlignment(SwingConstants.LEFT);
            return this;
        }
    }
}
