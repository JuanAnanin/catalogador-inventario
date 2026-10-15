import catalogador.logica.Almacen;
import catalogador.logica.Catalogos;
import catalogador.logica.ImportadorCatalogos;
import catalogador.logica.ImportadorInventario;
import catalogador.modelo.Proyecto;
import catalogador.modelo.TipoLista;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Prepara una carpeta de datos del Catalogador (como si el usuario ya hubiera
 * importado los catálogos y un inventario) para las pruebas de la interfaz.
 * Uso: PrepararDatos <home> <catalogos.xlsx> <inventario.xlsx> [clave=valor de ia.properties…]
 */
public class PrepararDatos {
    public static void main(String[] a) throws Exception {
        Path base = Paths.get(a[0], "Catalogador");
        Almacen al = new Almacen(base);
        al.prepararCarpetas();
        File cat = new File(a[1]);
        ImportadorCatalogos.Resultado r = ImportadorCatalogos.leer(cat);
        Catalogos c = new Catalogos(al.carpetaCatalogos());
        c.reemplazar(TipoLista.GMDN, r.gmdn, cat.getName(), r.hojaGmdn);
        c.reemplazar(TipoLista.MANTENIMIENTO, r.mantenimiento, cat.getName(), r.hojaMantenimiento);
        File inv = new File(a[2]);
        Proyecto p = ImportadorInventario.importar(inv, ImportadorInventario.detectar(inv), true, null);
        p.setNombre(inv.getName().replaceFirst("\\.[^.]+$", ""));
        p.setFicheroOrigen(inv.getName());
        p.setCreado("10/12/2026 09:30");
        p.setCarpeta(al.nuevaCarpetaProyecto(p.getNombre()));
        al.guardarInventario(p);
        al.guardarEstado(p);
        Properties cfg = al.leerConfig();
        cfg.setProperty("ultimoProyecto", p.getCarpeta().toString());
        al.guardarConfig(cfg);
        Properties ia = new Properties();
        for (int i = 3; i < a.length; i++) {
            int k = a[i].indexOf('=');
            ia.setProperty(a[i].substring(0, k), a[i].substring(k + 1));
        }
        if (ia.size() > 0) {
            try (OutputStream out = Files.newOutputStream(base.resolve("ia.properties"))) {
                ia.store(out, null);
            }
        }
        System.out.println("Preparado " + base + ": " + p.getGrupos().size() + " grupos, " + p.numEquipos() + " equipos");
    }
}
