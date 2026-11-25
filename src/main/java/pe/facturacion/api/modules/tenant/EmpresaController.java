package pe.facturacion.api.modules.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.certificate.CertificateService;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.FileSniffer;
import pe.facturacion.api.shared.validation.PhpValues;
import pe.facturacion.api.shared.validation.Validator;

/** Perfil de la empresa ({@code TenantController} de API-PRO). */
@RestController
@Tag(name = "Empresa")
public class EmpresaController {

    private final TenantService tenants;
    private final CertificateService certificates;
    private final CertificateStore certificateStore;
    private final FileStorage storage;
    private final AuditLogService audit;

    public EmpresaController(TenantService tenants, CertificateService certificates, CertificateStore certificateStore,
            FileStorage storage, AuditLogService audit) {
        this.tenants = tenants;
        this.certificates = certificates;
        this.certificateStore = certificateStore;
        this.storage = storage;
        this.audit = audit;
    }

    @Operation(summary = "Datos de la empresa autenticada")
    @GetMapping({"/api/v1/empresa", "/api/sunat/configuracion"})
    public ResponseEntity<Object> show(HttpServletRequest request) {
        return ApiResponse.success(tenants.resource(ApiContext.requireTenant(request)));
    }

    @Operation(summary = "Actualizar la empresa (JSON o multipart con logo)")
    @PutMapping("/api/v1/empresa")
    public ResponseEntity<Object> update(HttpServletRequest request) {
        return doUpdate(request);
    }

    /** Alias {@code POST /api/sunat/configuracion} → {@code TenantController@update}. */
    @PostMapping("/api/sunat/configuracion")
    public ResponseEntity<Object> updateAlias(HttpServletRequest request) {
        return doUpdate(request);
    }

    private ResponseEntity<Object> doUpdate(HttpServletRequest request) {
        Input input = Input.of(request);
        Updated updated = update(ApiContext.requireTenant(request), input);
        Tenant saved = updated.tenant();
        audit.registrar(request, "empresa.actualizada", saved.getId(), AuditLogService.SUBJECT_TENANT, saved.getId(),
                "Actualizó la configuración de la empresa (API)", Map.of("campos", updated.campos()));
        return ApiResponse.success(tenants.resource(tenants.fresh(saved)), "Empresa actualizada.");
    }

    /** Empresa guardada y campos que cambió {@link #update(Tenant, Input)}. */
    public record Updated(Tenant tenant, List<String> campos) {
    }

    /**
     * Valida y aplica los datos de {@code PUT /api/v1/empresa} sobre la empresa indicada y la guarda (sin auditar ni
     * responder). Lo usa también el panel de administración, que audita su propia acción.
     */
    public Updated update(Tenant tenant, Input input) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("razon_social", "sometimes|string|max:255");
        rules.put("nombre_comercial", "nullable|string|max:255");
        rules.put("direccion", "nullable|string|max:500");
        rules.put("ubigeo", "nullable|string|between:4,6");
        rules.put("departamento", "nullable|string|max:100");
        rules.put("provincia", "nullable|string|max:100");
        rules.put("distrito", "nullable|string|max:100");
        rules.put("sol_user", "nullable|string|max:50");
        rules.put("sol_pass", "nullable|string|max:50");
        rules.put("client_id", "nullable|string|max:100");
        rules.put("client_secret", "nullable|string|max:255");
        rules.put("entorno", "nullable|string|in:beta,production");
        rules.put("url_webhook", "nullable|url|max:500");
        rules.put("logo", "nullable|image|mimes:jpg,jpeg,png|max:2048");
        rules.put("telefonos", "nullable|array|max:5");
        rules.put("telefonos.*", "string|max:20");
        rules.put("emails", "nullable|array|max:5");
        rules.put("emails.*", "email|max:100");
        rules.put("cuentas_bancarias", "nullable|array|max:5");
        rules.put("cuentas_bancarias.*.banco", "required|string|max:50");
        rules.put("cuentas_bancarias.*.tipo_cuenta", "required|string|max:30");
        rules.put("cuentas_bancarias.*.moneda", "required|string|in:PEN,USD");
        rules.put("cuentas_bancarias.*.numero", "required|string|max:30");
        rules.put("cuentas_bancarias.*.cci", "nullable|string|max:25");
        rules.put("cuentas_bancarias.*.titular", "nullable|string|max:100");
        rules.put("billeteras_digitales", "nullable|array|max:5");
        rules.put("billeteras_digitales.*.tipo", "required|string|in:yape,plin,tunki,otro");
        rules.put("billeteras_digitales.*.numero", "required|string|max:20");
        rules.put("billeteras_digitales.*.titular", "nullable|string|max:100");
        rules.put("mensaje_agradecimiento", "nullable|string|max:500");
        rules.put("mensaje_promocional", "nullable|string|max:500");
        rules.put("tax_regime", "nullable|string|in:" + String.join(",", TaxRateService.REGIMENES));
        rules.put("igv_rate_override", "nullable|numeric|between:0,30");
        rules.put("nrus_categoria", "nullable|integer|in:1,2");
        Validator.make(input.all(), rules).validate();

        List<String> campos = new ArrayList<>();

        setIfNotNull(campos, "razon_social", input.string("razon_social"), tenant::setRazonSocial);
        setIfNotNull(campos, "nombre_comercial", input.string("nombre_comercial"), tenant::setNombreComercial);
        setIfNotNull(campos, "direccion", input.string("direccion"), tenant::setDireccion);
        setIfNotNull(campos, "ubigeo", input.string("ubigeo"), tenant::setUbigeo);
        setIfNotNull(campos, "departamento", input.string("departamento"), tenant::setDepartamento);
        setIfNotNull(campos, "provincia", input.string("provincia"), tenant::setProvincia);
        setIfNotNull(campos, "distrito", input.string("distrito"), tenant::setDistrito);
        setIfNotNull(campos, "sol_user", input.string("sol_user"), v -> tenant.setSolUser(EncryptedString.ofPlain(v)));
        setIfNotNull(campos, "sol_pass", input.string("sol_pass"), v -> tenant.setSolPass(EncryptedString.ofPlain(v)));
        setIfNotNull(campos, "client_id", input.string("client_id"), tenant::setClientId);
        setIfNotNull(campos, "client_secret", input.string("client_secret"), v -> tenant.setClientSecret(EncryptedString.ofPlain(v)));
        setIfNotNull(campos, "environment", input.string("entorno"), tenant::setEnvironment);
        setIfNotNull(campos, "webhook_url", input.string("url_webhook"), tenant::setWebhookUrl);
        setIfNotNull(campos, "telefonos", list(input.get("telefonos")), tenant::setTelefonos);
        setIfNotNull(campos, "emails", list(input.get("emails")), tenant::setEmails);
        setIfNotNull(campos, "cuentas_bancarias", list(input.get("cuentas_bancarias")), tenant::setCuentasBancarias);
        setIfNotNull(campos, "billeteras_digitales", list(input.get("billeteras_digitales")), tenant::setBilleterasDigitales);
        setIfNotNull(campos, "mensaje_agradecimiento", input.string("mensaje_agradecimiento"), tenant::setMensajeAgradecimiento);
        setIfNotNull(campos, "mensaje_promocional", input.string("mensaje_promocional"), tenant::setMensajePromocional);
        String taxRegime = input.string("tax_regime");
        setIfNotNull(campos, "tax_regime", taxRegime, tenant::setTaxRegime);

        // igv_rate_override y nrus_categoria aceptan null explícito para "borrar" el valor
        if (input.has("igv_rate_override")) {
            Object v = input.get("igv_rate_override");
            tenant.setIgvRateOverride(v == null ? null : PhpValues.toNumber(v));
            campos.add("igv_rate_override");
        }
        if (input.has("nrus_categoria")) {
            Object v = input.get("nrus_categoria");
            tenant.setNrusCategoria(v == null ? null : Input.intval(v));
            campos.add("nrus_categoria");
        }
        if (taxRegime != null && !TaxRateService.REGIMEN_RESTRINGIDO.equals(taxRegime)) {
            tenant.setNrusCategoria(null);
            if (!campos.contains("nrus_categoria")) {
                campos.add("nrus_categoria");
            }
        }

        MultipartFile logo = input.file("logo");
        if (logo != null) {
            tenant.setLogoPath(storeLogo(tenant, logo));
            campos.add("logo_path");
        }

        return new Updated(tenants.save(tenant), campos);
    }

    @Operation(summary = "Subir logo (multipart: logo)")
    @PostMapping("/api/v1/empresa/logo")
    public ResponseEntity<Object> uploadLogo(HttpServletRequest request) {
        Input input = Input.of(request);
        Validator.make(input.all(), Map.of("logo", "required|image|mimes:jpg,jpeg,png,webp|max:2048")).validate();
        Tenant tenant = ApiContext.requireTenant(request);
        String path = storeLogo(tenant, input.file("logo"));
        tenant.setLogoPath(path);
        tenants.save(tenant);
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("logo_path", path);
        datos.put("logo_url", storage.url(path) + "?v=" + Clock.now().getEpochSecond());
        return ApiResponse.success(datos, "Logo actualizado.");
    }

    @Operation(summary = "Ver credenciales (el api_secret nunca se muestra)")
    @GetMapping("/api/v1/empresa/credenciales")
    public ResponseEntity<Object> credenciales(HttpServletRequest request) {
        Tenant tenant = ApiContext.requireTenant(request);
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("api_key", tenant.getApiKey());
        datos.put("api_secret", "*** oculto — use POST /empresa/credenciales/regenerar para obtener uno nuevo ***");
        return ApiResponse.success(datos, "El api_secret no puede mostrarse. Si lo perdiste, regenera tus credenciales.");
    }

    @Operation(summary = "Regenerar api_key y api_secret")
    @PostMapping("/api/v1/empresa/credenciales/regenerar")
    public ResponseEntity<Object> regenerar(HttpServletRequest request) {
        Tenant tenant = ApiContext.requireTenant(request);
        TenantService.ApiKeyPair pair = tenants.rotateApiCredentials(tenant);
        String key = pair.apiKey();
        String secret = pair.apiSecret();
        audit.registrar(request, "credenciales.regeneradas", tenant.getId(), AuditLogService.SUBJECT_TENANT, tenant.getId(),
                "Regeneró api_key y api_secret (API). Las anteriores quedaron inválidas.", null);
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("api_key", key);
        datos.put("api_secret", secret);
        datos.put("aviso", "Guarda estas credenciales ahora. El api_secret NO se volverá a mostrar.");
        return ApiResponse.success(datos, "Credenciales regeneradas. Las anteriores ya no son válidas.");
    }

    @Operation(summary = "Subir certificado digital (multipart: certificado, contrasena_certificado)")
    @PostMapping("/api/v1/empresa/certificado")
    public ResponseEntity<Object> uploadCertificate(HttpServletRequest request) throws Exception {
        Input input = Input.of(request);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("certificado", "required|file|max:100");
        rules.put("contrasena_certificado", "nullable|string|max:100");
        Validator.make(input.all(), rules).validate();

        Tenant tenant = ApiContext.requireTenant(request);
        MultipartFile file = input.file("certificado");
        String extension = FileSniffer.clientExtension(file).toLowerCase();
        if (!List.of("pfx", "p12", "pem", "cer", "crt").contains(extension)) {
            return ApiResponse.error("Formato no soportado. Use .pfx, .p12, .pem, .cer o .crt", 422);
        }
        if (List.of("pfx", "p12").contains(extension) && !input.filled("contrasena_certificado")) {
            return ApiResponse.error("El campo contrasena_certificado es obligatorio para archivos .pfx/.p12", 422);
        }
        String pem;
        try {
            pem = certificates.convertToPem(file.getBytes(), extension, input.string("contrasena_certificado"));
        } catch (CertificateService.CertificateException e) {
            return ApiResponse.error(e.getMessage(), 422);
        }
        tenant.setCertificatePath(certificateStore.store(tenant, pem));
        tenant.setCertificatePassword(EncryptedString.ofPlain(input.string("contrasena_certificado")));
        tenants.save(tenant);
        audit.registrar(request, "certificado.actualizado", tenant.getId(), AuditLogService.SUBJECT_TENANT, tenant.getId(),
                "Subió/actualizó el certificado digital (API)", Map.of("extension", extension));
        return ApiResponse.success(null, "Certificado actualizado y convertido a PEM.");
    }

    @Operation(summary = "Eliminar la empresa (borrado físico)")
    @DeleteMapping("/api/v1/empresa")
    public ResponseEntity<Object> destroy(HttpServletRequest request) {
        Tenant tenant = ApiContext.requireTenant(request);
        long id = tenant.getId();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ruc", tenant.getRuc());
        audit.registrar(request, "empresa.eliminada", id, AuditLogService.SUBJECT_TENANT, id,
                "Eliminó la empresa (forceDelete, API)", meta);
        tenants.forceDelete(tenant);
        return ApiResponse.success(Map.of("tenant_id", id), "Empresa eliminada.");
    }

    /** {@code storeAs("logos/{ruc}", "logo.{ext}", "public")}. */
    private String storeLogo(Tenant tenant, MultipartFile logo) {
        String path = "logos/" + tenant.getRuc() + "/logo." + FileSniffer.clientExtension(logo);
        try {
            storage.put(Disk.PUBLIC, path, logo.getBytes());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return path;
    }

    private static <T> void setIfNotNull(List<String> campos, String campo, T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
            campos.add(campo);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        if (v instanceof Map<?, ?> m) {
            return new ArrayList<>(((Map<String, Object>) m).values());
        }
        return null;
    }
}
