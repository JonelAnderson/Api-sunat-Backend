package pe.facturacion.api.sunat.pdf;

/**
 * {@code PdfFormatConfig} de API-PRO: A4, A5 y tickets de 80 y 58 mm. Los tickets usan una
 * página del alto de un A4 (841.89 pt), como el papel que configura API-PRO para dompdf; al
 * generar el ticket, {@link PdfRenderer#render} ajusta el alto al contenido (mejora M1).
 */
public enum PdfFormat {

    A4("a4", "a4", "210mm 297mm"),
    A5("a5", "a5", "148mm 210mm"),
    TICKET_80("ticket-80", "ticket_80", "226.77pt 841.89pt"),
    TICKET_58("ticket-58", "ticket_58", "164.41pt 841.89pt");

    public final String value;
    public final String template;
    /** Valor de {@code @page { size: ... }}. */
    public final String pageSize;

    PdfFormat(String value, String template, String pageSize) {
        this.value = value;
        this.template = template;
        this.pageSize = pageSize;
    }

    public boolean isTicket() {
        return this == TICKET_80 || this == TICKET_58;
    }

    /** {@code PdfFormatConfig::from()}: null si no es un formato válido. */
    public static PdfFormat from(String value) {
        for (PdfFormat f : values()) {
            if (f.value.equals(value)) {
                return f;
            }
        }
        return null;
    }
}
