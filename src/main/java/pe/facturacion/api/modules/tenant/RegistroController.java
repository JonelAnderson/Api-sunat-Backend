package pe.facturacion.api.modules.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pe.facturacion.api.modules.certificate.CertificateService;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiCredentials;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.validation.DatabaseRules;
import pe.facturacion.api.shared.validation.FileSniffer;
import pe.facturacion.api.shared.validation.PhpValues;
import pe.facturacion.api.shared.validation.Validator;

/**
 * {@code POST /api/v1/registro} (público, multipart). Con la corrección SEC-020 de la rama de
 * seguridad: el plan enviado se ignora y la empresa siempre empieza en {@code free}.
 */
@RestController
@Tag(name = "Registro y credenciales")
public class RegistroController {

    private final TenantService tenants;
    private final CertificateService certificates;
    private final CertificateStore certificateStore;
    private final FileStorage storage;
    private final DatabaseRules db;

    public RegistroController(TenantService tenants, CertificateService certificates, CertificateStore certificateStore,
            FileStorage storage, DatabaseRules db) {
        this.tenants = tenants;
        this.certificates = certificates;
        this.certificateStore = certificateStore;
        this.storage = storage;
        this.db = db;
    }

    @Operation(summary = "Registrar una empresa (multipart con certificado); devuelve api_key y api_secret")
    @PostMapping("/api/v1/registro")
    @Transactional
    public ResponseEntity<Object> store(HttpServletRequest request) throws Exception {
        Input input = Input.of(request);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("ruc", "required|string|size:11|unique:tenants,ruc");
        rules.put("razon_social", "required|string|max:255");
        rules.put("nombre_comercial", "nullable|string|max:255");
        rules.put("direccion", "required|string|max:500");
        rules.put("ubigeo", "required|string|between:4,6");
        rules.put("departamento", "nullable|string|max:100");
        rules.put("provincia", "nullable|string|max:100");
        rules.put("distrito", "nullable|string|max:100");
        rules.put("sol_user", "required|string|max:20");
        rules.put("sol_pass", "required|string|max:50");
        rules.put("entorno", "sometimes|string|in:beta,production");
        rules.put("tax_regime", "sometimes|string|in:" + String.join(",", TaxRateService.REGIMENES));
        rules.put("igv_rate_override", "nullable|numeric|between:0,30");
        rules.put("nrus_categoria", "nullable|integer|in:1,2");
        rules.put("client_id", "nullable|string|max:100");
        rules.put("client_secret", "nullable|string|max:255");
        rules.put("certificado", "required|file|max:100");
        rules.put("contrasena_certificado", "nullable|string|max:100");
        rules.put("logo", "nullable|image|mimes:jpg,jpeg,png|max:2048");
        Validator.make(input.all(), rules).database(db).validate();

        MultipartFile cert = input.file("certificado");
        String extension = FileSniffer.clientExtension(cert).toLowerCase();
        if (!List.of("pfx", "p12", "pem", "cer", "crt").contains(extension)) {
            return ApiResponse.error("Formato de certificado no soportado. Use .pfx, .p12, .pem, .cer o .crt", 422);
        }
        if (List.of("pfx", "p12").contains(extension) && !input.filled("contrasena_certificado")) {
            return ApiResponse.error("El campo contrasena_certificado es obligatorio para archivos .pfx/.p12", 422);
        }
        String pem;
        try {
            pem = certificates.convertToPem(cert.getBytes(), extension, input.string("contrasena_certificado"));
        } catch (CertificateService.CertificateException e) {
            return ApiResponse.error(e.getMessage(), 422);
        }

        String apiKey = ApiCredentials.newApiKey();
        String apiSecret = ApiCredentials.newApiSecret();
        String taxRegime = input.string("tax_regime", "general");
        Object nrus = TaxRateService.REGIMEN_RESTRINGIDO.equals(input.string("tax_regime")) ? input.get("nrus_categoria") : null;

        Tenant t = new Tenant();
        t.setRuc(input.string("ruc"));
        t.setRazonSocial(input.string("razon_social"));
        t.setNombreComercial(input.string("nombre_comercial"));
        t.setDireccion(input.string("direccion"));
        t.setUbigeo(input.string("ubigeo"));
        t.setDepartamento(input.string("departamento"));
        t.setProvincia(input.string("provincia"));
        t.setDistrito(input.string("distrito"));
        t.setSolUser(EncryptedString.ofPlain(input.string("sol_user")));
        t.setSolPass(EncryptedString.ofPlain(input.string("sol_pass")));
        t.setClientId(input.string("client_id"));
        t.setClientSecret(EncryptedString.ofPlain(input.string("client_secret")));
        t.setCertificatePassword(EncryptedString.ofPlain(input.string("contrasena_certificado")));
        t.setEnvironment(input.string("entorno", "beta"));
        t.setPlan("free");
        t.setTaxRegime(taxRegime);
        Object igv = input.get("igv_rate_override");
        t.setIgvRateOverride(igv == null ? null : PhpValues.toNumber(igv));
        t.setNrusCategoria(nrus == null ? null : Input.intval(nrus));
        t.setMaxDocumentsMonth(20);
        t.setActive(true);
        t.setApiKey(apiKey);
        t.setApiSecret(EncryptedString.ofPlain(apiSecret));
        tenants.save(t);

        t.setCertificatePath(certificateStore.store(t, pem));
        MultipartFile logo = input.file("logo");
        if (logo != null) {
            // store("logos/{ruc}", "public"): nombre aleatorio de 40 caracteres + extensión deducida
            String path = "logos/" + t.getRuc() + "/" + ApiCredentials.random(40) + "." + FileSniffer.guessExtension(logo);
            storage.put(Disk.PUBLIC, path, logo.getBytes());
            t.setLogoPath(path);
        }
        tenants.save(t);

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("tenant_id", t.getId());
        datos.put("ruc", t.getRuc());
        datos.put("razon_social", t.getRazonSocial());
        datos.put("entorno", t.getEnvironment());
        datos.put("plan", t.getPlan());
        datos.put("tax_regime", t.getTaxRegime());
        // Cast decimal:2 del modelo → texto ("10.50"), igual que API-PRO
        datos.put("igv_rate_override", t.getIgvRateOverride() == null ? null
                : t.getIgvRateOverride().setScale(2, RoundingMode.HALF_UP).toPlainString());
        datos.put("nrus_categoria", nrus);
        datos.put("api_key", apiKey);
        datos.put("api_secret", apiSecret);
        datos.put("importante", "Guarde sus credenciales. El api_secret NO se puede recuperar.");
        return ApiResponse.created(datos);
    }
}
