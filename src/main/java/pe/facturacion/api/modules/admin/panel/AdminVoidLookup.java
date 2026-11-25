package pe.facturacion.api.modules.admin.panel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.document.DocumentKind;

/**
 * Constancia de anulación de un comprobante (como {@code HistorialController::descargarAnulacion} de la referencia):
 * la comunicación de baja (RA) que lo incluye en sus {@code detalles} o, si es una boleta, el resumen diario de
 * anulación (RC) que lo lleva en {@code document_ids} (si no hay RC, también se busca un RA). Siempre dentro de la
 * empresa de la ruta y la más reciente. Los archivos se sirven después con {@code SummaryEndpoints} /
 * {@code VoidedEndpoints}, los mismos de {@code /api/v1}.
 */
@Component
public class AdminVoidLookup {

    /** RA / RC encontrado. */
    public record Found(String tipo, long id, String identifier, String sunatStatus, String sunatCode, String sunatDescription,
            String ticket, boolean hasXml, boolean hasCdr, Object createdAt) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", tipo);
            m.put("id", id);
            m.put("identifier", identifier);
            m.put("vista", "RC".equals(tipo) ? "resumenes" : "anulaciones");
            m.put("sunatStatus", sunatStatus);
            m.put("sunatCode", sunatCode);
            m.put("sunatDescription", sunatDescription);
            m.put("ticket", ticket);
            m.put("hasXml", hasXml);
            m.put("hasCdr", hasCdr);
            m.put("createdAt", AdminFormat.instant(createdAt));
            return m;
        }
    }

    private static final String COLUMNS = "id, identifier, sunat_status, sunat_code, sunat_description, ticket, "
            + "xml_path IS NOT NULL AS has_xml, cdr_path IS NOT NULL AS has_cdr, created_at";

    private final JdbcTemplate jdbc;

    public AdminVoidLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Anulación del comprobante; 404 si el comprobante no es de la empresa (o no existe). */
    public Optional<Found> find(DocumentKind kind, long tenantId, long docId) {
        List<Map<String, Object>> doc = jdbc.queryForList("SELECT id, serie, correlativo FROM " + kind.table
                + " WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL", tenantId, docId);
        if (doc.isEmpty()) {
            throw AdminException.status(404, "Recurso no encontrado.");
        }
        String serie = String.valueOf(doc.get(0).get("serie"));
        String correlativo = String.valueOf(doc.get(0).get("correlativo"));
        if (kind == DocumentKind.BOLETA) {
            Optional<Found> rc = jdbc.queryForList("SELECT " + COLUMNS + " FROM summaries WHERE tenant_id = ? AND tipo = 'anulacion' "
                    + "AND document_ids @> CAST(? AS jsonb) ORDER BY id DESC LIMIT 1", tenantId, "[" + docId + "]")
                    .stream().findFirst().map(r -> found("RC", r));
            if (rc.isPresent()) {
                return rc;
            }
        }
        // Correlativo comparado sin ceros a la izquierda (la baja lo guarda como texto)
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM voided_documents v WHERE tenant_id = ? AND identifier LIKE 'RA-%' "
                + "AND EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(v.detalles) = 'array' THEN v.detalles "
                + "ELSE '[]'::jsonb END) d WHERE d->>'tipo_documento' = ? AND upper(d->>'serie') = upper(?) "
                + "AND ltrim(d->>'correlativo', '0') = ltrim(?, '0')) ORDER BY id DESC LIMIT 1",
                tenantId, kind.tipoDocumento, serie, correlativo).stream().findFirst().map(r -> found("RA", r));
    }

    private static Found found(String tipo, Map<String, Object> r) {
        return new Found(tipo, AdminFormat.longOrNull(r.get("id")), (String) r.get("identifier"), (String) r.get("sunat_status"),
                (String) r.get("sunat_code"), (String) r.get("sunat_description"), (String) r.get("ticket"),
                Boolean.TRUE.equals(r.get("has_xml")), Boolean.TRUE.equals(r.get("has_cdr")), r.get("created_at"));
    }
}
