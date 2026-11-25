package pe.facturacion.api.sunat.pdf;

import java.util.List;
import java.util.Map;

/**
 * {@code DocumentTypeConfig} de API-PRO: título y secciones de la representación impresa por tipo
 * de comprobante. Se agregan la percepción (40), que API-PRO no imprimía, y la liquidación de
 * compra (04), módulo nuevo.
 */
public final class PdfDocumentTypes {

    public record Config(String titulo, List<String> sections) {
    }

    private static final List<String> VENTA = List.of("header", "document-badge", "emitter", "receiver",
            "items", "totals", "leyenda", "payment-info", "bank-accounts", "qr-code", "footer");
    private static final List<String> NOTA = List.of("header", "document-badge", "emitter", "receiver",
            "note-reference", "items", "totals", "leyenda", "bank-accounts", "qr-code", "footer");
    private static final List<String> GUIA = List.of("header", "document-badge", "emitter", "receiver",
            "dispatch-info", "items", "bank-accounts", "qr-code", "footer");
    private static final List<String> RETPER = List.of("header", "document-badge", "emitter", "receiver",
            "retention-info", "retention-items", "retention-totals", "qr-code", "footer");
    private static final List<String> LIQUIDACION = List.of("header", "document-badge", "emitter", "receiver",
            "settlement-info", "items", "settlement-totals", "leyenda", "qr-code", "footer");

    private static final Map<String, Config> CONFIGS = Map.of(
            "01", new Config("FACTURA ELECTRÓNICA", VENTA),
            "03", new Config("BOLETA DE VENTA ELECTRÓNICA", VENTA),
            "07", new Config("NOTA DE CRÉDITO ELECTRÓNICA", NOTA),
            "08", new Config("NOTA DE DÉBITO ELECTRÓNICA", NOTA),
            "09", new Config("GUÍA DE REMISIÓN ELECTRÓNICA", GUIA),
            "31", new Config("GUÍA DE REMISIÓN TRANSPORTISTA ELECTRÓNICA", GUIA),
            "20", new Config("COMPROBANTE DE RETENCIÓN ELECTRÓNICA", RETPER),
            "40", new Config("COMPROBANTE DE PERCEPCIÓN ELECTRÓNICA", RETPER),
            "04", new Config("LIQUIDACIÓN DE COMPRA ELECTRÓNICA", LIQUIDACION));

    private PdfDocumentTypes() {
    }

    public static Config get(String tipoDocumento) {
        return CONFIGS.getOrDefault(tipoDocumento, CONFIGS.get("01"));
    }
}
