package pe.facturacion.api.modules.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.crypto.EncryptedStringConverter;
import pe.facturacion.api.shared.time.Clock;

/** Empresa emisora (tabla {@code tenants}). Borrado lógico como SoftDeletes de Laravel. */
@Entity
@Table(name = "tenants")
@SQLRestriction("deleted_at IS NULL")
public class Tenant {

    public static final String ENV_BETA = "beta";
    public static final String ENV_PRODUCTION = "production";
    public static final String EMISSION_PLAN = "plan";
    public static final String EMISSION_UNLIMITED = "unlimited";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ruc;
    @Column(name = "razon_social")
    private String razonSocial;
    @Column(name = "nombre_comercial")
    private String nombreComercial;
    private String direccion;
    private String ubigeo;
    private String departamento;
    private String provincia;
    private String distrito;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Object> telefonos;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Object> emails;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cuentas_bancarias")
    private List<Object> cuentasBancarias;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billeteras_digitales")
    private List<Object> billeterasDigitales;

    @Column(name = "mensaje_agradecimiento")
    private String mensajeAgradecimiento;
    @Column(name = "mensaje_promocional")
    private String mensajePromocional;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "sol_user")
    private EncryptedString solUser;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "sol_pass")
    private EncryptedString solPass;
    @Column(name = "client_id")
    private String clientId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "client_secret")
    private EncryptedString clientSecret;
    @Column(name = "certificate_path")
    private String certificatePath;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "certificate_password")
    private EncryptedString certificatePassword;

    private String environment = ENV_BETA;
    @Column(name = "webhook_url")
    private String webhookUrl;
    @Column(name = "logo_path")
    private String logoPath;
    @Column(name = "favicon_path")
    private String faviconPath;

    @Column(name = "api_key")
    private String apiKey;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "api_secret")
    private EncryptedString apiSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "consulta_token")
    private EncryptedString consultaToken;
    @Column(name = "consulta_proveedor")
    private String consultaProveedor = "json";

    private String plan = "free";
    @Column(name = "emission_mode")
    private String emissionMode = EMISSION_PLAN;
    @Column(name = "tax_regime")
    private String taxRegime = "general";
    @Column(name = "obligado_codigo_producto")
    private boolean obligadoCodigoProducto;
    @Column(name = "igv_rate_override")
    private BigDecimal igvRateOverride;
    @Column(name = "nrus_categoria")
    private Integer nrusCategoria;

    /** Cupo efectivo del mes (columna informativa, -1 = ilimitado): lo mantiene {@code PanelPlanService} / {@code DocumentQuota}. */
    @Column(name = "max_documents_month")
    private int maxDocumentsMonth = 20;
    /** Cupo mensual fijado desde el panel (V9); {@code null} = el {@code documents_month} del plan efectivo. */
    @Column(name = "documents_month_override")
    private Integer documentsMonthOverride;
    @Column(name = "documents_this_month")
    private int documentsThisMonth;
    @Column(name = "ai_messages_this_month")
    private int aiMessagesThisMonth;
    @Column(name = "usage_reset_month")
    private String usageResetMonth;

    @Column(name = "is_active")
    private boolean active = true;
    /** {@code 'pago'} = suspendida por falta de pago desde la consola (sigue activa, pero no emite). Solo lectura aquí. */
    @Column(name = "suspension_kind", insertable = false, updatable = false)
    private String suspensionKind;
    @Column(name = "sire_enabled")
    private boolean sireEnabled;
    @Column(name = "sire_last_period_synced")
    private String sireLastPeriodSynced;
    @Column(name = "sire_last_reconciliation_at")
    private LocalDateTime sireLastReconciliationAt;
    @Column(name = "sire_client_id")
    private String sireClientId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "sire_client_secret")
    private EncryptedString sireClientSecret;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = Clock.nowUtc();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Clock.nowUtc();
    }

    public boolean isBeta() {
        return ENV_BETA.equals(environment);
    }

    // --- getters / setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRuc() { return ruc; }
    public void setRuc(String ruc) { this.ruc = ruc; }
    public String getRazonSocial() { return razonSocial; }
    public void setRazonSocial(String razonSocial) { this.razonSocial = razonSocial; }
    public String getNombreComercial() { return nombreComercial; }
    public void setNombreComercial(String nombreComercial) { this.nombreComercial = nombreComercial; }
    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
    public String getUbigeo() { return ubigeo; }
    public void setUbigeo(String ubigeo) { this.ubigeo = ubigeo; }
    public String getDepartamento() { return departamento; }
    public void setDepartamento(String departamento) { this.departamento = departamento; }
    public String getProvincia() { return provincia; }
    public void setProvincia(String provincia) { this.provincia = provincia; }
    public String getDistrito() { return distrito; }
    public void setDistrito(String distrito) { this.distrito = distrito; }
    public List<Object> getTelefonos() { return telefonos; }
    public void setTelefonos(List<Object> telefonos) { this.telefonos = telefonos; }
    public List<Object> getEmails() { return emails; }
    public void setEmails(List<Object> emails) { this.emails = emails; }
    public List<Object> getCuentasBancarias() { return cuentasBancarias; }
    public void setCuentasBancarias(List<Object> cuentasBancarias) { this.cuentasBancarias = cuentasBancarias; }
    public List<Object> getBilleterasDigitales() { return billeterasDigitales; }
    public void setBilleterasDigitales(List<Object> billeterasDigitales) { this.billeterasDigitales = billeterasDigitales; }
    public String getMensajeAgradecimiento() { return mensajeAgradecimiento; }
    public void setMensajeAgradecimiento(String v) { this.mensajeAgradecimiento = v; }
    public String getMensajePromocional() { return mensajePromocional; }
    public void setMensajePromocional(String v) { this.mensajePromocional = v; }
    public EncryptedString getSolUser() { return solUser; }
    public void setSolUser(EncryptedString solUser) { this.solUser = solUser; }
    public EncryptedString getSolPass() { return solPass; }
    public void setSolPass(EncryptedString solPass) { this.solPass = solPass; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public EncryptedString getClientSecret() { return clientSecret; }
    public void setClientSecret(EncryptedString clientSecret) { this.clientSecret = clientSecret; }
    public String getCertificatePath() { return certificatePath; }
    public void setCertificatePath(String certificatePath) { this.certificatePath = certificatePath; }
    public EncryptedString getCertificatePassword() { return certificatePassword; }
    public void setCertificatePassword(EncryptedString v) { this.certificatePassword = v; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }
    public String getFaviconPath() { return faviconPath; }
    public void setFaviconPath(String faviconPath) { this.faviconPath = faviconPath; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public EncryptedString getApiSecret() { return apiSecret; }
    public void setApiSecret(EncryptedString apiSecret) { this.apiSecret = apiSecret; }
    public EncryptedString getConsultaToken() { return consultaToken; }
    public void setConsultaToken(EncryptedString consultaToken) { this.consultaToken = consultaToken; }
    public String getConsultaProveedor() { return consultaProveedor; }
    public void setConsultaProveedor(String consultaProveedor) { this.consultaProveedor = consultaProveedor; }
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }
    public String getEmissionMode() { return emissionMode; }
    public void setEmissionMode(String emissionMode) { this.emissionMode = emissionMode; }
    public String getTaxRegime() { return taxRegime; }
    public void setTaxRegime(String taxRegime) { this.taxRegime = taxRegime; }
    public boolean isObligadoCodigoProducto() { return obligadoCodigoProducto; }
    public void setObligadoCodigoProducto(boolean v) { this.obligadoCodigoProducto = v; }
    public BigDecimal getIgvRateOverride() { return igvRateOverride; }
    public void setIgvRateOverride(BigDecimal igvRateOverride) { this.igvRateOverride = igvRateOverride; }
    public Integer getNrusCategoria() { return nrusCategoria; }
    public void setNrusCategoria(Integer nrusCategoria) { this.nrusCategoria = nrusCategoria; }
    public int getMaxDocumentsMonth() { return maxDocumentsMonth; }
    public void setMaxDocumentsMonth(int maxDocumentsMonth) { this.maxDocumentsMonth = maxDocumentsMonth; }
    public Integer getDocumentsMonthOverride() { return documentsMonthOverride; }
    public void setDocumentsMonthOverride(Integer v) { this.documentsMonthOverride = v; }
    public int getDocumentsThisMonth() { return documentsThisMonth; }
    public void setDocumentsThisMonth(int documentsThisMonth) { this.documentsThisMonth = documentsThisMonth; }
    public int getAiMessagesThisMonth() { return aiMessagesThisMonth; }
    public void setAiMessagesThisMonth(int aiMessagesThisMonth) { this.aiMessagesThisMonth = aiMessagesThisMonth; }
    public String getUsageResetMonth() { return usageResetMonth; }
    public void setUsageResetMonth(String usageResetMonth) { this.usageResetMonth = usageResetMonth; }
    public String getSuspensionKind() { return suspensionKind; }
    /** Suspendida por falta de pago: consulta y descarga sí, emisión nueva no (402). */
    public boolean isBillingSuspended() { return "pago".equals(suspensionKind); }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isSireEnabled() { return sireEnabled; }
    public void setSireEnabled(boolean sireEnabled) { this.sireEnabled = sireEnabled; }
    public String getSireLastPeriodSynced() { return sireLastPeriodSynced; }
    public void setSireLastPeriodSynced(String v) { this.sireLastPeriodSynced = v; }
    public LocalDateTime getSireLastReconciliationAt() { return sireLastReconciliationAt; }
    public void setSireLastReconciliationAt(LocalDateTime v) { this.sireLastReconciliationAt = v; }
    public String getSireClientId() { return sireClientId; }
    public void setSireClientId(String sireClientId) { this.sireClientId = sireClientId; }
    public EncryptedString getSireClientSecret() { return sireClientSecret; }
    public void setSireClientSecret(EncryptedString v) { this.sireClientSecret = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
