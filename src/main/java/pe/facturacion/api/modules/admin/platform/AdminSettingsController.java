package pe.facturacion.api.modules.admin.platform;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.consulta.DocumentLookupService;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.settings.PlatformSettings;

/**
 * Configuración global de emisión ({@code /api/admin/settings}), como {@code Admin/ConfiguracionController} de la
 * referencia, guardada en la tabla {@code settings} ({@link PlatformSettings}):
 * <ul>
 *   <li>emisión ilimitada para todas las empresas (la usa {@link EmissionPolicy}; la variable
 *       {@code EMISION_ILIMITADA_GLOBAL} sigue valiendo como interruptor del despliegue y el panel no la apaga);</li>
 *   <li>las empresas nuevas nacen ilimitadas (alta desde el panel, fase C);</li>
 *   <li>token global de consulta RUC / DNI: <b>solo escritura</b>. Se guarda cifrado; la lectura devuelve solo si está
 *       configurado, de dónde sale y sus últimos 4 caracteres. Nunca va en la respuesta, en la auditoría ni en los logs.</li>
 * </ul>
 * Leer: todos los roles. Cambiar: {@code Admin.Settings.Manage} (solo superadmin: afecta el cobro de todas las
 * empresas y guarda un secreto de la plataforma). Cada cambio se audita con el valor anterior y el nuevo.
 */
@RestController
@RequestMapping("/api/admin/settings")
@Tag(name = "Panel de administración · configuración global")
public class AdminSettingsController {

    /** Token: texto imprimible sin espacios (va en una cabecera HTTP), 8 a 1000 caracteres. */
    static final Pattern TOKEN = Pattern.compile("^[\\x21-\\x7E]{8,1000}$");

    private final PlatformSettings settings;
    private final EmissionPolicy emission;
    private final DocumentLookupService lookup;
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    public AdminSettingsController(PlatformSettings settings, EmissionPolicy emission, DocumentLookupService lookup, JdbcTemplate jdbc,
            AuditLogService audit) {
        this.settings = settings;
        this.emission = emission;
        this.lookup = lookup;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Operation(summary = "Configuración global: emisión ilimitada, empresas nuevas ilimitadas y token de consulta (sin revelarlo)")
    @AdminPermission(AdminPermissions.SETTINGS_VIEW)
    @GetMapping
    public ResponseEntity<Object> show() {
        return AdminResponse.ok(view());
    }

    @Operation(summary = "Cambia la emisión ilimitada global y la de empresas nuevas ({globalUnlimited, newTenantsUnlimited, motivo})")
    @AdminPermission(AdminPermissions.SETTINGS_MANAGE)
    @PutMapping("/emission")
    public ResponseEntity<Object> emission(HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        AdminBody b = AdminBody.of(request);
        Boolean global = b.bool("globalUnlimited");
        Boolean nuevas = b.bool("newTenantsUnlimited");
        String motivo = b.text("motivo");
        boolean globalBefore = settings.emisionIlimitadaGlobal();
        boolean nuevasBefore = settings.nuevasEmpresasIlimitadas();
        AdminValidation v = new AdminValidation();
        if (global == null) {
            v.add("globalUnlimited", "Indica si la emisión ilimitada global queda encendida (true) o apagada (false).");
        }
        if (nuevas == null) {
            v.add("newTenantsUnlimited", "Indica si las empresas nuevas nacen ilimitadas (true) o con su plan (false).");
        }
        if (global != null && global != globalBefore) {
            v.required("motivo", motivo, "El motivo es obligatorio al cambiar la emisión ilimitada global.");
        }
        v.maxLength("motivo", motivo, 400, "El motivo no debe superar los 400 caracteres.");
        v.throwIfAny();
        settings.setBoolean(PlatformSettings.EMISION_ILIMITADA_GLOBAL, global);
        settings.setBoolean(PlatformSettings.NUEVAS_EMPRESAS_ILIMITADAS, nuevas);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("antes", Map.of(PlatformSettings.EMISION_ILIMITADA_GLOBAL, globalBefore, PlatformSettings.NUEVAS_EMPRESAS_ILIMITADAS, nuevasBefore));
        meta.put("despues", Map.of(PlatformSettings.EMISION_ILIMITADA_GLOBAL, global, PlatformSettings.NUEVAS_EMPRESAS_ILIMITADAS, nuevas));
        if (motivo != null) {
            meta.put("motivo", motivo);
        }
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.configuracion.emision", null, null, null,
                "Configuración de emisión: ilimitada global " + onOff(global) + ", empresas nuevas ilimitadas " + onOff(nuevas), meta);
        return AdminResponse.ok(view(), "Configuración de emisión actualizada.");
    }

    @Operation(summary = "Guarda o reemplaza el token global de consulta RUC / DNI ({token}); nunca se devuelve")
    @AdminPermission(AdminPermissions.SETTINGS_MANAGE)
    @PutMapping("/lookup-token")
    public ResponseEntity<Object> setToken(HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        String token = AdminBody.of(request).raw("token");
        if (token != null) {
            token = token.trim();
        }
        AdminValidation v = new AdminValidation();
        if (token == null || token.isEmpty()) {
            v.add("token", "Ingresa el token de consulta.");
        } else if (!TOKEN.matcher(token).matches()) {
            v.add("token", "El token debe tener entre 8 y 1000 caracteres, sin espacios.");
        }
        v.throwIfAny();
        boolean replaced = settings.lookupToken().isPresent();
        settings.setLookupToken(token);
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.configuracion.token_consulta", null, null, null,
                replaced ? "Token global de consulta RUC / DNI reemplazado" : "Token global de consulta RUC / DNI configurado",
                Map.of("accion", replaced ? "reemplazado" : "configurado"));
        return AdminResponse.ok(view(), "Token de consulta guardado.");
    }

    @Operation(summary = "Quita el token global de consulta (vuelve a usarse LOOKUP_API_TOKEN si existe)")
    @AdminPermission(AdminPermissions.SETTINGS_MANAGE)
    @DeleteMapping("/lookup-token")
    public ResponseEntity<Object> clearToken(HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        boolean had = settings.lookupToken().isPresent();
        settings.clearLookupToken();
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.configuracion.token_consulta", null, null, null,
                "Token global de consulta RUC / DNI eliminado", Map.of("accion", had ? "eliminado" : "sin_cambios"));
        return AdminResponse.ok(view(), had ? "Token de consulta eliminado." : "No había un token guardado.");
    }

    // ------------------------------------------------------------------ apoyo

    private Map<String, Object> view() {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean db = settings.emisionIlimitadaGlobal();
        boolean env = emission.globalUnlimitedByEnvironment();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("globalUnlimited", db);
        e.put("globalUnlimitedByEnvironment", env);
        e.put("globalUnlimitedEffective", db || env);
        e.put("newTenantsUnlimited", settings.nuevasEmpresasIlimitadas());
        e.put("updatedAt", AdminFormat.instant(latest(settings.updatedAt(PlatformSettings.EMISION_ILIMITADA_GLOBAL),
                settings.updatedAt(PlatformSettings.NUEVAS_EMPRESAS_ILIMITADAS))));
        data.put("emission", e);

        Optional<String> token = settings.lookupToken();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("configured", token.isPresent() || lookup.environmentTokenConfigured());
        t.put("source", token.isPresent() ? "panel" : lookup.environmentTokenConfigured() ? "environment" : "none");
        t.put("last4", token.map(PlatformSettings::last4).orElse(null));
        t.put("updatedAt", token.isPresent() ? AdminFormat.instant(settings.updatedAt(PlatformSettings.CONSULTA_TOKEN_GLOBAL)) : null);
        data.put("lookupToken", t);

        Map<String, Object> stats = jdbc.queryForMap("SELECT count(*) AS total, count(*) FILTER (WHERE emission_mode = 'unlimited') AS unlimited, "
                + "count(*) FILTER (WHERE emission_mode <> 'unlimited') AS plan FROM tenants WHERE deleted_at IS NULL");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("tenantsTotal", AdminFormat.longOrNull(stats.get("total")));
        s.put("tenantsUnlimited", AdminFormat.longOrNull(stats.get("unlimited")));
        s.put("tenantsPlan", AdminFormat.longOrNull(stats.get("plan")));
        data.put("stats", s);
        return data;
    }

    private static java.time.LocalDateTime latest(java.time.LocalDateTime a, java.time.LocalDateTime b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static String onOff(boolean v) {
        return v ? "encendida" : "apagada";
    }
}
