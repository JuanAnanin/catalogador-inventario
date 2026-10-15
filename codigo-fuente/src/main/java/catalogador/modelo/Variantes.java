package catalogador.modelo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuenta las distintas formas en que aparece escrito un dato dentro de un
 * grupo. Está optimizada para el caso habitual (una sola forma): solo crea un
 * mapa cuando aparece la segunda, lo que ahorra mucha memoria con cientos de
 * miles de equipos.
 */
public final class Variantes {

    private String primera;
    private int vecesPrimera;
    private Map<String, Integer> resto;

    void anadir(String valor) {
        String v = valor == null ? "" : valor;
        if (primera == null) {
            primera = v;
            vecesPrimera = 1;
        } else if (primera.equals(v)) {
            vecesPrimera++;
        } else {
            if (resto == null) {
                resto = new LinkedHashMap<>(4);
            }
            resto.merge(v, 1, Integer::sum);
        }
    }

    /** Número de formas distintas (incluida la vacía). */
    public int distintas() {
        return primera == null ? 0 : 1 + (resto == null ? 0 : resto.size());
    }

    /** Todas las formas con su número de apariciones, de más a menos frecuente. */
    public List<Map.Entry<String, Integer>> ordenadas() {
        List<Map.Entry<String, Integer>> l = new ArrayList<>();
        if (primera == null) {
            return l;
        }
        l.add(new java.util.AbstractMap.SimpleEntry<>(primera, vecesPrimera));
        if (resto != null) {
            l.addAll(resto.entrySet());
        }
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return l;
    }

    /** La forma no vacía más frecuente ("" si todas están vacías). */
    public String masFrecuente() {
        String mejor = "";
        int veces = 0;
        for (Map.Entry<String, Integer> e : ordenadas()) {
            if (!e.getKey().trim().isEmpty() && e.getValue() > veces) {
                mejor = e.getKey().trim();
                veces = e.getValue();
            }
        }
        return mejor;
    }

    /** Formas no vacías, de más a menos frecuente. */
    public List<Map.Entry<String, Integer>> noVacias() {
        List<Map.Entry<String, Integer>> l = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ordenadas()) {
            if (!e.getKey().trim().isEmpty()) {
                l.add(e);
            }
        }
        return l;
    }

    public List<String> claves() {
        if (primera == null) {
            return Collections.emptyList();
        }
        List<String> l = new ArrayList<>();
        l.add(primera);
        if (resto != null) {
            l.addAll(resto.keySet());
        }
        return l;
    }
}
