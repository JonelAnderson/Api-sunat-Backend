package pe.facturacion.api.modules.admin.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pe.facturacion.api.modules.admin.api.AdminActionAudit;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.panel.AdminTenantConfigController;
import pe.facturacion.api.modules.admin.panel.AdminTenantViews;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.admin.security.AdminRateLimiter;
import pe.facturacion.api.modules.consulta.DocumentLookupService;
import pe.facturacion.api.modules.plan.PanelPlanService;
import pe.facturacion.api.modules.settings.PlatformSettings;
import pe.facturacion.api.modules.tenant.EmpresaController;
import pe.facturacion.api.modules.tenant.TenantService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiCredentials;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.Validator;

/**
 * Fase C del panel (§11.2 del diseño): alta, edición, eliminación (lógica) y logo de una empresa emisora.
 *
 * <ul>
 *   <li><b>Alta</b> ({@code POST tenants}): RUC de 11 dígitos con dígito verificador (hallazgo H13 para este camino), 409
 *       si el RUC ya existe, plan y modo de emisión ({@code newTenantsUnlimited} de la configuración global si no se
 *       indica) y los datos con las mismas reglas de {@code PUT /api/v1/empresa} ({@link EmpresaController#update}).
 *       Genera {@code api_key} / {@code api_secret} como {@code POST /api/v1/registro} y las series por defecto del
 *       bridge. Las credenciales se devuelven <b>una sola vez</b> en esa respuesta ({@code no-store}, sin idempotencia
 *       ni auditoría de su valor).</li>
 *   <li><b>Edición</b> ({@code PUT tenants/{id}}): {@link EmpresaController#update} con la empresa de la ruta; las
 *       credenciales de SUNAT y el logo no se aceptan aquí (tienen sus endpoints).</li>
 *   <li><b>Eliminación</b> ({@code DELETE tenants/{id}}, solo superadmin): borrado lógico ({@code deleted_at}, como el
 *       {@code SoftDeletes} de la tabla), con motivo y el RUC escrito como confirmación. No borra comprobantes ni
 *       archivos (obligación de conservación); la API deja de reconocer sus credenciales al instante.</li>
 *   <li><b>Logo</b> ({@code POST tenants/{id}/logo}): PNG, JPG o WEBP reconocido por su contenido y guardado con esa
 *       extensión (hallazgo H4 para este camino).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/tenants")
@Tag(name = "Panel de administración · alta y edición de empresas")
public class AdminTenantWriteController {

    static final Set<String> PLANS = Set.of("free", "pro", "business");
    /** Claves propias del alta que no viajan a {@link EmpresaController#update}. */
    static final List<String> CREATE_ONLY = List.of("ruc", "plan", "max_documents_month", "emission_mode", "series_por_defecto", "motivo");
    static final String CREDENCIALES_AVISO = "Guarda estas credenciales ahora: el api_secret no se volverá a mostrar. Si se pierde, "
            + "regenéralas (las anteriores dejan de servir).";

    private final AdminTenantViews views;
    private final TenantService tenantService;
    private final EmpresaController empresa;
    private final PlatformSettings settings;
    private final FileStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AdminActionAudit audit;
    private final DocumentLookupService lookup;
    private final AdminRateLimiter adminLimit;
    private final PanelPlanService panelPlans;

    public AdminTenantWriteController(AdminTenantViews views, TenantService tenantService, EmpresaController empresa,
            PlatformSettings settings, FileStorage storage, JdbcTemplate jdbc, TransactionTemplate tx, AdminActionAudit audit,
            DocumentLookupService lookup, AdminRateLimiter adminLimit, PanelPlanService panelPlans) {
        this.views = views;
        this.tenantService = tenantService;
        this.empresa = empresa;
        this.settings = settings;
        this.storage = storage;
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.lookup = lookup;
        this.adminLimit = adminLimit;
        this.panelPlans = panelPlans;
    }

    // ------------------------------------------------------------------ alta

    @Operation(summary = "Alta de empresa (JSON); devuelve api_key y api_secret una sola vez")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PostMapping
    public ResponseEntity<Object> create(HttpServletRequest request) {
        Input input = views.input(request);
        Map<String, Object> body = input.all();
        AdminValidation v = new AdminValidation();
        rejectCredentials(body, v);
        String ruc = input.string("ruc");
        String rucError = AdminTenantRules.rucError(ruc);
        if (rucError != null) {
            v.add("ruc", rucError);
        }
        String plan = input.has("plan") && input.string("plan") != null ? input.string("plan") : "free";
        if (!PLANS.contains(plan)) {
            v.add("plan", "El plan debe ser free, pro o business.");
        }
        // Sin max_documents_month (o null): el cupo del plan; con valor distinto, cupo del panel (§12.2)
        boolean conCupo = body.get("max_documents_month") != null;
        Long max = conCupo ? AdminBody.of(body).integer("max_documents_month") : null;
        if (conCupo && (max == null || max < 0 || max > Integer.MAX_VALUE)) {
            v.add("max_documents_month", "El cupo mensual debe ser un número entero mayor o igual a 0.");
        }
        String mode = input.string("emission_mode");
        if (mode == null) {
            mode = settings.nuevasEmpresasIlimitadas() ? Tenant.EMISSION_UNLIMITED : Tenant.EMISSION_PLAN;
        } else if (!Tenant.EMISSION_PLAN.equals(mode) && !Tenant.EMISSION_UNLIMITED.equals(mode)) {
            v.add("emission_mode", "El modo de emisión debe ser plan o unlimited.");
        }
        v.reason(input.string("motivo"), false);
        v.throwIfAny();
        // Datos obligatorios del alta (los mismos de POST /api/v1/registro); el resto, con las reglas de PUT /api/v1/empresa
        Map<String, Object> required = new LinkedHashMap<>();
        required.put("razon_social", "required|string|max:255");
        required.put("direccion", "required|string|max:500");
        required.put("ubigeo", "required|string|between:4,6");
        Validator.make(body, required).validate();
        Long exists = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE ruc = ?", Long.class, ruc);
        if (exists != null && exists > 0) {
            throw AdminException.status(409, "Ya existe una empresa con el RUC " + ruc + ".");
        }
        boolean defaultSeries = !input.has("series_por_defecto") || input.bool("series_por_defecto");
        Map<String, Object> forV1 = new LinkedHashMap<>(body);
        CREATE_ONLY.forEach(forV1::remove);

        String apiKey = ApiCredentials.newApiKey();
        String apiSecret = ApiCredentials.newApiSecret();
        String emissionMode = mode;
        PanelPlanService.Result[] asignado = new PanelPlanService.Result[1];
        Tenant created;
        try {
            created = tx.execute(st -> {
                Tenant t = new Tenant();
                t.setRuc(ruc);
                t.setRazonSocial(input.string("razon_social"));
                t.setEnvironment(Tenant.ENV_BETA);
                t.setPlan(plan);
                t.setEmissionMode(emissionMode);
                t.setTaxRegime("general");
                t.setActive(true);
                t.setApiKey(apiKey);
                t.setApiSecret(EncryptedString.ofPlain(apiSecret));
                Tenant saved = tenantService.save(t);
                Tenant updated = empresa.update(saved, new Input(forV1)).tenant();
                if (defaultSeries) {
                    tenantService.createDefaultSeries(updated.getId());
                }
                // El plan del alta es el plan efectivo: suscripción del panel y cupo (como asignarPlan de la referencia)
                try {
                    asignado[0] = panelPlans.assign(updated, plan, max, emissionMode);
                } catch (PanelPlanService.PanelPlanException e) {
                    throw new AdminException(200, List.of(e.getMessage()), List.of(AdminResponse.fieldError("plan", e.getMessage())));
                }
                return updated;
            });
        } catch (DataIntegrityViolationException e) {
            throw AdminException.status(409, "Ya existe una empresa con el RUC " + ruc + ".");
        }
        Tenant fresh = tenantService.fresh(created);
        Map<String, Object> empresaData = AdminTenantConfigController.empresaSinSecretos(tenantService.resource(fresh));
        audit.record(request, "admin.empresa.creada", fresh, "Panel: alta de la empresa " + ruc,
                AdminActionAudit.meta("ruc", ruc, "razon_social", fresh.getRazonSocial(), "plan", plan, "max_documents_month",
                        asignado[0].after().maxDocumentsMonth(), "cupo_panel", asignado[0].after().override(),
                        "suscripcion_accion", asignado[0].action().code(), "suscripcion", asignado[0].after().subscription(),
                        "emission_mode", emissionMode, "entorno", fresh.getEnvironment(), "series_por_defecto", defaultSeries,
                        "motivo", input.string("motivo")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", fresh.getId());
        data.put("empresa", empresaData);
        data.put("apiKey", apiKey);
        data.put("apiSecret", apiSecret);
        data.put("aviso", CREDENCIALES_AVISO);
        return oneTime(data, "Empresa creada. Guarda las credenciales: el api_secret no se volverá a mostrar.");
    }

    /** Respuesta con un secreto que se muestra una sola vez: sin caché en el navegador ni en intermediarios. */
    static ResponseEntity<Object> oneTime(Map<String, Object> data, String message) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .body(AdminResponse.body(true, List.of(message), data, 0, List.of()));
    }

    // ------------------------------------------------------------------ edición

    @Operation(summary = "Editar los datos de la empresa (cuerpo de PUT /api/v1/empresa, sin credenciales ni logo; motivo opcional)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Input input = views.input(request);
        Map<String, Object> body = input.all();
        AdminValidation v = new AdminValidation();
        rejectCredentials(body, v);
        if (body.containsKey("ruc") && body.get("ruc") != null && !String.valueOf(body.get("ruc")).equals(tenant.getRuc())) {
            v.add("ruc", "El RUC de una empresa no se puede cambiar.");
        }
        String motivo = input.string("motivo");
        v.reason(motivo, false);
        v.throwIfAny();
        Map<String, Object> before = AdminTenantConfigController.empresaSinSecretos(tenantService.resource(tenant));
        Map<String, Object> forV1 = new LinkedHashMap<>(body);
        forV1.remove("motivo");
        forV1.remove("ruc");
        EmpresaController.Updated updated = empresa.update(tenant, new Input(forV1));
        Map<String, Object> after = AdminTenantConfigController.empresaSinSecretos(tenantService.resource(tenantService.fresh(updated.tenant())));
        audit.record(request, "admin.empresa.actualizada", tenant, "Panel: edición de la empresa " + tenant.getRuc(),
                AdminActionAudit.meta("campos", updated.campos(), "cambios", AdminTenantRules.changes(before, after), "motivo", motivo));
        return AdminResponse.ok(after, "Empresa actualizada.");
    }

    /** Las credenciales de SUNAT y los archivos tienen sus propios endpoints (otro permiso, solo escritura). */
    static void rejectCredentials(Map<String, Object> body, AdminValidation v) {
        for (String k : AdminTenantRules.forbiddenInEdit(body)) {
            v.add(k, switch (k) {
                case "sol_user", "sol_pass" -> "El usuario y la clave SOL se cambian en PUT tenants/{id}/credenciales/sol.";
                case "client_id", "client_secret" -> "El client_id y el client_secret de GRE se cambian en PUT tenants/{id}/credenciales/gre.";
                case "logo" -> "El logo se sube en POST tenants/{id}/logo.";
                default -> "El certificado se sube en POST tenants/{id}/certificado.";
            });
        }
    }

    // ------------------------------------------------------------------ eliminación

    @Operation(summary = "Eliminar la empresa (borrado lógico; solo superadmin; motivo y RUC de confirmación)")
    @AdminPermission(AdminPermissions.TENANTS_DELETE)
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        AdminBody b = AdminBody.of(request);
        String motivo = b.text("motivo");
        String ruc = b.text("ruc");
        AdminValidation v = new AdminValidation();
        v.reason(motivo, true);
        if (ruc == null || !ruc.equals(tenant.getRuc())) {
            v.add("ruc", "Escribe el RUC de la empresa para confirmar la eliminación.");
        }
        v.throwIfAny();
        LocalDateTime now = Clock.nowUtc().withNano(0);
        jdbc.update("UPDATE tenants SET deleted_at = ?, is_active = false, suspension_reason = ?, suspended_at = ?, updated_at = ? "
                + "WHERE id = ? AND deleted_at IS NULL", now, motivo, now, now, id);
        audit.record(request, "admin.empresa.eliminada", tenant, "Panel: eliminación (lógica) de la empresa " + tenant.getRuc() + " — " + motivo,
                AdminActionAudit.meta("ruc", tenant.getRuc(), "razon_social", tenant.getRazonSocial(), "motivo", motivo, "borrado", "logico"));
        return AdminResponse.ok(null, "Empresa eliminada.");
    }

    // ------------------------------------------------------------------ logo

    @Operation(summary = "Subir el logo (multipart: logo; PNG, JPG o WEBP de hasta 2 MB, reconocido por su contenido)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PostMapping("/{id}/logo")
    public ResponseEntity<Object> logo(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        MultipartFile file = Input.of(request).file("logo");
        AdminValidation v = new AdminValidation();
        byte[] bytes = null;
        String formato = null;
        if (file == null) {
            v.add("logo", "Adjunta el logo en el campo logo (multipart/form-data).");
        } else if (file.getSize() > AdminTenantRules.LOGO_MAX_BYTES) {
            v.add("logo", "El logo no debe superar los 2 MB.");
        } else {
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            formato = AdminTenantRules.logoFormat(bytes);
            if (formato == null) {
                v.add("logo", "El logo debe ser una imagen PNG, JPG o WEBP (se reconoce por su contenido, no por el nombre del archivo).");
            }
        }
        v.throwIfAny();
        String dir = "logos/" + tenant.getRuc() + "/";
        String path = dir + "logo." + formato;
        storage.put(Disk.PUBLIC, path, bytes);
        String old = tenant.getLogoPath();
        if (old != null && !old.equals(path) && old.startsWith(dir)) {
            storage.delete(Disk.PUBLIC, old);
        }
        tenant.setLogoPath(path);
        tenantService.save(tenant);
        audit.record(request, "admin.empresa.logo", tenant, "Panel: logo de la empresa " + tenant.getRuc(),
                AdminActionAudit.meta("formato", formato, "bytes", bytes.length));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("logoUrl", storage.url(path) + "?v=" + Clock.now().getEpochSecond());
        data.put("formato", formato);
        return AdminResponse.ok(data, "Logo actualizado.");
    }

    // ------------------------------------------------------------------ autocompletar el alta

    @Operation(summary = "Datos de un RUC en SUNAT para autocompletar el alta (usa el token de consulta global)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @GetMapping("/ruc-lookup")
    public ResponseEntity<Object> rucLookup(HttpServletRequest request) {
        String ruc = request.getParameter("ruc") == null ? null : request.getParameter("ruc").trim();
        String error = AdminTenantRules.rucError(ruc);
        if (error != null) {
            throw new AdminException(200, List.of(error), List.of(AdminResponse.fieldError("ruc", error)));
        }
        Long exists = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE ruc = ?", Long.class, ruc);
        if (exists != null && exists > 0) {
            throw AdminException.status(409, "Ya existe una empresa con el RUC " + ruc + ".");
        }
        if (!adminLimit.allowSunatQuery(AdminPrincipal.require(request).userId())) {
            throw AdminException.status(429, "Alcanzaste el límite de consultas por minuto. Intenta de nuevo en un momento.");
        }
        Map<String, Object> found = lookup.lookup("6", ruc);
        if (found == null) {
            return AdminResponse.fail("No se encontró información del RUC (o el servicio de consulta no respondió). Completa los datos a mano.");
        }
        return AdminResponse.ok(found);
    }
}
