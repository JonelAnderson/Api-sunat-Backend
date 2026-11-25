package pe.facturacion.api.shared.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import pe.facturacion.api.shared.http.Input;

/**
 * {@code LengthAwarePaginator} de Laravel: página tomada de {@code ?page=} (inválida o menor
 * que 1 → 1), {@code per_page} 0 o vacío → 15, última página = max(ceil(total/por_pagina), 1).
 */
public final class Paginator<T> {

    private final List<T> items;
    private final int currentPage;
    private final int perPage;
    private final long total;

    private Paginator(List<T> items, int currentPage, int perPage, long total) {
        this.items = items;
        this.currentPage = currentPage;
        this.perPage = perPage;
        this.total = total;
    }

    /** Página solicitada en {@code ?page=}. */
    public static int page(Input input) {
        Object raw = input.get("page");
        if (raw == null) {
            return 1;
        }
        String s = raw.toString().trim();
        if (!s.matches("\\d+")) {
            return 1;
        }
        int p = Input.intval(s);
        return p < 1 ? 1 : p;
    }

    /** {@code paginate($perPage)}: 0 o negativo inválido → 15 (perPage del modelo). */
    public static int perPage(int requested) {
        return requested > 0 ? requested : 15;
    }

    public static int offset(int page, int perPage) {
        return (page - 1) * perPage;
    }

    public static <T> Paginator<T> of(List<T> items, int page, int perPage, long total) {
        return new Paginator<>(items, page, perPage, total);
    }

    public List<T> items() {
        return items;
    }

    public int currentPage() {
        return currentPage;
    }

    public int perPage() {
        return perPage;
    }

    public long total() {
        return total;
    }

    public int lastPage() {
        return (int) Math.max((long) Math.ceil((double) total / perPage), 1);
    }

    /** Forma {@code {"pagina_actual","ultima_pagina","por_pagina","total"}} de API-PRO. */
    public Map<String, Object> paginacion() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pagina_actual", currentPage);
        m.put("ultima_pagina", lastPage());
        m.put("por_pagina", perPage);
        m.put("total", total);
        return m;
    }

    /** {@code {"datos":[...],"paginacion":{...}}}. */
    public <R> Map<String, Object> toDatosPaginacion(Function<T, R> mapper) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datos", items.stream().map(mapper).toList());
        m.put("paginacion", paginacion());
        return m;
    }
}
