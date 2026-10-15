import catalogador.excel.LectorTablas;
import catalogador.ia.AsistenteIA;
import catalogador.ia.ClienteIA;
import catalogador.ia.ProveedorIA;
import catalogador.logica.Almacen;
import catalogador.logica.Catalogos;
import catalogador.logica.Exportador;
import catalogador.logica.ImportadorCatalogos;
import catalogador.logica.ImportadorInventario;
import catalogador.logica.ImportadorResultados;
import catalogador.logica.Memoria;
import catalogador.modelo.EstadoGrupo;
import catalogador.modelo.Grupo;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TipoLista;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Mide lo que tarda cada paso con un inventario grande y comprueba de punta a
 * punta que el resultado exportado coincide con el esperado.
 * Uso: Rendimiento <catalogos.xlsx> <inventario.xlsx|csv> <resultado_esperado.xlsx> <carpeta_trabajo> [url_ia_simulada]
 */
public class Rendimiento {

    static PrintWriter informe;
    static long t0;

    static void empezar() {
        System.gc();
        t0 = System.nanoTime();
    }

    static double segundos() {
        return (System.nanoTime() - t0) / 1e9;
    }

    static long memoriaMB() {
        Runtime r = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    static void fila(String paso, String tiempo, String detalle) {
        String l = String.format(Locale.ROOT, "%-46s %10s   %s", paso, tiempo, detalle);
        System.out.println(l);
        informe.println(paso + "\t" + tiempo + "\t" + detalle);
        informe.flush();
    }

    static String s(double seg) {
        return String.format(Locale.ROOT, "%.2f s", seg).replace('.', ',');
    }

    static String ms(double m) {
        return String.format(Locale.ROOT, "%.2f ms", m).replace('.', ',');
    }

    static String n(long x) {
        return String.format(new Locale("es", "ES"), "%,d", x);
    }

    public static void main(String[] a) throws Exception {
        File fCat = new File(a[0]);
        File fInv = new File(a[1]);
        File fEsp = new File(a[2]);
        Path trabajo = Paths.get(a[3]);
        String urlIA = a.length > 4 ? a[4] : null;
        Files.createDirectories(trabajo);
        informe = new PrintWriter(Files.newBufferedWriter(trabajo.resolve("informe_" + fInv.getName() + ".tsv")));
        System.out.println("Java " + System.getProperty("java.version") + " · " + System.getProperty("os.name") + " · "
                + Runtime.getRuntime().availableProcessors() + " núcleos · memoria máxima "
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        informe.println("# " + fInv.getName() + "\tJava " + System.getProperty("java.version") + "\t"
                + Runtime.getRuntime().availableProcessors() + " núcleos\t" + Runtime.getRuntime().maxMemory() / 1048576 + " MB");
        System.out.println();

        // 1. Catálogos
        empezar();
        ImportadorCatalogos.Resultado rc = ImportadorCatalogos.leer(fCat);
        Path base = trabajo.resolve("datos_" + fInv.getName().replaceAll("\\W", "_"));
        Almacen al = new Almacen(base);
        al.prepararCarpetas();
        Catalogos cats = new Catalogos(al.carpetaCatalogos());
        cats.reemplazar(TipoLista.GMDN, rc.gmdn, fCat.getName(), rc.hojaGmdn);
        cats.reemplazar(TipoLista.MANTENIMIENTO, rc.mantenimiento, fCat.getName(), rc.hojaMantenimiento);
        fila("Importar catálogos", s(segundos()), "GMDN " + n(cats.total(TipoLista.GMDN)) + " · Mantenimiento "
                + n(cats.total(TipoLista.MANTENIMIENTO)));

        // 2. Inventario
        empezar();
        ImportadorInventario.Mapeo m = ImportadorInventario.detectar(fInv);
        fila("Reconocer las columnas", s(segundos()), "cabeceras en la fila " + (m.filaCabecera + 1));
        long memAntes = memoriaMB();
        empezar();
        Proyecto p = ImportadorInventario.importar(fInv, m, true, null);
        double tImp = segundos();
        long memDespues = memoriaMB();
        fila("Leer el inventario y agrupar", s(tImp), n(p.getFilasLeidas()) + " filas · " + n(p.getDescartadosBaja())
                + " de baja · " + n(p.numEquipos()) + " equipos · " + n(p.getGrupos().size()) + " grupos · "
                + p.centros().size() + " centros");
        fila("Memoria ocupada por el proyecto", n(memDespues - memAntes) + " MB",
                String.format(Locale.ROOT, "%.0f bytes por equipo", (memDespues - memAntes) * 1048576.0 / p.numEquipos()));
        long nb = p.getGrupos().isEmpty() ? 0 : p.getGrupos().get(0).getEquipos();
        fila("Grupo más numeroso", "", p.getGrupos().get(0).getNombre() + " (" + n(nb) + " equipos)");

        // 3. Guardar y reabrir
        empezar();
        p.setNombre("prueba");
        p.setFicheroOrigen(fInv.getName());
        p.setCreado("11/12/2026 10:00");
        p.setCarpeta(al.nuevaCarpetaProyecto("prueba"));
        al.guardarInventario(p);
        al.guardarEstado(p);
        fila("Guardar el proyecto (una vez)", s(segundos()), (Files.size(p.getCarpeta().resolve("inventario.tsv")) / 1048576)
                + " MB en inventario.tsv");
        empezar();
        Proyecto p2 = al.abrir(p.getCarpeta(), null);
        fila("Abrir el proyecto guardado", s(segundos()), n(p2.getGrupos().size()) + " grupos");
        p = p2;

        // 4. Buscador
        Random rnd = new Random(1);
        List<Grupo> gs = p.getGrupos();
        long tot = 0;
        long max = 0;
        int busquedas = 300;
        for (int i = 0; i < busquedas; i++) {
            String q = gs.get(rnd.nextInt(gs.size())).getNombre();
            TipoLista l = i % 2 == 0 ? TipoLista.GMDN : TipoLista.MANTENIMIENTO;
            long t = System.nanoTime();
            cats.buscar(q, l, true, 300);
            long d = System.nanoTime() - t;
            tot += d;
            max = Math.max(max, d);
        }
        fila("Buscar en los catálogos (media de " + busquedas + ")", ms(tot / 1e6 / busquedas), "máximo " + ms(max / 1e6));

        // 5. Aplicar el resultado esperado (marca todos los grupos como revisados y enseña a la memoria)
        Memoria mem = new Memoria(base);
        mem.cargar();
        empezar();
        ImportadorResultados.Resumen r = ImportadorResultados.aplicar(fEsp, p, cats, mem, "Resultado esperado",
                "11/12/2026 10:00", null);
        double tRes = segundos();
        empezar();
        al.registrarCambios(p, r.cambiados);
        mem.guardarCambios();
        double tGuardar = segundos();
        fila("Aplicar un resultado anterior (cruce por id)", s(tRes), n(r.filas) + " filas · " + n(r.gruposRevisados)
                + " grupos revisados · " + n(r.gruposEnConflicto) + " en conflicto · " + n(r.sinEquipo) + " sin equipo");
        fila("Guardar esos cambios y la memoria", s(tGuardar), n(mem.tamano()) + " clasificaciones en la memoria");

        // 6. Sugerencias con la memoria llena
        tot = 0;
        max = 0;
        int sug = 1000;
        for (int i = 0; i < sug; i++) {
            Grupo g = gs.get(rnd.nextInt(gs.size()));
            String nombre = g.getNombre();
            if (i % 2 == 1 && nombre.length() > 6) {
                nombre = nombre.substring(0, nombre.length() - 2) + "XZ"; // con errata
            }
            long t = System.nanoTime();
            mem.sugerir(nombre, g.getMarca(), g.getModelo(), 3);
            long d = System.nanoTime() - t;
            tot += d;
            max = Math.max(max, d);
        }
        fila("Calcular sugerencias (media de " + sug + ")", ms(tot / 1e6 / sug), "máximo " + ms(max / 1e6)
                + " · memoria de " + n(mem.tamano()));

        // 7. Guardar un grupo (Intro): una línea al final del diario, forzada a disco
        tot = 0;
        int guardados = Math.min(500, gs.size());
        for (int i = 0; i < guardados; i++) {
            Grupo g = gs.get(i);
            long t = System.nanoTime();
            al.registrarCambio(p, g);
            tot += System.nanoTime() - t;
        }
        fila("Guardar un grupo al pulsar Intro (media)", ms(tot / 1e6 / guardados), "diario de cambios forzado a disco");

        // 8. Exportar
        Exportador.Opciones o = new Exportador.Opciones();
        o.carpeta = trabajo.resolve("exportado_" + fInv.getName().replaceAll("\\W", "_")).toFile();
        o.carpeta.mkdirs();
        o.prefijo = "Catalogacion";
        empezar();
        List<Exportador.Fichero> xs = Exportador.exportar(p, o, null);
        double tX = segundos();
        long filasX = 0;
        long bytesX = 0;
        for (Exportador.Fichero x : xs) {
            filasX += x.getFilas();
            bytesX += x.getFichero().length();
        }
        fila("Exportar a Excel (un fichero por centro)", s(tX), xs.size() + " ficheros · " + n(filasX) + " filas · "
                + n(bytesX / 1048576) + " MB");
        o.csv = true;
        empezar();
        List<Exportador.Fichero> cs = Exportador.exportar(p, o, null);
        double tC = segundos();
        long bytesC = 0;
        for (Exportador.Fichero x : cs) {
            bytesC += x.getFichero().length();
        }
        fila("Exportar a CSV (un fichero por centro)", s(tC), cs.size() + " ficheros · " + n(bytesC / 1048576) + " MB");

        // 9. Comprobar fila a fila el Excel exportado contra el resultado esperado
        empezar();
        Map<String, String> esperado = new HashMap<>();
        final int[] cabEsp = {-1};
        LectorTablas.recorrer(fEsp, 0, (num, c) -> {
            if (cabEsp[0] < 0) {
                if (c.length > 1 && "Equipo_idEquipo".equals(c[1])) {
                    cabEsp[0] = num;
                }
                return true;
            }
            esperado.put(c[0] + "|" + c[1], (c.length > 2 ? c[2] : "") + "|" + (c.length > 3 ? c[3] : ""));
            return true;
        }, null);
        long[] cuenta = new long[3]; // iguales, distintas, que no están
        for (Exportador.Fichero x : xs) {
            LectorTablas.recorrer(x.getFichero(), 0, (num, c) -> {
                if (num == 0) {
                    return true;
                }
                String e = esperado.remove(c[0] + "|" + c[1]);
                String obtenido = (c.length > 2 ? c[2] : "") + "|" + (c.length > 3 ? c[3] : "");
                if (e == null) {
                    cuenta[2]++;
                } else if (e.equals(obtenido)) {
                    cuenta[0]++;
                } else {
                    cuenta[1]++;
                    if (cuenta[1] <= 5) {
                        System.out.println("   distinto " + c[0] + " " + c[1] + ": esperado " + e + " · obtenido " + obtenido);
                    }
                }
                return true;
            }, null);
        }
        fila("Comprobar el Excel exportado con el esperado", s(segundos()), n(cuenta[0]) + " filas iguales · "
                + n(cuenta[1]) + " distintas · " + n(cuenta[2]) + " de más · " + n(esperado.size()) + " que faltan");

        // 10. IA (servidor simulado: mide lo que añade el programa, sin la espera del modelo)
        if (urlIA != null) {
            ClienteIA cli = new ClienteIA(ProveedorIA.porId("personalizado"), urlIA, "buena", "modelo-simulado");
            AsistenteIA asis = new AsistenteIA(cli, cats, null);
            List<Grupo> lote = new ArrayList<>();
            int grupos = Math.min(400, gs.size());
            empezar();
            int conTermino = 0;
            int consultas = 0;
            for (int i = 0; i < grupos; i++) {
                lote.add(gs.get(i));
                if (lote.size() == 8 || i == grupos - 1) {
                    AsistenteIA.Resultado ra = asis.proponer(lote, "x");
                    consultas += ra.getConsultas();
                    for (catalogador.ia.PropuestaIA pr : ra.getPropuestas()) {
                        if (pr.tieneTermino()) {
                            conTermino++;
                        }
                    }
                    lote.clear();
                }
            }
            double tIA = segundos();
            fila("IA simulada: proponer " + grupos + " grupos (lotes de 8)", s(tIA), consultas + " consultas · "
                    + conTermino + " con término · " + n((cli.getTokensEntrada() + cli.getTokensSalida()) / grupos)
                    + " tokens por grupo");
            // Lo mismo con la API propia de Ollama (lotes de 4 y contexto ajustado a cada consulta)
            String raizOllama = urlIA.replaceFirst("/v1/?$", "") + "/ollama";
            ClienteIA ol = new ClienteIA(ProveedorIA.porId("ollama"), raizOllama, "", "qwen3:8b");
            AsistenteIA asisOl = new AsistenteIA(ol, cats, null);
            int gruposOl = Math.min(200, gs.size());
            empezar();
            int consultasOl = 0;
            int conTerminoOl = 0;
            for (int i = 0; i < gruposOl; i += 4) {
                AsistenteIA.Resultado ra = asisOl.proponer(gs.subList(i, Math.min(gruposOl, i + 4)), "x");
                consultasOl += ra.getConsultas();
                for (catalogador.ia.PropuestaIA pr : ra.getPropuestas()) {
                    if (pr.tieneTermino()) {
                        conTerminoOl++;
                    }
                }
            }
            fila("IA simulada (API de Ollama): " + gruposOl + " grupos (lotes de 4)", s(segundos()), consultasOl
                    + " consultas · " + conTerminoOl + " con término · "
                    + n((ol.getTokensEntrada() + ol.getTokensSalida()) / gruposOl) + " tokens por grupo");
        }
        long memFinal = memoriaMB();
        fila("Memoria total al terminar", n(memFinal) + " MB", "");
        informe.close();
        System.exit(0);
    }
}
