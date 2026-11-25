package pe.facturacion.api.modules.settings;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;

/**
 * Configuración global de la plataforma en la tabla {@code settings} (la misma de API-PRO, valor JSON por clave),
 * equivalente a su {@code SettingsService}. Se lee con una caché corta por instancia ({@link #TTL_NANOS}); un cambio
 * desde el panel la invalida al momento en esta instancia y en las demás tarda como mucho ese tiempo.
 *
 * <ul>
 *   <li>{@link #EMISION_ILIMITADA_GLOBAL}: todas las empresas emiten sin cupo (lo usa {@code EmissionPolicy}, que
 *       además respeta la variable {@code EMISION_ILIMITADA_GLOBAL} como interruptor del despliegue).</li>
 *   <li>{@link #NUEVAS_EMPRESAS_ILIMITADAS}: las empresas creadas desde el panel nacen en modo {@code unlimited}
 *       (como en la referencia, solo el alta del panel lo usa: llega en la fase C).</li>
 *   <li>{@link #CONSULTA_TOKEN_GLOBAL}: token de consulta RUC / DNI, siempre cifrado con {@link SecretCipher}; nunca
 *       se devuelve ni se registra. {@code DocumentLookupService} lo usa antes que {@code LOOKUP_API_TOKEN}.</li>
 * </ul>
 */
@Service
public class PlatformSettings {

    public static final String EMISION_ILIMITADA_GLOBAL = "emision_ilimitada_global";
    public static final String NUEVAS_EMPRESAS_ILIMITADAS = "nuevas_empresas_ilimitadas";
    public static final String CONSULTA_TOKEN_GLOBAL = "consulta_token_global";

    static final List<String> KEYS = List.of(EMISION_ILIMITADA_GLOBAL, NUEVAS_EMPRESAS_ILIMITADAS, CONSULTA_TOKEN_GLOBAL);
    static final long TTL_NANOS = 30_000_000_000L;

    private static final Logger log = LoggerFactory.getLogger(PlatformSettings.class);

    /** Fila leída: valor JSON decodificado y fecha de actualización (UTC). */
    public record Value(Object value, LocalDateTime updatedAt) {
    }

    private record Snapshot(Map<String, Value> values, long loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private volatile Snapshot snapshot;

    public PlatformSettings(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    // ------------------------------------------------------------------ lectura

    public boolean emisionIlimitadaGlobal() {
        return truthy(value(EMISION_ILIMITADA_GLOBAL));
    }

    public boolean nuevasEmpresasIlimitadas() {
        return truthy(value(NUEVAS_EMPRESAS_ILIMITADAS));
    }

    /** Token de consulta RUC / DNI guardado (descifrado), si hay uno legible. Uso interno: nunca devolverlo. */
    public Optional<String> lookupToken() {
        Object v = value(CONSULTA_TOKEN_GLOBAL);
        if (!(v instanceof String s) || s.isBlank()) {
            return Optional.empty();
        }
        try {
            String plain = cipher.decrypt(s);
            return plain == null || plain.isBlank() ? Optional.empty() : Optional.of(plain);
        } catch (SecretCipher.DecryptException e) {
            log.warn("settings.consulta_token.ilegible");
            return Optional.empty();
        }
    }

    /** Fecha de la última actualización de una clave (UTC) o null si no existe. */
    public LocalDateTime updatedAt(String key) {
        Value v = snapshot().values().get(key);
        return v == null ? null : v.updatedAt();
    }

    private Object value(String key) {
        Value v = snapshot().values().get(key);
        return v == null ? null : v.value();
    }

    private Snapshot snapshot() {
        long now = System.nanoTime();
        Snapshot s = snapshot;
        if (s != null && now - s.loadedAt() <= TTL_NANOS) {
            return s;
        }
        Map<String, Value> values = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList("SELECT key, value::text AS value, updated_at FROM settings WHERE key IN (?, ?, ?)",
                    KEYS.toArray())) {
                Object raw = r.get("value");
                Object updated = r.get("updated_at");
                values.put(String.valueOf(r.get("key")), new Value(raw == null ? null : PhpJson.decode(String.valueOf(raw)),
                        updated instanceof Timestamp ts ? ts.toLocalDateTime() : null));
            }
        } catch (RuntimeException e) {
            // Sin la BD tampoco se puede emitir: se sigue con los valores por defecto (y el interruptor del entorno)
            log.warn("settings.lectura.error tipo={}", e.getClass().getSimpleName());
            if (s != null) {
                return s;
            }
        }
        Snapshot fresh = new Snapshot(Map.copyOf(values), now);
        snapshot = fresh;
        return fresh;
    }

    // ------------------------------------------------------------------ escritura (panel)

    public void setBoolean(String key, boolean value) {
        upsert(key, value ? "true" : "false");
    }

    /** Guarda el token cifrado (el texto claro nunca toca la BD ni los logs). */
    public void setLookupToken(String plainToken) {
        upsert(CONSULTA_TOKEN_GLOBAL, PhpJson.encode(cipher.encrypt(plainToken)));
    }

    public void clearLookupToken() {
        jdbc.update("DELETE FROM settings WHERE key = ?", CONSULTA_TOKEN_GLOBAL);
        invalidate();
    }

    private void upsert(String key, String json) {
        LocalDateTime now = Clock.nowUtc().withNano(0);
        jdbc.update("INSERT INTO settings (key, value, created_at, updated_at) VALUES (?, ?::jsonb, ?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at", key, json, now, now);
        invalidate();
    }

    public void invalidate() {
        snapshot = null;
    }

    // ------------------------------------------------------------------ apoyo (puro)

    /** Booleano guardado por API-PRO o por el panel: {@code true}, {@code 1}, {@code "1"} o {@code "true"}. */
    static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() == 1;
        }
        if (v instanceof String s) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            return "1".equals(t) || "true".equals(t);
        }
        return false;
    }

    /** Últimos 4 caracteres de un token para reconocerlo (null si es tan corto que mostrarlos revelaría demasiado). */
    public static String last4(String token) {
        if (token == null || token.length() < 12) {
            return null;
        }
        return token.substring(token.length() - 4);
    }
}
