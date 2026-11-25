package pe.facturacion.api.modules.retper;

/**
 * Retención (20) y percepción (40): mismas operaciones con distintos nombres de tabla, columnas
 * y textos. En la retención el tercero es el proveedor y los montos son retenido/pagado; en la
 * percepción, el cliente y percibido/cobrado.
 */
public enum RetPerKind {

    RETENCION(false, "20", "retentions", "retention_items", "retention_id", "proveedor", "pagos", "fecha_retencion",
            "imp_retenido", "imp_pagado", "imp_retenido", "imp_pagar", "retenciones", "Retención", "retención", "retention", 'R'),
    PERCEPCION(true, "40", "perceptions", "perception_items", "perception_id", "cliente", "cobros", "fecha_percepcion",
            "imp_percibido", "imp_cobrado", "imp_percibido", "imp_cobrar", "percepciones", "Percepción", "percepción", "perception", 'P');

    public final boolean perception;
    public final String tipoDocumento;
    public final String table;
    public final String itemsTable;
    public final String foreignKey;
    /** Prefijo de las columnas del tercero y clave del objeto en la petición. */
    public final String tercero;
    /** Clave de pagos/cobros de cada documento relacionado. */
    public final String pagos;
    public final String fechaItem;
    /** Totales de cabecera: retenido/percibido y pagado/cobrado. */
    public final String totalMonto;
    public final String totalNeto;
    /** Montos por documento: retenido/percibido y pagar/cobrar. */
    public final String itemMonto;
    public final String itemNeto;
    /** Ruta y texto plural ("retenciones"). */
    public final String plural;
    public final String titulo;
    public final String nombre;
    /** Prefijo de eventos de webhook y carpeta de archivos ("retention" → retentions/). */
    public final String evento;
    public final char prefijoSerie;

    RetPerKind(boolean perception, String tipoDocumento, String table, String itemsTable, String foreignKey, String tercero,
            String pagos, String fechaItem, String totalMonto, String totalNeto, String itemMonto, String itemNeto, String plural,
            String titulo, String nombre, String evento, char prefijoSerie) {
        this.perception = perception;
        this.tipoDocumento = tipoDocumento;
        this.table = table;
        this.itemsTable = itemsTable;
        this.foreignKey = foreignKey;
        this.tercero = tercero;
        this.pagos = pagos;
        this.fechaItem = fechaItem;
        this.totalMonto = totalMonto;
        this.totalNeto = totalNeto;
        this.itemMonto = itemMonto;
        this.itemNeto = itemNeto;
        this.plural = plural;
        this.titulo = titulo;
        this.nombre = nombre;
        this.evento = evento;
        this.prefijoSerie = prefijoSerie;
    }

    public static RetPerKind fromTipo(String tipo) {
        return "40".equals(tipo) ? PERCEPCION : "20".equals(tipo) ? RETENCION : null;
    }

    public static RetPerKind fromTable(String table) {
        for (RetPerKind k : values()) {
            if (k.table.equals(table)) {
                return k;
            }
        }
        return null;
    }
}
