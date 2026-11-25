package pe.facturacion.api.modules.portal.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Las empresas de una persona del portal para {@code GET auth/me}: una sola consulta con las columnas elegidas una
 * por una (nunca secretos, certificados ni credenciales).
 */
@Component
public class PortalTenantQueries {

    private final JdbcTemplate jdbc;

    public PortalTenantQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code [{ id, ruc, businessName, tradeName, plan, active, billingSuspended }]}, de las empresas no eliminadas. */
    public List<Map<String, Object>> tenantsOf(long userId) {
        return jdbc.query("SELECT t.id, t.ruc, t.razon_social, t.nombre_comercial, t.plan, t.is_active, t.suspension_kind "
                + "FROM portal_user_tenants ut JOIN tenants t ON t.id = ut.tenant_id "
                + "WHERE ut.portal_user_id = ? AND t.deleted_at IS NULL ORDER BY lower(t.razon_social), t.id", (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("ruc", rs.getString("ruc"));
                    m.put("businessName", rs.getString("razon_social"));
                    m.put("tradeName", rs.getString("nombre_comercial"));
                    m.put("plan", rs.getString("plan"));
                    m.put("active", rs.getBoolean("is_active"));
                    m.put("billingSuspended", "pago".equals(rs.getString("suspension_kind")));
                    return m;
                }, userId);
    }
}
