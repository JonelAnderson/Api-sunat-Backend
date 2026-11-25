package pe.facturacion.api.modules.admin.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pe.facturacion.api.modules.admin.api.AdminActionAudit;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.panel.AdminCertificates;
import pe.facturacion.api.modules.admin.panel.AdminTenantViews;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.certificate.CertificateService;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.tenant.TenantService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.validation.FileSniffer;

/**
 * Credenciales de una empresa desde el panel (fase C, §11.3 del diseño). Todas son de <b>solo escritura</b>:
 * {@code GET tenants/{id}/credenciales} devuelve solo indicadores y metadatos (si hay usuario / clave SOL, si hay
 * client_id / client_secret de GRE, el sujeto y la vigencia del certificado), nunca los valores; la auditoría y los
 * logs tampoco los guardan.
 *
 * <ul>
 *   <li>SOL y GRE: se cifran con {@code SecretCipher} (las mismas columnas que escribe {@code PUT /api/v1/empresa}).</li>
 *   <li>Certificado: {@code .pfx} / {@code .p12} con su contraseña, convertido y validado por {@link CertificateService} y
 *       guardado cifrado por {@link CertificateStore} (como {@code POST /api/v1/empresa/certificado}). La contraseña no
 *       se guarda: solo sirve para abrir el archivo (hallazgo H17 para este camino).</li>
 *   <li>Regenerar {@code api_key} / {@code api_secret}: con motivo; el secreto vuelve <b>una sola vez</b> en esa
 *       respuesta ({@code Cache-Control: no-store}), nunca pasa por la idempotencia (hallazgo H10) ni por la auditoría.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · credenciales de la empresa")
public class AdminTenantCredentialsController {

    static final int SOL_MAX = 50;
    static final int CLIENT_ID_MAX = 100;
    static final int CLIENT_SECRET_MAX = 255;
    /** El mismo tope de {@code POST /api/v1/empresa/certificado} (100 KB). */
    static final long CERT_MAX_BYTES = 100L * 1024;

    private final AdminTenantViews views;
    private final TenantService tenantService;
    private final CertificateService certificates;
    private final CertificateStore certificateStore;
    private final AdminCertificates certStatus;
    private final AdminActionAudit audit;

    public AdminTenantCredentialsController(AdminTenantViews views, TenantService tenantService, CertificateService certificates,
            CertificateStore certificateStore, AdminCertificates certStatus, AdminActionAudit audit) {
        this.views = views;
        this.tenantService = tenantService;
        this.certificates = certificates;
        this.certificateStore = certificateStore;
        this.certStatus = certStatus;
        this.audit = audit;
    }

    @Operation(summary = "Estado de las credenciales (solo indicadores y metadatos, nunca valores)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/credenciales")
    public ResponseEntity<Object> show(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        views.auditView(request, tenant, "credenciales");
        return AdminResponse.ok(status(tenant));
    }

    @Operation(summary = "Usuario y clave SOL (solo escritura)")
    @AdminPermission(AdminPermissions.TENANTS_CREDENTIALS)
    @PutMapping("/credenciales/sol")
    public ResponseEntity<Object> sol(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        AdminBody b = AdminBody.of(request);
        String user = b.text("sol_user");
        String pass = b.text("sol_pass");
        String motivo = b.text("motivo");
        AdminValidation v = new AdminValidation();
        v.required("sol_user", user, "El usuario SOL es obligatorio.");
        v.maxLength("sol_user", user, SOL_MAX, "El usuario SOL no debe superar los " + SOL_MAX + " caracteres.");
        v.required("sol_pass", pass, "La clave SOL es obligatoria.");
        v.maxLength("sol_pass", pass, SOL_MAX, "La clave SOL no debe superar los " + SOL_MAX + " caracteres.");
        v.reason(motivo, false);
        v.throwIfAny();
        boolean replaced = present(tenant.getSolPass());
        tenant.setSolUser(EncryptedString.ofPlain(user));
        tenant.setSolPass(EncryptedString.ofPlain(pass));
        Tenant saved = tenantService.save(tenant);
        audit.record(request, "admin.empresa.credenciales_sol", saved, "Panel: usuario y clave SOL de la empresa " + saved.getRuc(),
                AdminActionAudit.meta("accion", replaced ? "reemplazado" : "configurado", "campos", List.of("sol_user", "sol_pass"), "motivo", motivo));
        return AdminResponse.ok(status(saved), "Usuario y clave SOL guardados.");
    }

    @Operation(summary = "client_id y client_secret de la API REST de SUNAT para guías (solo escritura)")
    @AdminPermission(AdminPermissions.TENANTS_CREDENTIALS)
    @PutMapping("/credenciales/gre")
    public ResponseEntity<Object> gre(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        AdminBody b = AdminBody.of(request);
        String clientId = b.text("client_id");
        String clientSecret = b.text("client_secret");
        String motivo = b.text("motivo");
        AdminValidation v = new AdminValidation();
        v.required("client_id", clientId, "El client_id es obligatorio.");
        v.maxLength("client_id", clientId, CLIENT_ID_MAX, "El client_id no debe superar los " + CLIENT_ID_MAX + " caracteres.");
        v.required("client_secret", clientSecret, "El client_secret es obligatorio.");
        v.maxLength("client_secret", clientSecret, CLIENT_SECRET_MAX, "El client_secret no debe superar los " + CLIENT_SECRET_MAX + " caracteres.");
        v.reason(motivo, false);
        v.throwIfAny();
        boolean replaced = present(tenant.getClientSecret());
        tenant.setClientId(clientId);
        tenant.setClientSecret(EncryptedString.ofPlain(clientSecret));
        Tenant saved = tenantService.save(tenant);
        audit.record(request, "admin.empresa.credenciales_gre", saved, "Panel: credenciales GRE de la empresa " + saved.getRuc(),
                AdminActionAudit.meta("accion", replaced ? "reemplazado" : "configurado", "campos", List.of("client_id", "client_secret"),
                        "motivo", motivo));
        return AdminResponse.ok(status(saved), "Credenciales de GRE guardadas.");
    }

    @Operation(summary = "Subir el certificado digital (multipart: certificado .pfx/.p12, contrasena_certificado, motivo?)")
    @AdminPermission(AdminPermissions.TENANTS_CREDENTIALS)
    @PostMapping("/certificado")
    public ResponseEntity<Object> certificado(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Input input = Input.of(request);
        MultipartFile file = input.file("certificado");
        String password = input.string("contrasena_certificado");
        String motivo = input.string("motivo");
        AdminValidation v = new AdminValidation();
        String extension = file == null ? "" : FileSniffer.clientExtension(file).toLowerCase(Locale.ROOT);
        if (file == null) {
            v.add("certificado", "Adjunta el certificado en el campo certificado (multipart/form-data).");
        } else if (!List.of("pfx", "p12").contains(extension)) {
            v.add("certificado", "El certificado debe ser un archivo .pfx o .p12.");
        } else if (file.getSize() > CERT_MAX_BYTES) {
            v.add("certificado", "El certificado no debe superar los 100 KB.");
        }
        v.required("contrasena_certificado", password, "La contraseña del certificado es obligatoria.");
        v.reason(motivo, false);
        v.throwIfAny();
        String pem;
        try {
            pem = certificates.convertToPem(file.getBytes(), extension, password);
        } catch (CertificateService.CertificateException e) {
            new AdminValidation().add("certificado", e.getMessage()).throwIfAny();
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean replaced = tenant.getCertificatePath() != null && !tenant.getCertificatePath().isEmpty();
        tenant.setCertificatePath(certificateStore.store(tenant, pem));
        // La contraseña solo abre el .pfx: el PEM guardado no la necesita (no se guarda)
        tenant.setCertificatePassword(null);
        Tenant saved = tenantService.save(tenant);
        X509Certificate cert = certificates.parse(pem).certificate();
        audit.record(request, "admin.empresa.certificado", saved, "Panel: certificado digital de la empresa " + saved.getRuc(),
                AdminActionAudit.meta("accion", replaced ? "reemplazado" : "configurado", "formato", extension,
                        "vence", AdminFormat.instant(cert.getNotAfter().toInstant()), "motivo", motivo));
        return AdminResponse.ok(status(saved), "Certificado actualizado.");
    }

    @Operation(summary = "Regenerar api_key y api_secret (motivo obligatorio); el secreto se muestra una sola vez")
    @AdminPermission(AdminPermissions.TENANTS_CREDENTIALS_ROTATE)
    @PostMapping("/credenciales/regenerar")
    public ResponseEntity<Object> regenerar(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        String motivo = AdminBody.of(request).text("motivo");
        new AdminValidation().reason(motivo, true).throwIfAny();
        TenantService.ApiKeyPair pair = tenantService.rotateApiCredentials(tenant);
        audit.record(request, "admin.empresa.credenciales_api", tenant, "Panel: regeneró api_key y api_secret de la empresa " + tenant.getRuc()
                + ". Las anteriores quedaron inválidas.", AdminActionAudit.meta("accion", "regeneradas", "motivo", motivo));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiKey", pair.apiKey());
        data.put("apiSecret", pair.apiSecret());
        data.put("aviso", AdminTenantWriteController.CREDENCIALES_AVISO);
        return AdminTenantWriteController.oneTime(data, "Credenciales regeneradas. Las anteriores ya no son válidas.");
    }

    // ------------------------------------------------------------------ apoyo

    /** Indicadores y metadatos de las credenciales (nunca valores). */
    Map<String, Object> status(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasSolUser", present(t.getSolUser()));
        m.put("hasSolPassword", present(t.getSolPass()));
        m.put("hasClientId", t.getClientId() != null && !t.getClientId().isBlank());
        m.put("hasClientSecret", present(t.getClientSecret()));
        m.put("apiKeyLast4", AdminTenantRules.last4(t.getApiKey()));
        m.put("certificate", certificate(t));
        return m;
    }

    private Map<String, Object> certificate(Tenant t) {
        AdminCertificates.Row row = certStatus.row(t, certStatus.defaultDays());
        Map<String, Object> c = new LinkedHashMap<>();
        boolean present = t.getCertificatePath() != null && !t.getCertificatePath().isEmpty();
        c.put("present", present);
        c.put("status", row.status());
        c.put("expiresOn", row.expiresOn() == null ? null : row.expiresOn().toString());
        c.put("daysLeft", row.daysLeft());
        c.put("message", row.message());
        X509Certificate cert = null;
        if (present) {
            try {
                String pem = certificateStore.load(t);
                cert = pem == null ? null : certificates.parse(pem).certificate();
            } catch (RuntimeException e) {
                cert = null;
            }
        }
        c.put("subject", cert == null ? null : cert.getSubjectX500Principal().getName());
        c.put("issuer", cert == null ? null : cert.getIssuerX500Principal().getName());
        c.put("serialNumber", cert == null ? null : cert.getSerialNumber().toString(16).toUpperCase(Locale.ROOT));
        c.put("validFrom", cert == null ? null : AdminFormat.instant(cert.getNotBefore().toInstant()));
        c.put("validTo", cert == null ? null : AdminFormat.instant(cert.getNotAfter().toInstant()));
        return c;
    }

    /** Hay un valor guardado (sin revelarlo; uno ilegible cuenta como guardado). */
    static boolean present(EncryptedString s) {
        if (s == null) {
            return false;
        }
        try {
            String v = s.reveal();
            return v != null && !v.isEmpty();
        } catch (SecretCipher.DecryptException e) {
            return true;
        }
    }
}
