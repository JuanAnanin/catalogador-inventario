package catalogador.logica;

import catalogador.excel.LectorTablas;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TerminoOficial;
import catalogador.modelo.TipoLista;
import catalogador.util.Texto;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aprovecha una catalogación hecha antes (un fichero con el formato de
 * salida: Equipo_codiCentro, Equipo_idEquipo, CÓDIGO GMDN, NOMBRE…).
 * <p>
 * Como ese fichero no trae el nombre del equipo, se cruza con el proyecto
 * abierto por centro + identificador. Para cada grupo cuyos equipos tienen
 * todos la misma clasificación:
 * <ul>
 *   <li>el programa la aprende (para sugerirla en aparatos parecidos), y</li>
 *   <li>si el grupo estaba pendiente, queda revisado con esa clasificación.</li>
 * </ul>
 * Los grupos con clasificaciones distintas entre sus equipos no se tocan.
 */
public final class ImportadorResultados {

    public static final class Resumen {
        public long filas;
        public long conClasificacion;
        public long sinEquipo;          // centro + id que no están en el proyecto
        public long fueraDeCatalogo;    // términos que no están en los catálogos cargados
        public int gruposRevisados;
        public int gruposYaRevisados;
        public int gruposEnConflicto;
        public int aprendidas;
        public final List<Grupo> cambiados = new ArrayList<>();
    }

    private static final class Asignacion {
        final TipoLista lista;
        final String codigo;
        final String nombre;
        int veces;

        Asignacion(TipoLista lista, String codigo, String nombre) {
            this.lista = lista;
            this.codigo = codigo;
            this.nombre = nombre;
        }
    }

    private ImportadorResultados() {
    }

    /** Posición de [hoja, fila de cabecera, centro, id, código, nombre], o null si no se reconoce. */
    static int[] detectar(File f) throws IOException {
        int numHojas = LectorTablas.hojas(f).size();
        for (int h = 0; h < numHojas; h++) {
            int[] r = {h, -1, -1, -1, -1, -1};
            LectorTablas.recorrer(f, h, (num, celdas) -> {
                int centro = -1;
                int id = -1;
                int codigo = -1;
                int nombre = -1;
                for (int c = 0; c < celdas.length; c++) {
                    String k = ImportadorInventario.clave(celdas[c]);
                    if (k.equals("equipocodicentro")) {
                        centro = c;
                    } else if (k.equals("equipoidequipo")) {
                        id = c;
                    } else if (codigo < 0 && k.contains("codigo") && k.contains("gmdn")) {
                        codigo = c;
                    } else if (nombre < 0 && k.contains("nombre")) {
                        nombre = c;
                    }
                }
                if (centro >= 0 && id >= 0 && nombre >= 0) {
                    r[1] = num;
                    r[2] = centro;
                    r[3] = id;
                    r[4] = codigo;
                    r[5] = nombre;
                    return false;
                }
                return num < 30;
            }, null);
            if (r[1] >= 0) {
                return r;
            }
        }
        return null;
    }

    public static Resumen aplicar(File f, Proyecto p, Catalogos cats, Memoria memoria, String origen, String fecha,
                                  LectorTablas.Progreso progreso) throws IOException {
        int[] pos = detectar(f);
        if (pos == null) {
            throw new IOException("No parece un resultado de catalogación.\n\nSe esperan las columnas "
                    + "Equipo_codiCentro, Equipo_idEquipo, CÓDIGO GMDN y una columna de NOMBRE.");
        }
        Map<String, Integer> equipos = new HashMap<>(p.numEquipos() * 2);
        for (int i = 0; i < p.numEquipos(); i++) {
            equipos.put(p.centro(i) + "\u0001" + p.id(i), i);
        }
        Map<Grupo, Map<String, Asignacion>> porGrupo = new LinkedHashMap<>();
        Resumen r = new Resumen();
        LectorTablas.recorrer(f, pos[0], (num, celdas) -> {
            if (num <= pos[1]) {
                return true;
            }
            r.filas++;
            String nombre = celda(celdas, pos[5]);
            if (nombre.isEmpty()) {
                return true;
            }
            r.conClasificacion++;
            Integer i = equipos.get(celda(celdas, pos[2]) + "\u0001" + celda(celdas, pos[3]));
            if (i == null) {
                r.sinEquipo++;
                return true;
            }
            String codigo = celda(celdas, pos[4]);
            TipoLista lista = codigo.isEmpty() ? TipoLista.MANTENIMIENTO : TipoLista.GMDN;
            TerminoOficial t = cats.porCodigo(lista, codigo.isEmpty() ? nombre : codigo);
            if (t == null) {
                r.fueraDeCatalogo++;
            } else {
                nombre = t.getNombre();
                codigo = t.getCodigo();
            }
            if (lista == TipoLista.MANTENIMIENTO) {
                codigo = nombre; // en Mantenimiento el nombre es el identificador
            }
            Map<String, Asignacion> m = porGrupo.computeIfAbsent(p.grupoDeFila(i), g -> new LinkedHashMap<>(2));
            String clave = lista.name() + "|" + codigo;
            Asignacion a = m.get(clave);
            if (a == null) {
                a = new Asignacion(lista, codigo, nombre);
                m.put(clave, a);
            }
            a.veces++;
            return true;
        }, progreso);

        for (Map.Entry<Grupo, Map<String, Asignacion>> e : porGrupo.entrySet()) {
            Grupo g = e.getKey();
            if (e.getValue().size() > 1) {
                r.gruposEnConflicto++;
                continue;
            }
            Asignacion a = e.getValue().values().iterator().next();
            memoria.aprender(g.getNombre(), g.getMarca(), g.getModelo(), a.lista, a.codigo, a.nombre, fecha);
            r.aprendidas++;
            if (g.getEstado() == EstadoGrupo.PENDIENTE) {
                g.asignar(a.lista, a.codigo, a.nombre, origen, fecha);
                r.gruposRevisados++;
                r.cambiados.add(g);
            } else {
                r.gruposYaRevisados++;
            }
        }
        return r;
    }

    private static String celda(String[] celdas, int col) {
        return col >= 0 && col < celdas.length && celdas[col] != null ? Texto.limpio(celdas[col]) : "";
    }
}
