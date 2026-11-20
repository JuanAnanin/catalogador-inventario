package catalogador.ui;

import catalogador.excel.Hoja;
import catalogador.excel.LectorTablas;
import catalogador.logica.Almacen;
import catalogador.logica.CatalogacionAutomatica;
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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Comprobación automática de los requisitos de los dos documentos recibidos
 * («Memoria del Proyecto» e «Información») con los ficheros reales y el paquete
 * de pruebas. Cada comprobación lleva el identificador del requisito de la ERS.
 * Uso: PruebaRequisitos <carpeta ejemplos> <carpeta de trabajo> [--sin-600k]
 */
public final class PruebaRequisitos {

    static int ok;
    static int mal;
    static PrintWriter informe;

    static void comprobar(String req, boolean cumple, String que) {
        String l = (cumple ? "  ✔ " : "  ✘ ") + req + "  " + que;
        System.out.println(l);
        informe.println((cumple ? "OK" : "FALLO") + "\t" + req + "\t" + que);
        informe.flush();
        if (cumple) {
            ok++;
        } else {
            mal++;
        }
    }

    static void titulo(String t) {
        System.out.println("\n== " + t);
    }

    public static void main(String[] a) throws Exception {
        Path ej = Paths.get(a[0]);
        Path trabajo = Paths.get(a[1]);
        boolean grande = !Arrays.asList(a).contains("--sin-600k");
        borrar(trabajo);
        Files.createDirectories(trabajo);
        informe = new PrintWriter(Files.newBufferedWriter(trabajo.resolve("requisitos.tsv")));

        File catReal = ej.resolve("Datos_catalogo_que_paso_al_programa.xlsx").toFile();
        File invReal = ej.resolve("Datos_inventario_que_paso_al_programa.xlsx").toFile();
        File salidaModelo = ej.resolve("Datos_que_el_programa_debe_devolver.xlsx").toFile();
        // Ficheros pequeños del paquete de pruebas: en «codigo-fuente/pruebas/datos» (o «--datos=carpeta»)
        Path pr = ej.resolve("pruebas");
        for (String x : a) {
            if (x.startsWith("--datos=")) {
                pr = Paths.get(x.substring(8));
            }
        }
        if (!Files.isDirectory(pr)) {
            pr = Paths.get("pruebas", "datos");
        }

        // ------------------------------------------------------------------ ficheros reales
        titulo("Ficheros recibidos");
        ImportadorCatalogos.Resultado rc = ImportadorCatalogos.leer(catReal);
        comprobar("RF-23", rc.gmdn != null && rc.mantenimiento != null, "el Excel de catálogos tiene las dos hojas: «"
                + rc.hojaGmdn + "» y «" + rc.hojaMantenimiento + "»");
        comprobar("RF-23", rc.gmdn.size() == 16 && rc.gmdnRepetidos == 3, "GMDN: 16 términos (3 filas con código repetido)");
        comprobar("RF-23", rc.mantenimiento.size() == 641, "Mantenimiento: 641 entradas");
        Almacen al = new Almacen(trabajo.resolve("datos"));
        al.prepararCarpetas();
        Catalogos cats = new Catalogos(al.carpetaCatalogos());
        cats.reemplazar(TipoLista.GMDN, rc.gmdn, catReal.getName(), rc.hojaGmdn);
        cats.reemplazar(TipoLista.MANTENIMIENTO, rc.mantenimiento, catReal.getName(), rc.hojaMantenimiento);

        ImportadorInventario.Mapeo m = ImportadorInventario.detectar(invReal);
        comprobar("RF-12", m != null && m.valido() && m.filaCabecera == 1,
                "inventario: se reconocen las cabeceras literales en la fila 2");
        comprobar("RF-12", m.completo(), "inventario: están las 8 columnas que usa el programa");
        Proyecto p = ImportadorInventario.importar(invReal, m, true, null);
        comprobar("RF-15", p.getFilasLeidas() == 35 && p.getDescartadosBaja() == 16 && p.numEquipos() == 19,
                "Equipo_debaja: 35 filas, 16 con S descartadas, 19 con N a catalogar");
        comprobar("RF-01", p.getGrupos().size() == 5, "los 19 equipos se juntan en 5 grupos de equipos iguales");
        comprobar("RF-02", ordenados(p), "los grupos salen de más a menos equipos (primero «"
                + p.getGrupos().get(0).getNombre() + "», " + p.getGrupos().get(0).getEquipos() + ")");
        Grupo rx = buscar(p, "SISTEMA RADIOLOGIA INTRAORAL");
        comprobar("RF-13", rx != null && rx.getMarca().equals("OWANDY") && rx.getModelo().equals("RX DC")
                && !rx.getReferencia().isEmpty(), "del grupo se tienen nombre, referencia, marca y modelo");
        comprobar("RF-14", rx != null && rx.isElectromedicina(), "«Electromedicina» cuando Equipo_codiTipoActivo = E");

        // ------------------------------------------------------------------ catálogos en pantalla
        titulo("Catálogos");
        TerminoOficial amalgama = primero(cats.buscar("amalgama", TipoLista.GMDN, false, 10));
        comprobar("RF-18", amalgama != null && amalgama.getCodigo().equals("10082")
                && amalgama.getDescripcion().startsWith("Instrumento conectado"),
                "GMDN: código, nombre y descripción («amalgama» → 10082 Mezclador de amalgama dental)");
        comprobar("RF-05", codigo(primero(cats.buscar("10082", TipoLista.GMDN, false, 10))).equals("10082"),
                "se puede buscar por código GMDN");
        comprobar("RF-05", codigo(primero(cats.buscar("amalgma dentl", TipoLista.GMDN, false, 10))).equals("10082"),
                "encuentra aunque se escriba con erratas («amalgma dentl»)");
        comprobar("RF-05", codigo(primero(cats.buscar("pulsaciones ventriculo", TipoLista.GMDN, true, 10))).equals("10164"),
                "busca también en la descripción («pulsaciones ventriculo» → Apexcardiógrafo)");
        TerminoOficial ext = cats.porCodigo(TipoLista.MANTENIMIENTO, "EXTERMINADOR DE INSECTOS");
        comprobar("RF-19", ext != null && ext.nivel(0).getNombre().equals("MOBILIARIO")
                        && ext.nivel(1).getNombre().equals("ACCESORIOS DE LIMPIEZA Y SANEAMIENTO")
                        && ext.nivel(2).getNombre().equals("APARATOS ELECTRICOS")
                        && ext.nivel(3).getNombre().equals("EXTERMINADORES DE INSECTOS"),
                "Mantenimiento: nombre + tipo de activo, uso, familia, subfamilia y subgrupo");
        comprobar("RF-22", cats.buscar("armario", TipoLista.MANTENIMIENTO, false, 300).getResultados().stream()
                .allMatch(r -> r.getTermino().getLista() == TipoLista.MANTENIMIENTO), "cada búsqueda es en un solo catálogo");
        TerminoOficial nbsp = cats.porCodigo(TipoLista.MANTENIMIENTO, "AMPLIADORA FOTOGRAFICA");
        comprobar("RF-23", nbsp != null && nbsp.getNombre().equals("AMPLIADORA FOTOGRAFICA"),
                "se quitan los espacios invisibles del final (U+00A0 en «AMPLIADORA FOTOGRAFICA»)");

        // ------------------------------------------------------------------ paquete de pruebas
        titulo("Paquete de pruebas (codigo-fuente/pruebas/datos)");
        ImportadorCatalogos.Resultado rp = ImportadorCatalogos.leer(pr.resolve("Catalogos_PRUEBA.xlsx").toFile());
        Catalogos catsP = new Catalogos(trabajo.resolve("datosP").resolve("catalogos"));
        Files.createDirectories(trabajo.resolve("datosP").resolve("catalogos"));
        catsP.reemplazar(TipoLista.GMDN, rp.gmdn, "Catalogos_PRUEBA.xlsx", rp.hojaGmdn);
        catsP.reemplazar(TipoLista.MANTENIMIENTO, rp.mantenimiento, "Catalogos_PRUEBA.xlsx", rp.hojaMantenimiento);
        comprobar("RF-23", catsP.total(TipoLista.GMDN) == 23 && catsP.total(TipoLista.MANTENIMIENTO) == 641
                        && catsP.porCodigo(TipoLista.GMDN, "34870") != null,
                "Catalogos_PRUEBA: GMDN 23 (con el 34870 del ejemplo de la hoja de mejoras) · Mantenimiento 641");
        File casos = pr.resolve("Inventario_PRUEBA_casos.xlsx").toFile();
        Proyecto pc = ImportadorInventario.importar(casos, ImportadorInventario.detectar(casos), true, null);
        comprobar("RF-15", pc.getFilasLeidas() == 51 && pc.getDescartadosBaja() == 6 && pc.getDescartadosOtros() == 2
                && pc.numEquipos() == 43, "51 filas · 6 de baja · 2 con otro valor en Equipo_debaja · 43 a catalogar");
        comprobar("RF-01", pc.getGrupos().size() == 11 && pc.centros().size() == 3, "11 grupos y 3 centros");
        long electro = pc.getGrupos().stream().filter(Grupo::isElectromedicina).count();
        comprobar("RF-14", electro == 6, "6 grupos de electromedicina");
        Grupo bomba = buscar(pc, "BOMBA DE INFUSION");
        comprobar("RF-01", bomba != null && bomba.getEquipos() == 6 && bomba.getVariantesNombre().noVacias().size() == 3,
                "«BOMBA DE INFUSION» escrita de 3 formas: un solo grupo de 6 equipos");

        File csv = pr.resolve("Inventario_PRUEBA_casos.csv").toFile();
        Proyecto pcsv = ImportadorInventario.importar(csv, ImportadorInventario.detectar(csv), true, null);
        comprobar("RF-12", pcsv.getFilasLeidas() == 51 && pcsv.numEquipos() == 43 && pcsv.getGrupos().size() == 11
                && buscar(pcsv, "BOMBA DE INFUSION").getVariantesNombre().noVacias().stream()
                .anyMatch(v -> v.getKey().equals("Bomba de infusión")),
                "el mismo inventario en CSV (Windows-1252) da las mismas cifras y lee bien «Bomba de infusión»");

        File otros = pr.resolve("Inventario_PRUEBA_otros_titulos.xlsx").toFile();
        ImportadorInventario.Mapeo mo = ImportadorInventario.detectar(otros);
        comprobar("RF-12", mo == null || !mo.valido(), "con otros títulos no se reconocen las columnas solas");
        Hoja h = LectorTablas.leerHoja(otros, 0, 50);
        int filaCab = h.filaCabeceraProbable();
        Map<String, Integer> adiv = DialogoImportacion.adivinar(VentanaPrincipal.camposInventario(), h.getFilas().get(filaCab));
        ImportadorInventario.Mapeo mm = new ImportadorInventario.Mapeo();
        mm.filaCabecera = filaCab;
        for (int i = 0; i < mm.col.length; i++) {
            Integer c = adiv.get(String.valueOf(i));
            mm.col[i] = c == null ? -1 : c;
        }
        Proyecto po = ImportadorInventario.importar(otros, mm, true, null);
        comprobar("RF-12", po.numEquipos() == 43 && po.getGrupos().size() == 11,
                "…y la ventana de columnas las propone bien (Centro, Nº equipo, Denominación…): mismas cifras");

        // Columnas en otro orden, columnas de más y sin las opcionales (la duda de la hoja de mejoras)
        File colum = pr.resolve("Inventario_PRUEBA_columnas.xlsx").toFile();
        ImportadorInventario.Mapeo mc = ImportadorInventario.detectar(colum);
        comprobar("RF-12", mc != null && mc.valido() && !mc.completo()
                        && mc.faltan().equals(Arrays.asList("Equipo_referencia", "Equipo_codiTipoActivo"))
                        && mc.col[ImportadorInventario.NOMBRE] == 0 && mc.col[ImportadorInventario.CENTRO] == 5,
                "columnas en otro orden, 3 de más y sin Equipo_referencia ni Equipo_codiTipoActivo: se reconocen solas "
                        + "(faltan " + (mc == null ? "?" : mc.faltan()) + ", que no son obligatorias)");
        Proyecto pcol = ImportadorInventario.importar(colum, mc, true, null);
        comprobar("RF-12", pcol.getFilasLeidas() == 51 && pcol.getDescartadosBaja() == 6 && pcol.numEquipos() == 43
                        && pcol.getGrupos().size() == 11 && pcol.getGrupos().stream().noneMatch(Grupo::isElectromedicina),
                "…mismas cifras (51 filas · 6 de baja · 43 equipos · 11 grupos); sin tipo de activo no hay «Electromedicina»");

        // ------------------------------------------------------------------ revisar (memoria, sugerencias, posponer)
        titulo("Revisión");
        Memoria mem = new Memoria(trabajo.resolve("datosP"));
        mem.cargar();
        Grupo armario = buscar(pc, "ARMARIO");
        armario.asignar(TipoLista.MANTENIMIENTO, "ARMARIO", "ARMARIO", "Buscador", "11/12/2026 10:00");
        mem.aprender(armario.getNombre(), armario.getMarca(), armario.getModelo(), TipoLista.MANTENIMIENTO, "ARMARIO",
                "ARMARIO", "11/12/2026 10:00");
        TerminoOficial bombaT = catsP.porCodigo(TipoLista.GMDN, "990001");
        bomba.asignar(TipoLista.GMDN, "990001", bombaT.getNombre(), "Buscador", "11/12/2026 10:01");
        mem.aprender(bomba.getNombre(), bomba.getMarca(), bomba.getModelo(), TipoLista.GMDN, "990001", bombaT.getNombre(),
                "11/12/2026 10:01");
        Grupo armMet = buscar(pc, "ARMARIO METALICO");
        Sugerencia s1 = mem.sugerir(armMet.getNombre(), armMet.getMarca(), armMet.getModelo(), 3).get(0);
        comprobar("RF-03", s1.getCodigo().equals("ARMARIO") && s1.porcentaje() >= 65 && s1.porcentaje() < 85
                && Sugerencia.nivel(s1.getSimilitud()).equals("Revísalo"),
                "«ARMARIO METALICO» → sugiere ARMARIO al " + s1.porcentaje() + " % en NARANJA");
        Grupo bonba = buscar(pc, "BONBA INFUSION");
        Sugerencia s2 = mem.sugerir(bonba.getNombre(), bonba.getMarca(), bonba.getModelo(), 3).get(0);
        comprobar("RF-03", s2.getCodigo().equals("990001") && s2.porcentaje() >= 85
                        && Sugerencia.nivel(s2.getSimilitud()).equals("Casi seguro"),
                "«BONBA INFUSION» (errata) → sugiere 990001 al " + s2.porcentaje() + " % en VERDE");
        Grupo bbraun = pc.getGrupos().stream().filter(g -> g.getMarca().equals("B. BRAUN")).findFirst().orElse(null);
        Sugerencia s3 = bbraun == null ? null : mem.sugerir(bbraun.getNombre(), bbraun.getMarca(), bbraun.getModelo(), 3).get(0);
        comprobar("RF-03", s3 != null && s3.porcentaje() == 98, "mismo nombre y otra marca → 98 %");
        comprobar("RF-03", Sugerencia.color(0.9).getGreen() > Sugerencia.color(0.9).getRed()
                && Sugerencia.color(0.5).getRed() > 150, "verde = casi seguro; rojo = dudoso");
        // aceptar la sugerencia (un botón): asignar lo sugerido
        bbraun.asignar(s3.getLista(), s3.getCodigo(), s3.getNombreOficial(), "Sugerencia (98% parecido)", "11/12/2026 10:02");
        comprobar("RF-04", bbraun.getEstado() == EstadoGrupo.REVISADO && bbraun.getCodigo().equals("990001"),
                "aceptar la sugerencia la asigna y deja el grupo revisado");
        bbraun.setMarcaCorregida("B. BRAUN MELSUNGEN");
        comprobar("RF-06", Exportador.marcaFinal(bbraun, "B. BRAUN").equals("B. BRAUN MELSUNGEN"),
                "la marca corregida es la que sale al exportar (si se añaden marca y modelo)");
        Grupo raro = pc.getGrupos().stream().filter(g -> g.getNombre().startsWith("EQUIPO RARO")).findFirst().orElse(null);
        raro.posponer("11/12/2026 10:03");
        comprobar("RF-07", raro.getEstado() == EstadoGrupo.POSPUESTO && pc.contar(EstadoGrupo.POSPUESTO) == 1,
                "posponer deja el grupo marcado como duda para revisarlo después");

        // ------------------------------------------------------------------ nueva entrada de mantenimiento
        String uso = catsP.siguienteCodigo(1, "B");
        String fam = catsP.siguienteCodigo(2, "03");
        TerminoOficial pie = catsP.anadirMantenimiento(new String[]{"PIE DE GOTERO", "B", "MOBILIARIO", "03",
                catsP.nombreDeCodigo(1, "03"), fam, "PIES Y SOPORTES", "", "", "", ""});
        comprobar("RF-20", pie.isCreadoEnPrograma() && catsP.porCodigo(TipoLista.MANTENIMIENTO, "PIE DE GOTERO") != null
                && fam.startsWith("03") && fam.length() == 4, "crear «PIE DE GOTERO» con los códigos propuestos (uso nuevo "
                + uso + ", familia " + fam + ")");
        Catalogos catsP2 = new Catalogos(trabajo.resolve("datosP").resolve("catalogos"));
        catsP2.cargar();
        comprobar("RF-20", catsP2.porCodigo(TipoLista.MANTENIMIENTO, "PIE DE GOTERO") != null,
                "la entrada nueva queda guardada en disco");
        Grupo gotero = buscar(pc, "PIE DE GOTERO");
        gotero.asignar(TipoLista.MANTENIMIENTO, "PIE DE GOTERO", "PIE DE GOTERO", "Buscador", "11/12/2026 10:04");

        // ------------------------------------------------------------------ catálogo exportado igual al original
        titulo("Exportar el catálogo");
        File catSalida = trabajo.resolve("Catalogos_actualizados.xlsx").toFile();
        ExportadorCatalogo.exportar(catsP, Arrays.asList(TipoLista.values()), catSalida);
        List<Hoja> orig = LectorTablas.leer(pr.resolve("Catalogos_PRUEBA.xlsx").toFile());
        List<Hoja> nueva = LectorTablas.leer(catSalida);
        comprobar("RF-21", nueva.size() == 2 && nueva.get(0).getNombre().equals(orig.get(0).getNombre())
                && nueva.get(1).getNombre().equals(orig.get(1).getNombre()), "mismas hojas: «" + nueva.get(0).getNombre()
                + "» y «" + nueva.get(1).getNombre() + "»");
        comprobar("RF-21", nueva.get(1).getFilas().get(0).equals(orig.get(1).getFilas().get(0)),
                "misma cabecera de Mantenimiento (11 columnas, con los códigos)");
        int iguales = 0;
        for (List<String> f : orig.get(1).getFilas()) {
            if (nueva.get(1).getFilas().contains(f)) {
                iguales++;
            }
        }
        comprobar("RF-21", iguales == orig.get(1).getFilas().size() && nueva.get(1).numFilas() == orig.get(1).numFilas() + 1,
                "todas las filas originales, igual que estaban (códigos «01», «0101» como texto), más la nueva");

        // ------------------------------------------------------------------ guardado ante cierres
        titulo("Guardado automático");
        Almacen alP = new Almacen(trabajo.resolve("datosP"));
        alP.prepararCarpetas();
        pc.setNombre("casos");
        pc.setFicheroOrigen(casos.getName());
        pc.setCreado("11/12/2026 10:00");
        pc.setCarpeta(alP.nuevaCarpetaProyecto("casos"));
        alP.guardarInventario(pc);
        alP.guardarEstado(pc);
        Grupo monitor = buscar(pc, "MONITOR MULTIPARAMETRICO");
        monitor.asignar(TipoLista.GMDN, "990002", catsP.porCodigo(TipoLista.GMDN, "990002").getNombre(), "Buscador",
                "11/12/2026 10:05");
        alP.registrarCambio(pc, monitor);
        // se simula un corte de luz a mitad de escribir la siguiente línea
        Files.write(pc.getCarpeta().resolve("cambios.tsv"), "GRUPO\tmedia lí".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        Proyecto reabierto = alP.abrir(pc.getCarpeta(), null);
        Grupo m2 = buscar(reabierto, "MONITOR MULTIPARAMETRICO");
        comprobar("RF-08", m2 != null && m2.getEstado() == EstadoGrupo.REVISADO && m2.getCodigo().equals("990002"),
                "tras un cierre brusco (línea a medias) el proyecto se abre con lo guardado");
        comprobar("RF-24", alP.listarProyectos().size() == 1 && alP.listarProyectos().get(0).getNombre().equals("casos"),
                "el proyecto aparece en «Abrir proyecto guardado…» con su nombre");
        comprobar("RF-08", buscar(reabierto, "ARMARIO").getEstado() == EstadoGrupo.REVISADO
                && reabierto.contar(EstadoGrupo.POSPUESTO) == 1, "conserva revisados y pospuestos");

        // ------------------------------------------------------------------ exportar el resultado
        titulo("Exportar el resultado");
        catalogarResto(reabierto, catsP);
        Exportador.Opciones o = new Exportador.Opciones();
        o.carpeta = trabajo.resolve("resultado").toFile();
        o.carpeta.mkdirs();
        o.prefijo = "Catalogacion";
        List<Exportador.Fichero> fs = Exportador.exportar(reabierto, o, null);
        Map<String, Integer> filas = new HashMap<>();
        for (Exportador.Fichero f : fs) {
            filas.put(f.getCentro(), f.getFilas());
        }
        comprobar("RF-16", fs.size() == 3 && filas.get("GAIVI") == 18 && filas.get("GAICR") == 14 && filas.get("GAIPU") == 10,
                "un fichero por centro: GAIVI 18 · GAICR 14 · GAIPU 10 filas (el pospuesto no sale)");
        Hoja res = LectorTablas.leerHoja(fs.get(0).getFichero(), 0, 100);
        List<String> modelo = LectorTablas.leerHoja(salidaModelo, 0, 10).getFilas().get(2).subList(0, 4);
        comprobar("RF-17", res.getFilas().get(0).subList(0, 4).equals(modelo),
                "mismas columnas que el modelo recibido: " + String.join(" | ", modelo));
        boolean mantSinCodigo = true;
        boolean gmdnConCodigo = true;
        for (int i = 1; i < res.numFilas(); i++) {
            String cod = res.celda(i, 2);
            String nom = res.celda(i, 3);
            TerminoOficial t = catsP.porCodigo(TipoLista.MANTENIMIENTO, nom);
            if (t != null && !cod.isEmpty()) {
                mantSinCodigo = false;
            }
            if (t == null && !cod.matches("\\d+")) {
                gmdnConCodigo = false;
            }
        }
        comprobar("RF-17", mantSinCodigo && gmdnConCodigo, "los de Mantenimiento van sin CÓDIGO GMDN; los de GMDN, con él");
        o.incluirSinCatalogar = true;
        o.prefijo = "Con_sin_catalogar";
        int gaivi = Exportador.exportar(reabierto, o, null).stream().filter(f -> f.getCentro().equals("GAIVI"))
                .findFirst().get().getFilas();
        comprobar("RF-16", gaivi == 19, "con «incluir también los sin catalogar», GAIVI tiene 19 filas");
        o.csv = true;
        o.incluirSinCatalogar = false;
        o.prefijo = "CSV";
        List<Exportador.Fichero> fc = Exportador.exportar(reabierto, o, null);
        String primeraCsv = new String(Files.readAllBytes(fc.get(0).getFichero().toPath()), StandardCharsets.UTF_8)
                .split("\r\n")[0];
        comprobar("RF-16", fc.size() == 3 && primeraCsv.contains("Equipo_codiCentro;Equipo_idEquipo;CÓDIGO GMDN"),
                "también en CSV («;», UTF-8, se abre con doble clic en Excel)");
        // Centros cuyo nombre solo cambia en mayúsculas o en caracteres que no valen en un nombre de fichero
        Proyecto raros = new Proyecto();
        for (String c : new String[]{"C/S NORTE", "C:S NORTE", "c s norte", "GAIVI"}) {
            raros.anadirEquipo(c, String.valueOf(c.hashCode()), "SILLA", "", "", "", "B");
        }
        raros.terminarCarga();
        raros.agrupar();
        Exportador.Opciones or = new Exportador.Opciones();
        or.carpeta = trabajo.resolve("centros_raros").toFile();
        or.carpeta.mkdirs();
        or.incluirSinCatalogar = true;
        or.prefijo = "Catalogacion";
        List<Exportador.Fichero> fr = Exportador.exportar(raros, or, null);
        java.util.Set<String> nombresR = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Exportador.Fichero f : fr) {
            nombresR.add(f.getFichero().getName());
        }
        String[] creadosR = or.carpeta.list();
        comprobar("RF-16", fr.size() == 4 && nombresR.size() == 4 && creadosR != null && creadosR.length == 4,
                "centros «C/S NORTE», «C:S NORTE» y «c s norte»: 3 ficheros distintos, ninguno pisa a otro "
                        + "(Windows y macOS no distinguen mayúsculas): " + nombresR);

        // ------------------------------------------------------------------ elegir qué ficheros exportar
        titulo("Exportar solo los centros elegidos");
        Exportador.Opciones oe = new Exportador.Opciones();
        oe.carpeta = trabajo.resolve("resultado_elegidos").toFile();
        oe.carpeta.mkdirs();
        oe.prefijo = "Catalogacion";
        List<Exportador.Fichero> todosF = Exportador.previsualizar(reabierto, oe);
        oe.centros = new java.util.HashSet<>(Collections.singletonList("GAICR"));
        List<Exportador.Fichero> uno = Exportador.exportar(reabierto, oe, null);
        String[] enCarpeta = oe.carpeta.list();
        comprobar("RF-38", todosF.size() == 3 && uno.size() == 1 && uno.get(0).getCentro().equals("GAICR")
                        && uno.get(0).getFilas() == 14 && enCarpeta != null && enCarpeta.length == 1
                        && enCarpeta[0].equals("Catalogacion_GAICR.xlsx"),
                "solo un centro (GAICR): se crea un único fichero, Catalogacion_GAICR.xlsx, con sus 14 filas");
        oe.centros = new java.util.HashSet<>(Arrays.asList("GAIVI", "GAIPU"));
        List<Exportador.Fichero> dos = Exportador.exportar(reabierto, oe, null);
        comprobar("RF-38", dos.size() == 2 && Exportador.seleccionados(reabierto, oe).size() == 2
                        && dos.stream().noneMatch(f -> f.getCentro().equals("GAICR")) && oe.carpeta.list().length == 3,
                "varios (GAIVI y GAIPU): sus 2 ficheros; el de GAICR no se vuelve a escribir");
        oe.centros = null;
        oe.prefijo = "Todos";
        comprobar("RF-38", Exportador.exportar(reabierto, oe, null).size() == 3, "todos (opción por defecto): 3 ficheros");
        oe.centros = new java.util.HashSet<>(Collections.singletonList("GAIPU"));
        oe.prefijo = "Catalogacion";
        comprobar("RF-38", Exportador.seleccionados(reabierto, oe).get(0).getFichero().getName()
                        .equals(todosF.stream().filter(f -> f.getCentro().equals("GAIPU")).findFirst().get().getFichero().getName()),
                "el nombre de cada fichero no cambia según los que se elijan");

        // ------------------------------------------------------------------ catalogación automática de los idénticos
        titulo("Catalogación automática de los idénticos (opción; modo manual y modo IA)");
        // Lo catalogado en el paquete de pruebas queda en la memoria, como al trabajar en la ventana
        Memoria memA = new Memoria(trabajo.resolve("datosA"));
        Files.createDirectories(trabajo.resolve("datosA"));
        memA.cargar();
        for (Grupo g : reabierto.getGrupos()) {
            if (g.getEstado() == EstadoGrupo.REVISADO) {
                memA.aprender(g.getNombre(), g.getMarca(), g.getModelo(), g.getLista(), g.getCodigo(), g.getNombreOficial(),
                        "11/12/2026 12:00");
            }
        }
        File autoInv = pr.resolve("Inventario_PRUEBA_automatico.xlsx").toFile();
        Proyecto pa = ImportadorInventario.importar(autoInv, ImportadorInventario.detectar(autoInv), true, null);
        comprobar("RF-36", pa.numEquipos() == 25 && pa.getGrupos().size() == 12 && pa.contar(EstadoGrupo.REVISADO) == 0,
                "inventario posterior: 25 equipos en 12 grupos; sin la opción no se cataloga nada solo");
        Grupo bombaA = buscar(pa, "Bomba de infusión");
        Sugerencia sBomba = memA.sugerir(bombaA.getNombre(), bombaA.getMarca(), bombaA.getModelo(), 1).get(0);
        comprobar("RF-36", sBomba.porcentaje() == 100 && Sugerencia.nivel(sBomba.getSimilitud()).equals("Idéntico")
                        && memA.identica(bombaA.getNombre(), bombaA.getMarca(), bombaA.getModelo()) != null,
                "«Bomba de infusión» · BRAUN · «Infusomat Space» = «BOMBA DE INFUSION» · BRAUN · «INFUSOMAT SPACE»: "
                        + "sugerencia al 100 %, «Idéntico»");
        List<Grupo> auto1 = CatalogacionAutomatica.aplicar(pa.getGrupos(), memA, catsP, "15/12/2026 09:00");
        StringBuilder nombresA = new StringBuilder();
        for (Grupo g : auto1) {
            nombresA.append(nombresA.length() == 0 ? "" : ", ").append(g.getNombre());
        }
        comprobar("RF-36", auto1.size() == 6 && CatalogacionAutomatica.equipos(auto1) == 14
                        && auto1.stream().allMatch(g -> g.getEstado() == EstadoGrupo.REVISADO && g.esAutomatico()
                        && g.getOrigen().equals(CatalogacionAutomatica.ORIGEN)),
                "al importarlo con la opción: 6 grupos (14 equipos) catalogados solos, con origen «Automático…»: " + nombresA);
        comprobar("RF-36", bombaA.getLista() == TipoLista.GMDN && bombaA.getCodigo().equals("990001")
                        && buscar(pa, "INFUSION, BOMBA DE").getCodigo().equals("990001")
                        && buscar(pa, "Silla").getLista() == TipoLista.MANTENIMIENTO
                        && buscar(pa, "Silla").getNombreOficial().equals("SILLA"),
                "con el mismo código y nombre que el ya catalogado (también con las palabras en otro orden)");
        Grupo otroModelo = pa.getGrupos().stream().filter(g -> g.getModelo().equals("INFUSOMAT P")).findFirst().get();
        Grupo otraMarca = pa.getGrupos().stream().filter(g -> g.getMarca().equals("FRESENIUS")).findFirst().get();
        comprobar("RF-36", otroModelo.getEstado() == EstadoGrupo.PENDIENTE && otraMarca.getEstado() == EstadoGrupo.PENDIENTE
                        && memA.sugerir(otraMarca.getNombre(), otraMarca.getMarca(), otraMarca.getModelo(), 1).get(0).porcentaje() == 98
                        && buscar(pa, "ARMARIO METALICO 2 PUERTAS").getEstado() == EstadoGrupo.PENDIENTE
                        && buscar(pa, "EQUIPO RARO SIN IDENTIFICAR").getEstado() == EstadoGrupo.PENDIENTE,
                "no se tocan: otro modelo, otra marca (sugerencia 98 %), un nombre parecido ni lo que era una duda");
        // El ejemplo de la hoja de mejoras: se cataloga a mano uno y el idéntico se cataloga solo
        Grupo cama = pa.getGrupos().stream().filter(g -> g.getMarca().equals("HILL-ROM")).findFirst().get();
        Grupo camaMin = pa.getGrupos().stream().filter(g -> g.getMarca().equals("Hill-Rom")).findFirst().get();
        TerminoOficial t34870 = catsP.porCodigo(TipoLista.GMDN, "34870");
        cama.asignar(TipoLista.GMDN, "34870", t34870.getNombre(), "Buscador", "15/12/2026 09:05");
        memA.aprender(cama.getNombre(), cama.getMarca(), cama.getModelo(), TipoLista.GMDN, "34870", t34870.getNombre(),
                "15/12/2026 09:05");
        List<Grupo> auto2 = CatalogacionAutomatica.aplicar(pa.getGrupos(), memA, catsP, "15/12/2026 09:05");
        comprobar("RF-36", auto2.size() == 1 && auto2.get(0) == camaMin && camaMin.getCodigo().equals("34870")
                        && camaMin.getNombreOficial().equals("Cama hospitalaria eléctrica básica"),
                "ejemplo de la hoja de mejoras: catalogada a mano «CAMA CLINICA… HILL-ROM · P7500A002197» como 34870, la "
                        + "idéntica escrita en minúsculas (otro tipo de activo) se cataloga sola con 34870 · "
                        + "Cama hospitalaria eléctrica básica");
        // Lo que no se decide solo
        Proyecto pb = ImportadorInventario.importar(autoInv, ImportadorInventario.detectar(autoInv), true, null);
        buscar(pb, "DESFIBRILADOR").posponer("15/12/2026 09:10");
        memA.aprender("EQUIPO RARO SIN IDENTIFICAR", "", "", TipoLista.GMDN, "999999", "No está en el catálogo",
                "15/12/2026 09:10");
        memA.aprender("ARMARIO METALICO 2 PUERTAS", "", "", TipoLista.MANTENIMIENTO, "ARMARIO", "ARMARIO", "15/12/2026 09:10");
        memA.aprender("PUERTAS ARMARIO METALICO 2", "", "", TipoLista.MANTENIMIENTO, "SILLA", "SILLA", "15/12/2026 09:11");
        List<Grupo> auto3 = CatalogacionAutomatica.aplicar(pb.getGrupos(), memA, catsP, "15/12/2026 09:12");
        comprobar("RF-36", buscar(pb, "DESFIBRILADOR").getEstado() == EstadoGrupo.POSPUESTO
                        && buscar(pb, "EQUIPO RARO SIN IDENTIFICAR").getEstado() == EstadoGrupo.PENDIENTE
                        && buscar(pb, "ARMARIO METALICO 2 PUERTAS").getEstado() == EstadoGrupo.PENDIENTE
                        && auto3.size() == 7,
                "se respetan las dudas; no se asigna un término que no está en el catálogo ni si los idénticos se "
                        + "catalogaron de dos formas distintas (7 grupos solos)");
        Proyecto pm = ImportadorInventario.importar(autoInv, ImportadorInventario.detectar(autoInv), false, null);
        Grupo bombasMezcla = pm.getGrupos().stream().filter(g -> g.variasMarcas()).findFirst().orElse(null);
        CatalogacionAutomatica.aplicar(pm.getGrupos(), memA, catsP, "15/12/2026 09:13");
        comprobar("RF-36", bombasMezcla != null && bombasMezcla.getEstado() == EstadoGrupo.PENDIENTE,
                "sin «Separar por marca y modelo», un grupo que mezcla marcas («" + (bombasMezcla == null ? "?"
                        : bombasMezcla.getNombre()) + "», BRAUN y FRESENIUS) no se cataloga solo");
        // Se guarda y se exporta como lo demás
        Almacen alA = new Almacen(trabajo.resolve("datosA"));
        alA.prepararCarpetas();
        pa.setNombre("automatico");
        pa.setFicheroOrigen(autoInv.getName());
        pa.setCreado("15/12/2026 09:00");
        pa.setCarpeta(alA.nuevaCarpetaProyecto("automatico"));
        alA.guardarInventario(pa);
        alA.guardarEstado(pa);
        Proyecto paR = alA.abrir(pa.getCarpeta(), null);
        Grupo camaR = paR.getGrupos().stream().filter(g -> g.getMarca().equals("Hill-Rom")).findFirst().get();
        comprobar("RF-36", paR.contar(EstadoGrupo.REVISADO) == 8 && camaR.esAutomatico()
                        && paR.getGrupos().stream().filter(Grupo::esAutomatico).count() == 7,
                "tras reabrir el proyecto siguen catalogados y se distinguen los automáticos (7) del catalogado a mano");
        Exportador.Opciones oa = new Exportador.Opciones();
        oa.carpeta = trabajo.resolve("resultado_automatico").toFile();
        oa.carpeta.mkdirs();
        oa.prefijo = "Automatico";
        oa.centros = new java.util.HashSet<>(Collections.singletonList("GAIPU"));
        List<Exportador.Fichero> fa = Exportador.exportar(paR, oa, null);
        Hoja ha = LectorTablas.leerHoja(fa.get(0).getFichero(), 0, 50);
        boolean camaExportada = false;
        for (int i = 1; i < ha.numFilas(); i++) {
            if (ha.celda(i, 2).equals("34870") && ha.celda(i, 3).equals("Cama hospitalaria eléctrica básica")) {
                camaExportada = true;
            }
        }
        comprobar("RF-36", fa.size() == 1 && camaExportada, "el resultado de GAIPU lleva la cama catalogada sola con "
                + "34870 · Cama hospitalaria eléctrica básica");
        memA.olvidarIdenticos(cama.getNombre(), cama.getMarca(), cama.getModelo());
        comprobar("RF-36", memA.identica(camaMin.getNombre(), camaMin.getMarca(), camaMin.getModelo()) == null,
                "al devolver uno a pendiente (con la opción activada) se olvidan también sus idénticos");

        // ------------------------------------------------------------------ resultado anterior
        titulo("Importar un resultado anterior");
        Proyecto pr2 = ImportadorInventario.importar(casos, ImportadorInventario.detectar(casos), true, null);
        ImportadorResultados.Resumen ra = ImportadorResultados.aplicar(pr.resolve("Resultado_anterior_PRUEBA.xlsx").toFile(),
                pr2, catsP, new Memoria(trabajo.resolve("datosR")), "Anterior", "11/12/2026", null);
        comprobar("RF-25", ra.filas == 13 && ra.sinEquipo == 1 && ra.gruposRevisados == 2 && ra.gruposEnConflicto == 1,
                "13 filas · 1 equipo que no está · 2 grupos catalogados · 1 en conflicto (se deja sin tocar)");

        // ------------------------------------------------------------------ inventario recibido, catalogado de principio a fin
        File catGrande = ej.resolve("grandes").resolve("CATALOGO_GRANDE_GMDN_30000.xlsx").toFile();
        if (catGrande.isFile()) {
            titulo("Inventario recibido catalogado con el catálogo de prueba de 30.000 términos (manual, capítulo 6)");
            ImportadorCatalogos.Resultado rg = ImportadorCatalogos.leer(catGrande);
            Path datosG = trabajo.resolve("datosG");
            Almacen alG = new Almacen(datosG);
            alG.prepararCarpetas();
            Catalogos catsG = new Catalogos(alG.carpetaCatalogos());
            catsG.reemplazar(TipoLista.GMDN, rg.gmdn, catGrande.getName(), rg.hojaGmdn);
            catsG.reemplazar(TipoLista.MANTENIMIENTO, rg.mantenimiento, catGrande.getName(), rg.hojaMantenimiento);
            comprobar("RF-23", catsG.total(TipoLista.GMDN) == 29_997 && catsG.total(TipoLista.MANTENIMIENTO) == 641
                            && catsG.porCodigo(TipoLista.GMDN, "10082") != null,
                    "catálogo de prueba: GMDN 29.997 (incluye los 16 reales) · Mantenimiento 641");
            Proyecto pi = ImportadorInventario.importar(invReal, ImportadorInventario.detectar(invReal), true, null);
            StringBuilder orden = new StringBuilder();
            for (Grupo g : pi.getGrupos()) {
                orden.append(orden.length() == 0 ? "" : " · ").append(g.getNombre()).append(" ").append(g.getEquipos());
            }
            comprobar("RF-02", orden.toString().equals("BOMBA DE INFUSION 7 · SISTEMA RADIOLOGIA INTRAORAL 7 · "
                    + "COMPRESOR ODONTOLOGIA 3 · ASPIRADOR DENTAL 1 · BASCULA DIGITAL CON TALLIMETRO 1"), "cola: " + orden);
            // Lo que se escribe en el buscador y el término elegido, como en el manual
            String[][] pasos = {
                    {"BOMBA DE INFUSION", "BOMBA DE INFUSION", "900010"},
                    {"SISTEMA RADIOLOGIA INTRAORAL", "rayos intraoral", "901128"},
                    {"COMPRESOR ODONTOLOGIA", "compresor dental", "901100"},
                    {"ASPIRADOR DENTAL", "aspirador odontologia", "915459"},
                    {"BASCULA DIGITAL CON TALLIMETRO", "bascula pacientes", "901681"}};
            boolean todosEncontrados = true;
            StringBuilder donde = new StringBuilder();
            for (String[] x : pasos) {
                Grupo g = buscar(pi, x[0]);
                List<Catalogos.Resultado> rs = catsG.buscar(x[1], TipoLista.GMDN, false, 10).getResultados();
                int pos = -1;
                for (int i = 0; i < rs.size(); i++) {
                    if (rs.get(i).getTermino().getCodigo().equals(x[2])) {
                        pos = i + 1;
                        break;
                    }
                }
                if (g == null || pos < 1 || pos > 2) {
                    todosEncontrados = false;
                    continue;
                }
                donde.append(donde.length() == 0 ? "" : ", ").append("«").append(x[1].toLowerCase()).append("» → ")
                        .append(x[2]).append(pos == 1 ? "" : " (2.º)");
                TerminoOficial t = catsG.porCodigo(TipoLista.GMDN, x[2]);
                g.asignar(TipoLista.GMDN, t.getCodigo(), t.getNombre(), "Buscador", "10/12/2026 10:00");
            }
            comprobar("RF-05", todosEncontrados, "el buscador da el término en 1.ª o 2.ª posición: " + donde);
            comprobar("RF-04", pi.contar(EstadoGrupo.REVISADO) == 5 && pi.contar(EstadoGrupo.PENDIENTE) == 0,
                    "los 5 grupos quedan revisados (19 equipos catalogados)");
            Exportador.Opciones oi = new Exportador.Opciones();
            oi.carpeta = trabajo.resolve("resultado_recibido").toFile();
            oi.carpeta.mkdirs();
            oi.prefijo = "Catalogacion_Datos_inventario";
            List<Exportador.Fichero> fi = Exportador.exportar(pi, oi, null);
            Hoja hi = fi.size() == 1 ? LectorTablas.leerHoja(fi.get(0).getFichero(), 0, 100) : null;
            comprobar("RF-16", fi.size() == 1 && fi.get(0).getCentro().equals("GAIVI") && fi.get(0).getFilas() == 19
                            && fi.get(0).getFichero().getName().equals("Catalogacion_Datos_inventario_GAIVI.xlsx"),
                    "un solo fichero (el inventario es de un centro): …_GAIVI.xlsx con 19 filas");
            List<String> modeloCab = LectorTablas.leerHoja(salidaModelo, 0, 10).getFilas().get(2).subList(0, 4);
            comprobar("RF-17", hi != null && hi.getFilas().get(0).subList(0, 4).equals(modeloCab),
                    "cabecera igual que «Datos_que_el_programa_debe_devolver.xlsx»");
            Map<String, String> esperado = new HashMap<>();
            for (String id : new String[]{"138018", "138023", "138024", "138025", "138026", "138027", "138028"}) {
                esperado.put(id, "900010\tBomba de infusión de uso general");
            }
            for (String id : new String[]{"273216", "273222", "273223", "273224", "273225", "273242", "273243"}) {
                esperado.put(id, "901128\tSistema de rayos X dental intraoral");
            }
            for (String id : new String[]{"273232", "273234", "273235"}) {
                esperado.put(id, "901100\tCompresor de aire dental");
            }
            esperado.put("273233", "915459\tAspirador quirúrgico para odontología");
            esperado.put("273172", "901681\tBáscula para pacientes");
            int bien = 0;
            boolean bajaFuera = true;
            for (int i = 1; hi != null && i < hi.numFilas(); i++) {
                String id = hi.celda(i, 1);
                if (("GAIVI".equals(hi.celda(i, 0))) && (hi.celda(i, 2) + "\t" + hi.celda(i, 3)).equals(esperado.get(id))) {
                    bien++;
                }
                if (id.equals("273244")) {
                    bajaFuera = false;
                }
            }
            comprobar("RF-17", bien == 19, "las 19 filas llevan el centro, el id y el código y nombre GMDN esperados ("
                    + bien + " de 19)");
            comprobar("RF-15", bajaFuera, "el 273244 (ESPIROMETRO, de baja) no sale, aunque esté en el modelo recibido");
        }

        // ------------------------------------------------------------------ inventario de 600.000 filas
        if (grande) {
            titulo("Inventario de 600.000 filas");
            File g = ej.resolve("EJEMPLO_inventario_demostracion_600000.xlsx").toFile();
            long t0 = System.nanoTime();
            Proyecto pg = ImportadorInventario.importar(g, ImportadorInventario.detectar(g), true, null);
            double seg = (System.nanoTime() - t0) / 1e9;
            comprobar("RNF-01", pg.getFilasLeidas() == 600_000 && pg.getDescartadosBaja() == 120_258
                            && pg.numEquipos() == 479_742 && pg.getGrupos().size() == 732 && pg.centros().size() == 3,
                    String.format("600.000 filas · 120.258 de baja · 479.742 equipos · 732 grupos · 3 centros (%.1f s)", seg));
            comprobar("RF-02", pg.getGrupos().get(0).getNombre().equals("PAPELERA") && pg.getGrupos().get(0).getEquipos() == 13_767,
                    "primer grupo: PAPELERA (13.767)");
        }

        informe.close();
        System.out.println();
        System.out.println(mal == 0 ? "TODO OK (" + ok + " comprobaciones)" : mal + " FALLOS de " + (ok + mal));
        System.exit(mal == 0 ? 0 : 1);
    }

    /** Catalogar lo que falta del paquete de pruebas (salvo el pospuesto), como en la Guía rápida de prueba. */
    static void catalogarResto(Proyecto p, Catalogos c) {
        String[][] a = {{"SILLA", "M", "SILLA"}, {"DESFIBRILADOR", "G", "990003"}, {"AIRE ACONDICIONADO", "M", "AIRE ACONDICIONADO"},
                {"BONBA INFUSION", "G", "990001"}, {"ARMARIO METALICO", "M", "ARMARIO"}};
        for (String[] x : a) {
            Grupo g = buscar(p, x[0]);
            if (g == null || g.getEstado() != EstadoGrupo.PENDIENTE) {
                continue;
            }
            TipoLista l = x[1].equals("G") ? TipoLista.GMDN : TipoLista.MANTENIMIENTO;
            TerminoOficial t = c.porCodigo(l, x[2]);
            g.asignar(l, l == TipoLista.GMDN ? t.getCodigo() : t.getNombre(), t.getNombre(), "Prueba", "11/12/2026 11:00");
        }
    }

    static boolean ordenados(Proyecto p) {
        for (int i = 1; i < p.getGrupos().size(); i++) {
            if (p.getGrupos().get(i).getEquipos() > p.getGrupos().get(i - 1).getEquipos()) {
                return false;
            }
        }
        return true;
    }

    static Grupo buscar(Proyecto p, String nombre) {
        for (Grupo g : p.getGrupos()) {
            if (g.getNombre().equals(nombre)) {
                return g;
            }
        }
        return null;
    }

    static TerminoOficial primero(Catalogos.Busqueda b) {
        return b.getResultados().isEmpty() ? null : b.getResultados().get(0).getTermino();
    }

    static String codigo(TerminoOficial t) {
        return t == null ? "" : t.getCodigo();
    }

    static void borrar(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            List<Path> l = new ArrayList<>();
            s.forEach(l::add);
            l.sort(Comparator.reverseOrder());
            for (Path x : l) {
                Files.delete(x);
            }
        }
    }

}
