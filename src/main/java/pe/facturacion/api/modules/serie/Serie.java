package pe.facturacion.api.modules.serie;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.shared.time.Clock;

/** Serie de comprobantes (tabla {@code series}); {@code correlativo} = último número usado. */
@Entity
@Table(name = "series")
public class Serie {

    /** {@code Serie::TIPOS}: nombre amigable → código SUNAT (en el orden de API-PRO). */
    public static final Map<String, String> TIPOS = new LinkedHashMap<>();
    /** {@code Serie::PREFIJOS} (catálogo 61). */
    public static final Map<String, List<String>> PREFIJOS = new LinkedHashMap<>();

    static {
        TIPOS.put("factura", "01");
        TIPOS.put("boleta", "03");
        TIPOS.put("nota_credito", "07");
        TIPOS.put("nota_debito", "08");
        TIPOS.put("guia_remision", "09");
        TIPOS.put("guia_transportista", "31");
        TIPOS.put("retencion", "20");
        TIPOS.put("percepcion", "40");
        TIPOS.put("liquidacion_compra", "04");

        PREFIJOS.put("01", List.of("F"));
        PREFIJOS.put("03", List.of("B"));
        PREFIJOS.put("07", List.of("F", "B"));
        PREFIJOS.put("08", List.of("F", "B"));
        PREFIJOS.put("09", List.of("T"));
        PREFIJOS.put("31", List.of("V"));
        PREFIJOS.put("20", List.of("R"));
        PREFIJOS.put("40", List.of("P"));
        PREFIJOS.put("04", List.of("L"));
    }

    /** {@code Serie::TIPOS_NOMBRE}: código → nombre amigable. */
    public static String nombreTipo(String codigo) {
        for (Map.Entry<String, String> e : TIPOS.entrySet()) {
            if (e.getValue().equals(codigo)) {
                return e.getKey();
            }
        }
        return codigo;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "tipo_documento")
    private String tipoDocumento;
    private String serie;
    @Column(name = "sucursal_nombre")
    private String sucursalNombre;
    @Column(name = "sucursal_id")
    private Long sucursalId;
    private int correlativo;
    @Column(name = "is_active")
    private boolean active = true;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Clock.nowUtc();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Clock.nowUtc();
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTipoDocumento() { return tipoDocumento; }
    public void setTipoDocumento(String tipoDocumento) { this.tipoDocumento = tipoDocumento; }
    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }
    public String getSucursalNombre() { return sucursalNombre; }
    public void setSucursalNombre(String sucursalNombre) { this.sucursalNombre = sucursalNombre; }
    public Long getSucursalId() { return sucursalId; }
    public void setSucursalId(Long sucursalId) { this.sucursalId = sucursalId; }
    public int getCorrelativo() { return correlativo; }
    public void setCorrelativo(int correlativo) { this.correlativo = correlativo; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
