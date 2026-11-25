package pe.facturacion.api.modules.admin.panel;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.ops.OpsTasks;
import pe.facturacion.api.modules.ops.OpsTasks.RevisionCertificado;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.time.Clock;

/**
 * Estado del certificado de cada empresa activa con la misma regla de {@code certificados:verificar}
 * ({@link OpsTasks#revisarCertificado(Tenant, int)}). Leer y descifrar todos los certificados cuesta, así que el
 * resultado se guarda 60 s por instancia (lo usan el tablero y el listado de certificados).
 */
@Component
public class AdminCertificates {

    static final long TTL_NANOS = 60_000_000_000L;

    /** Fila del listado de certificados (sin el certificado ni su contraseña). */
    public record Row(long tenantId, String ruc, String razonSocial, String environment, String status, LocalDate expiresOn,
            Long daysLeft, String message) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenantId", tenantId);
            m.put("ruc", ruc);
            m.put("razonSocial", razonSocial);
            m.put("environment", environment);
            m.put("status", status);
            m.put("expiresOn", expiresOn == null ? null : expiresOn.toString());
            m.put("daysLeft", daysLeft);
            m.put("message", message);
            return m;
        }
    }

    private record Cached(List<Row> rows, long loadedAt) {
    }

    private final OpsTasks ops;
    private final TenantRepository tenants;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    public AdminCertificates(OpsTasks ops, TenantRepository tenants) {
        this.ops = ops;
        this.tenants = tenants;
    }

    public int defaultDays() {
        return ops.certDiasAviso();
    }

    /** Empresas activas ordenadas por días que les quedan (los problemas primero). */
    public List<Row> activeTenants(int diasAviso) {
        long now = System.nanoTime();
        Cached c = cache.get(diasAviso);
        if (c != null && now - c.loadedAt() <= TTL_NANOS) {
            return c.rows();
        }
        List<Row> rows = new ArrayList<>();
        for (Tenant t : tenants.findAll(Sort.by("id"))) {
            if (t.isActive()) {
                rows.add(row(t, diasAviso));
            }
        }
        rows.sort(Comparator.comparingInt((Row r) -> severity(r.status()))
                .thenComparing(r -> r.daysLeft() == null ? Long.MAX_VALUE : r.daysLeft())
                .thenComparingLong(Row::tenantId));
        List<Row> frozen = List.copyOf(rows);
        if (cache.size() > 50) {
            cache.clear();
        }
        cache.put(diasAviso, new Cached(frozen, now));
        return frozen;
    }

    /** Certificado de una empresa (detalle), sin caché. */
    public Row row(Tenant t, int diasAviso) {
        RevisionCertificado r = ops.revisarCertificado(t, diasAviso);
        Long daysLeft = r.vence() == null ? null : ChronoUnit.DAYS.between(Clock.nowUtc().toLocalDate(), r.vence());
        return new Row(t.getId(), t.getRuc(), t.getRazonSocial(), t.getEnvironment(), r.estado(), r.vence(), daysLeft, r.mensaje());
    }

    private static int severity(String status) {
        return switch (status) {
            case RevisionCertificado.ROTO -> 0;
            case RevisionCertificado.VENCIDO -> 1;
            case RevisionCertificado.POR_VENCER -> 2;
            case RevisionCertificado.SIN_CERTIFICADO -> 3;
            default -> 4;
        };
    }
}
