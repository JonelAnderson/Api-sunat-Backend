package pe.facturacion.api.modules.admin.document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.retper.RetPerKind;

/**
 * Reglas puras de las acciones ante SUNAT desde el panel (fase D, §11 del diseño): segmentos de la URL y los cuerpos
 * que se arman para los mismos servicios de {@code /api/v1} al anular un comprobante desde su detalle.
 *
 * <p>Anulación (decisión §11.1): factura y notas → comunicación de baja (RA) con {@code fecha_generacion} = fecha de
 * emisión del comprobante (la API exige que coincidan; la referencia mandaba la de hoy); boleta → resumen de anulación
 * (RC) con {@code fecha_resumen} = fecha de emisión de la boleta; retención / percepción → reversión (RR). Plazos y
 * validaciones (7 días, aceptado, sin NC asociada, sin otra baja vigente) son los de {@code /api/v1}.
 */
public final class AdminDocumentRules {

    public static final String SALE_TIPOS = "facturas|boletas|notas-credito|notas-debito";
    public static final String RETPER_TIPOS = "retenciones|percepciones";
    /** Largo máximo del motivo que viaja a SUNAT en una baja, un resumen de anulación o una reversión ({@code /api/v1}). */
    public static final int SUNAT_REASON_MAX = 255;

    private AdminDocumentRules() {
    }

    /** {@code facturas}, {@code boletas}, {@code notas-credito}, {@code notas-debito} → tipo de comprobante (404 si no). */
    public static DocumentKind saleKind(String segment) {
        for (DocumentKind k : DocumentKind.values()) {
            if (k.urlSegment.equals(segment)) {
                return k;
            }
        }
        throw AdminException.status(404, "Recurso no encontrado.");
    }

    /** {@code retenciones} / {@code percepciones} → tipo (404 si no). */
    public static RetPerKind retPerKind(String segment) {
        for (RetPerKind k : RetPerKind.values()) {
            if (k.plural.equals(segment)) {
                return k;
            }
        }
        throw AdminException.status(404, "Recurso no encontrado.");
    }

    /** Cuerpo de {@code POST /api/v1/anulaciones} para un comprobante (factura o nota). */
    public static Map<String, Object> raPayload(DocumentKind kind, String serie, int correlativo, LocalDate fechaEmision, String motivo) {
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("tipo_documento", kind.tipoDocumento);
        det.put("serie", serie);
        det.put("correlativo", String.valueOf(correlativo));
        det.put("motivo", motivo);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fecha_generacion", fechaEmision.toString());
        body.put("detalles", new ArrayList<>(List.of(det)));
        return body;
    }

    /** Cuerpo de {@code POST /api/v1/resumenes} para anular una boleta. */
    public static Map<String, Object> rcPayload(long boletaId, LocalDate fechaEmision, String motivo) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", boletaId);
        item.put("motivo", motivo);
        item.put("tipo_documento", "03");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fecha_resumen", fechaEmision.toString());
        body.put("anular", new ArrayList<>(List.of(item)));
        return body;
    }

    /** Cuerpo de {@code POST /api/v1/reversiones} para una retención o percepción. */
    public static Map<String, Object> rrPayload(RetPerKind kind, String serie, long correlativo, LocalDate fechaEmision, String motivo) {
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("tipo_documento", kind.tipoDocumento);
        det.put("serie", serie);
        det.put("correlativo", String.valueOf(correlativo));
        det.put("motivo", motivo);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fecha_generacion", fechaEmision.toString());
        body.put("detalles", new ArrayList<>(List.of(det)));
        return body;
    }

    /** Un resumen creado desde "emitir" solo informa boletas: la anulación va por {@code boletas/{id}/anular}. */
    public static boolean isSummaryVoid(Map<String, Object> body) {
        Object anular = body.get("anular");
        return anular != null && !(anular instanceof List<?> l && l.isEmpty()) && !(anular instanceof Map<?, ?> m && m.isEmpty())
                && !"".equals(anular);
    }
}
