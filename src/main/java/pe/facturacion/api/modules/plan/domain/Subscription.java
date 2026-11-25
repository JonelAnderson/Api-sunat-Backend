package pe.facturacion.api.modules.plan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import pe.facturacion.api.shared.time.Clock;

/** Suscripción de una empresa a un plan (tabla {@code subscriptions}). */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    public static final String STATUS_TRIALING = "trialing";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAST_DUE = "past_due";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_PENDING = "pending";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    private String status = STATUS_TRIALING;
    @Column(name = "billing_cycle")
    private String billingCycle = "monthly";
    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;
    @Column(name = "current_period_start")
    private LocalDateTime currentPeriodStart;
    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
    @Column(name = "payment_gateway")
    private String paymentGateway = "culqi";
    @Column(name = "gateway_customer_id")
    private String gatewayCustomerId;
    @Column(name = "gateway_card_id")
    private String gatewayCardId;
    @Column(name = "card_last_four")
    private String cardLastFour;
    @Column(name = "card_brand")
    private String cardBrand;
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
    public Plan getPlan() { return plan; }
    public void setPlan(Plan plan) { this.plan = plan; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public LocalDateTime getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(LocalDateTime trialEndsAt) { this.trialEndsAt = trialEndsAt; }
    public LocalDateTime getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(LocalDateTime v) { this.currentPeriodStart = v; }
    public LocalDateTime getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(LocalDateTime v) { this.currentPeriodEnd = v; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getPaymentGateway() { return paymentGateway; }
    public void setPaymentGateway(String paymentGateway) { this.paymentGateway = paymentGateway; }
    public String getCardLastFour() { return cardLastFour; }
    public String getCardBrand() { return cardBrand; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
