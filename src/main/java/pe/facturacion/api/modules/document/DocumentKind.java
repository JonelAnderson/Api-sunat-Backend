package pe.facturacion.api.modules.document;

/**
 * Comprobantes de venta con la misma estructura de tablas ({@code invoices}/{@code boletas}).
 * Agrupa lo que en API-PRO cambia entre {@code Invoice} y {@code Boleta}.
 */
public enum DocumentKind {

    FACTURA("01", "invoices", "invoice_items", "invoice_id", "invoice", "facturas", "facturas", "Factura", "factura"),
    BOLETA("03", "boletas", "boleta_items", "boleta_id", "boleta", "boletas", "boletas", "Boleta", "boleta"),
    NOTA_CREDITO("07", "credit_notes", "credit_note_items", "credit_note_id", "credit_note", "notas-credito", "notas_credito",
            "Nota de crédito", "nota de crédito"),
    NOTA_DEBITO("08", "debit_notes", "debit_note_items", "debit_note_id", "debit_note", "notas-debito", "notas_debito",
            "Nota de débito", "nota de débito");

    /** Código SUNAT (catálogo 01). */
    public final String tipoDocumento;
    public final String table;
    public final String itemsTable;
    public final String itemsFk;
    /** {@code payments.payable_type} y clave del job/webhook. */
    public final String payableType;
    /** Segmento de la URL ({@code /api/v1/facturas}). */
    public final String urlSegment;
    /** Carpeta en el almacenamiento ({@code {ruc}/{local}/{fecha}/facturas/xml}). */
    public final String folder;
    public final String label;
    public final String labelLower;

    DocumentKind(String tipoDocumento, String table, String itemsTable, String itemsFk, String payableType,
            String urlSegment, String folder, String label, String labelLower) {
        this.tipoDocumento = tipoDocumento;
        this.table = table;
        this.itemsTable = itemsTable;
        this.itemsFk = itemsFk;
        this.payableType = payableType;
        this.urlSegment = urlSegment;
        this.folder = folder;
        this.label = label;
        this.labelLower = labelLower;
    }

    /** Nota de crédito o débito (tablas y XML propios). */
    public boolean isNote() {
        return this == NOTA_CREDITO || this == NOTA_DEBITO;
    }

    /** Por código SUNAT (01, 03, 07, 08). */
    public static DocumentKind fromTipo(String tipo) {
        for (DocumentKind k : values()) {
            if (k.tipoDocumento.equals(tipo)) {
                return k;
            }
        }
        return null;
    }

    public static DocumentKind fromPayableType(String type) {
        for (DocumentKind k : values()) {
            if (k.payableType.equals(type)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Tipo de comprobante desconocido: " + type);
    }
}
