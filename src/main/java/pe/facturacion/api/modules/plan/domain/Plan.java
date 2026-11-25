package pe.facturacion.api.modules.plan.domain;

import jakarta.persistence.Column;
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
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import pe.facturacion.api.shared.time.Clock;

/** Plan de suscripción (tabla {@code plans}). Límite {@code -1} = ilimitado. */
@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String slug;
    private String name;
    @Column(name = "price_monthly")
    private BigDecimal priceMonthly = BigDecimal.ZERO;
    @Column(name = "price_yearly")
    private BigDecimal priceYearly = BigDecimal.ZERO;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> limits;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> features;
    @Column(name = "is_unlimited")
    private boolean unlimited;
    @Column(name = "duration_days")
    private Integer durationDays;
    @Column(name = "sort_order")
    private int sortOrder;
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

    /** {@code Plan::getLimit($key, $default)}: valor entero del límite o el default. */
    public int getLimit(String key, int defaultValue) {
        Object v = limits == null ? null : limits.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    /** {@code isUnlimited()}: is_unlimited o documents_month = -1. */
    public boolean isUnlimitedPlan() {
        return unlimited || getLimit("documents_month", 0) == -1;
    }

    public boolean hasFeature(String feature) {
        return features != null && features.contains(feature);
    }

    /** Plan "free" construido en memoria cuando no existe en BD (PlanService::buildFreePlan). */
    public static Plan builtFree() {
        Plan p = new Plan();
        p.slug = "free";
        p.name = "Free";
        Map<String, Object> l = new java.util.LinkedHashMap<>();
        l.put("documents_month", -1);
        l.put("ai_messages_month", 10);
        l.put("team_members", 1);
        l.put("sucursales", 1);
        l.put("productos", 100);
        l.put("catalog_listings", 5);
        l.put("rfqs_month", 1);
        l.put("b2b_requests_month", 3);
        l.put("reviews_month", 1);
        l.put("feed_posts_month", 2);
        p.limits = l;
        p.features = List.of("facturacion", "boletas", "notas", "guias_remision", "inventario_basico",
                "reportes_basicos", "feed_view", "marketplace_browse", "b2b_basic");
        return p;
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPriceMonthly() { return priceMonthly; }
    public void setPriceMonthly(BigDecimal priceMonthly) { this.priceMonthly = priceMonthly; }
    public BigDecimal getPriceYearly() { return priceYearly; }
    public void setPriceYearly(BigDecimal priceYearly) { this.priceYearly = priceYearly; }
    public Map<String, Object> getLimits() { return limits; }
    public void setLimits(Map<String, Object> limits) { this.limits = limits; }
    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features; }
    public boolean isUnlimited() { return unlimited; }
    public void setUnlimited(boolean unlimited) { this.unlimited = unlimited; }
    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
