package pe.facturacion.api.modules.apilog;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;

/**
 * Middleware {@code log.api} de API-PRO: registra cada petición autenticada en {@code api_logs}
 * después de responder. Enmascara secretos del request (lista ampliada de la rama de seguridad,
 * SEC-014) y, a diferencia de API-PRO, también los de la respuesta (p. ej. {@code api_secret}
 * al regenerar credenciales).
 */
@Component
public class ApiLogWriter {

    private static final Logger log = LoggerFactory.getLogger(ApiLogWriter.class);
    private static final Set<String> SENSITIVE = Set.of(
            "contrasena_certificado", "sol_pass", "password", "api_secret",
            "client_secret", "sire_client_secret", "consulta_token", "client_id");
    private static final int MAX_RESPONSE = 4096;

    private final JdbcTemplate jdbc;
    private final TaskExecutor executor;
    private final AppProperties props;

    public ApiLogWriter(JdbcTemplate jdbc, @Qualifier("applicationTaskExecutor") TaskExecutor executor, AppProperties props) {
        this.jdbc = jdbc;
        this.executor = executor;
        this.props = props;
    }

    public void log(HttpServletRequest request, Map<String, Object> input, int status, byte[] responseBytes, String contentType,
            Tenant tenant, int durationMs) {
        Map<String, Object> requestBody = sanitize(input);
        Object responseBody = responseBody(responseBytes, contentType);
        String userAgent = request.getHeader("User-Agent");
        Object[] row = {
                tenant == null ? null : tenant.getId(),
                truncate(ApiContext.laravelPath(request), 255),
                request.getMethod(),
                PhpJson.encodeUnescaped(requestBody),
                responseBody == null ? null : PhpJson.encodeUnescaped(responseBody),
                status,
                truncate(request.getRemoteAddr(), 45),
                userAgent == null ? null : truncate(userAgent, 500),
                durationMs,
                Timestamp.valueOf(Clock.nowUtc())
        };
        executor.execute(() -> {
            try {
                jdbc.update("INSERT INTO api_logs (tenant_id, endpoint, method, request_body, response_body, status_code, "
                        + "ip_address, user_agent, duration_ms, created_at) VALUES ((SELECT id FROM tenants WHERE id = ?), "
                        + "?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)", row);
            } catch (RuntimeException e) {
                log.warn("No se pudo guardar api_log: {}", e.getMessage());
            }
        });
    }

    private static Object responseBody(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0 || contentType == null || !contentType.contains("json")) {
            return null; // json_decode de un PDF/XML/ZIP devuelve null
        }
        Object decoded = PhpJson.decode(new String(bytes, StandardCharsets.UTF_8));
        if (bytes.length <= MAX_RESPONSE) {
            return maskValues(decoded);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<?, ?> m = decoded instanceof Map<?, ?> map ? map : Map.of();
        summary.put("estado", m.get("estado"));
        summary.put("mensaje", m.get("mensaje"));
        summary.put("_truncated", true);
        summary.put("_size", bytes.length);
        return summary;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitize(Map<String, Object> input) {
        return (Map<String, Object>) maskValues(input);
    }

    @SuppressWarnings("unchecked")
    private static Object maskValues(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> {
                if (v instanceof MultipartFile f) {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("_file", true);
                    meta.put("name", f.getOriginalFilename());
                    meta.put("size", f.getSize());
                    meta.put("mime", f.getContentType());
                    out.put(k, meta);
                } else if (SENSITIVE.contains(k) && !(v instanceof Map) && !(v instanceof List) && !isEmpty(v)) {
                    out.put(k, "***");
                } else {
                    out.put(k, maskValues(v));
                }
            });
            return out;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(ApiLogWriter::maskValues).toList();
        }
        return node;
    }

    /** {@code empty()} de PHP. */
    private static boolean isEmpty(Object v) {
        return v == null || Boolean.FALSE.equals(v) || "".equals(v) || "0".equals(v)
                || (v instanceof Number n && n.doubleValue() == 0);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** {@code logs:purge --days=90} (API-PRO lo programa los domingos 03:00). */
    @Scheduled(cron = "0 0 3 * * SUN", zone = "America/Lima")
    public void purgeOld() {
        int days = Math.max(1, props.security().apiLogsRetentionDays());
        int deleted = jdbc.update("DELETE FROM api_logs WHERE created_at < ?", Timestamp.valueOf(Clock.nowUtc().minusDays(days)));
        log.info("api_logs purgados: {}", deleted);
    }
}
