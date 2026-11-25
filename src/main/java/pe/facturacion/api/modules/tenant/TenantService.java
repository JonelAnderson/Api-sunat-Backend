package pe.facturacion.api.modules.tenant;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.security.ApiCredentials;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.time.Clock;

/** Operaciones sobre la empresa y su representación ({@code TenantResource} de API-PRO). */
@Service
public class TenantService {

    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;
    private final TaxRateService taxRates;
    private final FileStorage storage;

    public TenantService(TenantRepository tenants, JdbcTemplate jdbc, TaxRateService taxRates, FileStorage storage) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.taxRates = taxRates;
        this.storage = storage;
    }

    /** Series que el bridge crea a cada empresa nueva (sin sucursal, correlativo 0). */
    static final String[][] DEFAULT_SERIES = {
        {"01", "F001"}, {"07", "FC01"}, {"08", "FD01"}, {"03", "B001"}, {"07", "BC01"}, {"08", "BD01"}
    };

    /** api_key y api_secret recién generados (el secreto en claro solo existe en esta respuesta). */
    public record ApiKeyPair(String apiKey, String apiSecret) {
    }

    @Transactional
    public Tenant save(Tenant tenant) {
        return tenants.save(tenant);
    }

    /**
     * Nuevas {@code api_key} ({@code Str::random(64)}) y {@code api_secret} ({@code Str::random(64)}); las anteriores dejan
     * de servir de inmediato (la API no cachea la empresa). Lo usan {@code POST /api/v1/empresa/credenciales/regenerar}
     * y el panel de administración.
     */
    @Transactional
    public ApiKeyPair rotateApiCredentials(Tenant tenant) {
        String key = ApiCredentials.newApiKey();
        String secret = ApiCredentials.random(64);
        tenant.setApiKey(key);
        tenant.setApiSecret(EncryptedString.ofPlain(secret));
        tenants.save(tenant);
        return new ApiKeyPair(key, secret);
    }

    /**
     * Series por defecto de una empresa nueva (F001, FC01, FD01, B001, BC01, BD01), sin pisar las que ya existan. Las
     * crea el bridge al aprovisionar y el alta de empresas del panel.
     */
    public void createDefaultSeries(long tenantId) {
        Timestamp now = Timestamp.valueOf(Clock.nowUtc());
        for (String[] s : DEFAULT_SERIES) {
            jdbc.update("INSERT INTO series (tenant_id, tipo_documento, serie, correlativo, is_active, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 0, true, ?, ?) ON CONFLICT (tenant_id, tipo_documento, serie) DO NOTHING",
                    tenantId, s[0], s[1], now, now);
        }
    }

    public Tenant fresh(Tenant tenant) {
        return tenants.findById(tenant.getId()).orElse(tenant);
    }

    /** {@code Tenant::documentsThisMonth()}: facturas + boletas + NC + ND creadas en el mes actual (UTC). */
    public int documentsThisMonth(long tenantId) {
        LocalDateTime start = Clock.nowUtc().withDayOfMonth(1).toLocalDate().atStartOfDay();
        Timestamp from = Timestamp.valueOf(start);
        Timestamp to = Timestamp.valueOf(start.plusMonths(1));
        int total = 0;
        for (String table : List.of("invoices", "boletas", "credit_notes", "debit_notes")) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table
                    + " WHERE tenant_id = ? AND deleted_at IS NULL AND created_at >= ? AND created_at < ?", Integer.class, tenantId, from, to);
            total += n == null ? 0 : n;
        }
        return total;
    }

    /**
     * Borrado físico ({@code forceDelete}). En API-PRO las FK de retenciones y percepciones no
     * tienen cascada y en MySQL se apagaban las FK; aquí se eliminan explícitamente primero.
     */
    @Transactional
    public void forceDelete(Tenant tenant) {
        jdbc.update("DELETE FROM retentions WHERE tenant_id = ?", tenant.getId());
        jdbc.update("DELETE FROM perceptions WHERE tenant_id = ?", tenant.getId());
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenant.getId());
        // Corrección: API-PRO dejaba en disco el certificado (con la clave privada) y el logo. Se
        // borran al confirmar; los XML/CDR/PDF de los comprobantes se conservan (obligación de conservación)
        String ruc = tenant.getRuc();
        if (ruc != null && ruc.matches("\\d{11}")) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storage.deleteDirectory(FileStorage.Disk.PRIVATE, "certificates/" + ruc);
                    storage.deleteDirectory(FileStorage.Disk.PUBLIC, "logos/" + ruc);
                }
            });
        }
    }

    /** {@code TenantResource::toArray()} con el mismo orden de claves. */
    public Map<String, Object> resource(Tenant t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("ruc", t.getRuc());
        m.put("razon_social", t.getRazonSocial());
        m.put("nombre_comercial", t.getNombreComercial());
        m.put("direccion", t.getDireccion());
        m.put("ubigeo", t.getUbigeo());
        m.put("departamento", t.getDepartamento());
        m.put("provincia", t.getProvincia());
        m.put("distrito", t.getDistrito());
        m.put("sol_user", reveal(t.getSolUser()));
        m.put("tiene_sol_pass", !phpEmpty(reveal(t.getSolPass())));
        m.put("client_id", t.getClientId());
        m.put("tiene_client_secret", !phpEmpty(reveal(t.getClientSecret())));
        m.put("webhook_url", t.getWebhookUrl());
        m.put("entorno", t.getEnvironment());
        m.put("plan", t.getPlan());
        m.put("tax_regime", t.getTaxRegime());
        m.put("igv_rate_override", t.getIgvRateOverride() == null ? null : t.getIgvRateOverride().doubleValue());
        m.put("nrus_categoria", t.getNrusCategoria());
        m.put("tasa_igv_vigente", taxRates.currentRateFor(t));
        m.put("max_documentos_mes", t.getMaxDocumentsMonth());
        m.put("documentos_este_mes", documentsThisMonth(t.getId()));
        m.put("activo", t.isActive());
        m.put("logo_url", t.getLogoPath() == null || t.getLogoPath().isEmpty() ? null
                : storage.url(t.getLogoPath()) + "?v=" + epoch(t.getUpdatedAt()));
        m.put("tiene_certificado", t.getCertificatePath() != null && !t.getCertificatePath().isEmpty());
        m.put("telefonos", t.getTelefonos() == null ? List.of() : t.getTelefonos());
        m.put("emails", t.getEmails() == null ? List.of() : t.getEmails());
        m.put("cuentas_bancarias", t.getCuentasBancarias() == null ? List.of() : t.getCuentasBancarias());
        m.put("billeteras_digitales", t.getBilleterasDigitales() == null ? List.of() : t.getBilleterasDigitales());
        m.put("mensaje_agradecimiento", t.getMensajeAgradecimiento());
        m.put("mensaje_promocional", t.getMensajePromocional());
        m.put("tiene_webhook", t.getWebhookUrl() != null && !t.getWebhookUrl().isEmpty());
        m.put("creado_en", Clock.iso8601Utc(t.getCreatedAt()));
        return m;
    }

    private static long epoch(LocalDateTime utc) {
        return utc == null ? Clock.now().getEpochSecond() : utc.toEpochSecond(java.time.ZoneOffset.UTC);
    }

    private static String reveal(EncryptedString s) {
        if (s == null) {
            return null;
        }
        try {
            return s.reveal();
        } catch (SecretCipher.DecryptException e) {
            return null;
        }
    }

    /** {@code empty()} de PHP para cadenas. */
    private static boolean phpEmpty(String s) {
        return s == null || s.isEmpty() || "0".equals(s);
    }
}
