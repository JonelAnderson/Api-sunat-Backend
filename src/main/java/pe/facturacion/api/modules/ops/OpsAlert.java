package pe.facturacion.api.modules.ops;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;

/**
 * {@code App\Support\Ops\OpsAlert}: alertas de operación. Siempre se registran en el log; si hay
 * {@code OPS_WEBHOOK_URL}, también se publican (carga compatible con Slack "text", Discord
 * "content" y receptores propios), con deduplicación por clave durante
 * {@code OPS_THROTTLE_MINUTES} (en Redis, compartida entre instancias).
 */
@Component
public class OpsAlert {

    public static final String CRITICO = "critical";
    public static final String AVISO = "warning";
    public static final String INFO = "info";
    private static final Logger log = LoggerFactory.getLogger("ops");

    private final StringRedisTemplate redis;
    private final String webhookUrl;
    private final int throttleMinutes;
    private final String label;
    private final int timeoutSeconds;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public OpsAlert(StringRedisTemplate redis, @Value("${app.ops.webhook-url:}") String webhookUrl,
            @Value("${app.ops.throttle-minutes:60}") int throttleMinutes, @Value("${app.ops.label:API SUNAT}") String label,
            @Value("${app.ops.webhook-timeout:5}") int timeoutSeconds) {
        this.redis = redis;
        this.webhookUrl = webhookUrl;
        this.throttleMinutes = throttleMinutes;
        this.label = label;
        this.timeoutSeconds = timeoutSeconds;
    }

    public void critico(String titulo, String mensaje, Map<String, Object> contexto, String dedupeKey) {
        enviar(CRITICO, titulo, mensaje, contexto, dedupeKey);
    }

    public void aviso(String titulo, String mensaje, Map<String, Object> contexto, String dedupeKey) {
        enviar(AVISO, titulo, mensaje, contexto, dedupeKey);
    }

    public void enviar(String nivel, String titulo, String mensaje, Map<String, Object> contexto, String dedupeKey) {
        String linea = "[OPS][" + label + "] " + titulo + (mensaje == null || mensaje.isEmpty() ? "" : " — " + mensaje);
        switch (nivel) {
            case CRITICO -> log.error("{} {}", linea, contexto);
            case AVISO -> log.warn("{} {}", linea, contexto);
            default -> log.info("{} {}", linea, contexto);
        }
        if (dedupeKey != null && !debeEnviar(dedupeKey)) {
            return;
        }
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }
        publicar(nivel, titulo, mensaje, contexto);
    }

    private boolean debeEnviar(String dedupeKey) {
        if (throttleMinutes <= 0) {
            return true;
        }
        Boolean nuevo = redis.opsForValue().setIfAbsent("ops:alert:" + sha1(dedupeKey), "1", Duration.ofMinutes(throttleMinutes));
        return Boolean.TRUE.equals(nuevo);
    }

    private void publicar(String nivel, String titulo, String mensaje, Map<String, Object> contexto) {
        String emoji = switch (nivel) {
            case CRITICO -> "🔴";
            case AVISO -> "🟡";
            default -> "🟢";
        };
        StringBuilder texto = new StringBuilder(emoji + " *" + titulo + "* (" + label + ")");
        if (mensaje != null && !mensaje.isEmpty()) {
            texto.append('\n').append(mensaje);
        }
        contexto.forEach((k, v) -> texto.append("\n• ").append(k).append(": ").append(v));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", texto.toString());
        payload.put("content", texto.toString());
        payload.put("nivel", nivel);
        payload.put("titulo", titulo);
        payload.put("mensaje", mensaje);
        payload.put("contexto", contexto);
        payload.put("app", label);
        payload.put("timestamp", Clock.iso8601Utc(Clock.nowUtc()));
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(PhpJson.encodeUnescaped(payload), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 400) {
                log.warn("[OPS] El webhook de alertas respondió con error status={}", res.statusCode());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[OPS] No se pudo publicar la alerta en el webhook: {}", e.getMessage());
        }
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return s;
        }
    }
}
