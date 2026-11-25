package pe.facturacion.api.modules.idempotency;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.shared.time.Clock;

/**
 * Tabla {@code idempotency_keys}. A diferencia de API-PRO (claves eternas, sin purga), las
 * claves caducan a los {@code IDEMPOTENCY_TTL_DAYS} días y se purgan cada hora.
 */
@Repository
public class IdempotencyStore {

    public record Record(String requestHash, Integer responseStatus, String responseBody) {
    }

    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public IdempotencyStore(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public Record find(long tenantId, String key) {
        List<Record> rows = jdbc.query(
                "SELECT request_hash, response_status, response_body FROM idempotency_keys "
                        + "WHERE tenant_id = ? AND idempotency_key = ? AND created_at >= ?",
                (rs, i) -> new Record(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)),
                tenantId, key, cutoff());
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        // Una clave caducada se libera para poder reutilizarse.
        jdbc.update("DELETE FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ? AND created_at < ?", tenantId, key, cutoff());
        return null;
    }

    /** Reserva la clave; false si otra petición concurrente ya la reservó (índice único). */
    public boolean reserve(long tenantId, String key, String hash) {
        try {
            jdbc.update("INSERT INTO idempotency_keys (tenant_id, idempotency_key, request_hash, response_status, response_body, created_at) "
                    + "VALUES (?, ?, ?, NULL, NULL, ?)", tenantId, key, hash, Timestamp.valueOf(Clock.nowUtc()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void complete(long tenantId, String key, int status, String body) {
        jdbc.update("UPDATE idempotency_keys SET response_status = ?, response_body = ? WHERE tenant_id = ? AND idempotency_key = ?",
                status, body, tenantId, key);
    }

    public void release(long tenantId, String key) {
        jdbc.update("DELETE FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ?", tenantId, key);
    }

    @Scheduled(cron = "0 15 * * * *", zone = "America/Lima")
    public void purgeExpired() {
        jdbc.update("DELETE FROM idempotency_keys WHERE created_at < ?", cutoff());
    }

    private Timestamp cutoff() {
        int days = Math.max(1, props.security().idempotencyTtlDays());
        return Timestamp.valueOf(Clock.nowUtc().minusDays(days));
    }
}
