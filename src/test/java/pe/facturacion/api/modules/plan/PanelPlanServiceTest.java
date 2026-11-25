package pe.facturacion.api.modules.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import pe.facturacion.api.modules.plan.domain.Plan;
import pe.facturacion.api.modules.plan.domain.PlanRepository;
import pe.facturacion.api.modules.plan.domain.Subscription;
import pe.facturacion.api.modules.plan.domain.SubscriptionRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.time.Clock;

/** El plan del panel es el plan efectivo (§12.2): sincronización de la suscripción y cupo que se aplica. */
class PanelPlanServiceTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 15, 0, 0);

    PlanRepository plans;
    SubscriptionRepository subscriptions;
    JdbcTemplate jdbc;
    PanelPlanService service;
    Tenant tenant;
    final AtomicLong ids = new AtomicLong(100);
    final List<Subscription> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Clock.useFixed(NOW.toInstant(java.time.ZoneOffset.UTC));
        plans = mock(PlanRepository.class);
        subscriptions = mock(SubscriptionRepository.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PanelPlanService(plans, subscriptions, jdbc);
        tenant = new Tenant();
        tenant.setId(7L);
        tenant.setPlan("free");
        Map<String, Object> cols = new HashMap<>();
        cols.put("plan", "free");
        cols.put("max_documents_month", 20);
        cols.put("documents_month_override", null);
        cols.put("emission_mode", "plan");
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(cols);
        // Repositorio en memoria: save asigna id; las búsquedas filtran por estado
        when(subscriptions.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            if (s.getId() == null) {
                ReflectionTestUtils.setField(s, "id", ids.incrementAndGet());
                rows.add(s);
            }
            return s;
        });
        when(subscriptions.findByTenantIdAndStatusIn(eq(7L), any())).thenAnswer(inv -> {
            java.util.Collection<?> st = inv.getArgument(1);
            return rows.stream().filter(s -> st.contains(s.getStatus())).toList();
        });
        when(subscriptions.findFirstByTenantIdAndStatusInOrderByIdDesc(eq(7L), any())).thenAnswer(inv -> {
            java.util.Collection<?> st = inv.getArgument(1);
            return rows.stream().filter(s -> st.contains(s.getStatus())).max(java.util.Comparator.comparing(Subscription::getId));
        });
        when(plans.findBySlug(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        Clock.useSystem();
    }

    Plan plan(String slug, int documentsMonth, String price, Integer durationDays) {
        Plan p = DocumentQuotaTest.plan(slug, documentsMonth, false);
        p.setPriceMonthly(new BigDecimal(price));
        p.setPriceYearly(new BigDecimal(price));
        p.setDurationDays(durationDays);
        ReflectionTestUtils.setField(p, "id", (long) slug.hashCode());
        when(plans.findBySlug(slug)).thenReturn(Optional.of(p));
        return p;
    }

    Subscription existing(Plan p, String status, String gateway) {
        Subscription s = new Subscription();
        s.setTenantId(7L);
        s.setPlan(p);
        s.setStatus(status);
        s.setPaymentGateway(gateway);
        s.setCurrentPeriodStart(NOW.minusDays(10));
        s.setCurrentPeriodEnd(NOW.plusDays(20));
        ReflectionTestUtils.setField(s, "id", ids.incrementAndGet());
        rows.add(s);
        return s;
    }

    @Test
    void cambiar_de_plan_cancela_la_prueba_y_la_reduccion_y_crea_la_suscripcion_del_panel() {
        Plan pro = plan("pro", 1000, "49", null);
        Plan business = plan("business", -1, "99", null);
        Subscription prueba = existing(pro, Subscription.STATUS_TRIALING, "culqi");
        Subscription reduccion = existing(plan("free", 30, "0", null), Subscription.STATUS_PENDING, "culqi");

        PanelPlanService.Result r = service.assign(tenant, "business", 5000L, "plan");

        assertThat(r.action()).isEqualTo(PanelPlanService.Action.CREADA);
        assertThat(r.pendingCancelled()).isEqualTo(1);
        assertThat(reduccion.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
        assertThat(prueba.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
        assertThat(prueba.getCancelledAt()).isNotNull();
        assertThat(r.cancelledIds()).containsExactly(prueba.getId());
        Subscription nueva = rows.get(rows.size() - 1);
        assertThat(nueva.getPlan()).isSameAs(business);
        assertThat(nueva.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(nueva.getPaymentGateway()).isEqualTo(PanelPlanService.GATEWAY_PANEL);
        assertThat(nueva.getCurrentPeriodEnd()).as("sin duration_days: no vence (no cae a past_due ni a Free)").isNull();
        // Cupo del panel sobre un plan ilimitado: el que se aplica es 5000
        assertThat(r.after().override()).isEqualTo(5000);
        assertThat(r.after().maxDocumentsMonth()).isEqualTo(5000);
        assertThat(tenant.getPlan()).isEqualTo("business");
        assertThat(tenant.getDocumentsMonthOverride()).isEqualTo(5000);
        assertThat(tenant.getMaxDocumentsMonth()).isEqualTo(5000);
        verify(jdbc).update(anyString(), eq("business"), eq(5000), eq(5000), eq("plan"), any(), eq(7L));
        // Auditoría: antes la prueba de pro (tal como estaba), después la del panel
        assertThat(r.before().toMeta()).containsEntry("plan", "free").containsEntry("max_documents_month", 20);
        assertThat(r.before().subscription()).containsEntry("estado", "trialing").containsEntry("plan", "pro");
        assertThat(r.after().subscription()).containsEntry("estado", "active").containsEntry("origen", "admin");
    }

    @Test
    void mismo_plan_activo_del_panel_se_conserva_y_el_cupo_igual_al_del_plan_sigue_al_plan() {
        Plan pro = plan("pro", 1000, "49", null);
        Subscription actual = existing(pro, Subscription.STATUS_ACTIVE, PanelPlanService.GATEWAY_PANEL);

        PanelPlanService.Result r = service.assign(tenant, "pro", 1000L, "unlimited");

        assertThat(r.action()).isEqualTo(PanelPlanService.Action.CONSERVADA);
        assertThat(actual.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(rows).hasSize(1);
        assertThat(r.after().override()).isNull();
        assertThat(r.after().maxDocumentsMonth()).isEqualTo(1000);
        assertThat(tenant.getEmissionMode()).isEqualTo("unlimited");
    }

    @Test
    void plan_de_pago_propio_que_vencera_se_reemplaza_por_el_del_panel() {
        Plan pro = plan("pro", 1000, "49", 30);
        Subscription culqi = existing(pro, Subscription.STATUS_ACTIVE, "culqi");

        PanelPlanService.Result r = service.assign(tenant, "pro", null, "plan");

        assertThat(r.action()).isEqualTo(PanelPlanService.Action.CREADA);
        assertThat(culqi.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
        Subscription nueva = rows.get(rows.size() - 1);
        assertThat(nueva.getCurrentPeriodEnd()).as("con duration_days: vence a los 30 días").isNotNull();
        assertThat(java.time.Duration.between(nueva.getCurrentPeriodStart(), nueva.getCurrentPeriodEnd()).toDays()).isEqualTo(30);
    }

    @Test
    void free_sin_fila_en_plans_solo_cancela_y_rige_el_free_incorporado() {
        Subscription pro = existing(plan("pro", 1000, "49", null), Subscription.STATUS_ACTIVE, PanelPlanService.GATEWAY_PANEL);

        PanelPlanService.Result r = service.assign(tenant, "free", null, "plan");

        assertThat(r.action()).isEqualTo(PanelPlanService.Action.CANCELADA);
        assertThat(pro.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
        assertThat(r.after().subscription()).isNull();
        assertThat(r.after().maxDocumentsMonth()).isEqualTo(-1);
    }

    @Test
    void plan_de_pago_sin_fila_en_plans_es_un_error_y_no_toca_nada() {
        assertThatThrownBy(() -> service.assign(tenant, "pro", null, "plan"))
                .isInstanceOf(PanelPlanService.PanelPlanException.class).hasMessageContaining("No existe el plan pro");
        verify(jdbc, never()).queryForMap(anyString(), anyLong());
        verify(subscriptions, never()).save(any());
    }

    @Test
    void reglas_para_conservar_la_suscripcion_vigente() {
        BigDecimal cero = BigDecimal.ZERO;
        BigDecimal pago = new BigDecimal("49");
        LocalDateTime fin = NOW.plusDays(5);
        assertThat(PanelPlanService.keepCurrent("active", "pro", null, fin, "admin", pago, NOW, "pro")).isTrue();
        assertThat(PanelPlanService.keepCurrent("active", "pro", null, null, "admin", pago, NOW, "pro")).isTrue();
        assertThat(PanelPlanService.keepCurrent("active", "free", null, fin, "culqi", cero, NOW, "free")).as("gratuito: se renueva solo").isTrue();
        assertThat(PanelPlanService.keepCurrent("active", "pro", null, fin, "culqi", pago, NOW, "pro")).as("de pago sin cobro: caería a past_due").isFalse();
        assertThat(PanelPlanService.keepCurrent("trialing", "pro", null, fin, "admin", pago, NOW, "pro")).isFalse();
        assertThat(PanelPlanService.keepCurrent("active", "pro", null, fin, "admin", pago, NOW, "business")).isFalse();
        assertThat(PanelPlanService.keepCurrent("active", "pro", NOW.minusDays(1), fin, "admin", pago, NOW, "pro")).as("cancelación pedida").isFalse();
        assertThat(PanelPlanService.keepCurrent("active", "pro", null, NOW, "admin", pago, NOW, "pro")).as("vencida").isFalse();
        assertThat(PanelPlanService.periodEnd(null, NOW)).isNull();
        assertThat(PanelPlanService.periodEnd(0, NOW)).isNull();
        assertThat(PanelPlanService.periodEnd(15, NOW)).isEqualTo(NOW.plusDays(15));
    }
}
