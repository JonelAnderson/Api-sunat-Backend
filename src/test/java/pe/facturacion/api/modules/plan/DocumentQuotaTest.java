package pe.facturacion.api.modules.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.modules.plan.domain.Plan;

/** Regla del cupo mensual (§12.2): cupo del panel sobre el del plan, y valores de siempre sin cupo del panel. */
class DocumentQuotaTest {

    static Plan plan(String slug, Integer documentsMonth, boolean unlimited) {
        Plan p = new Plan();
        p.setSlug(slug);
        Map<String, Object> limits = new LinkedHashMap<>();
        if (documentsMonth != null) {
            limits.put("documents_month", documentsMonth);
        }
        p.setLimits(limits);
        p.setUnlimited(unlimited);
        return p;
    }

    @Test
    void sin_cupo_del_panel_rige_el_del_plan_como_antes() {
        assertThat(DocumentQuota.effective(null, plan("pro", 1000, false))).isEqualTo(1000);
        assertThat(DocumentQuota.effective(null, plan("business", -1, false))).isEqualTo(-1);
        // is_unlimited manda aunque el límite diga otra cosa (como EmissionPolicy antes de V9)
        assertThat(DocumentQuota.effective(null, plan("x", 500, true))).isEqualTo(-1);
        // Plan sin documents_month: 30
        assertThat(DocumentQuota.effective(null, plan("x", null, false))).isEqualTo(30);
        // Free incorporado (sin fila en plans): ilimitado
        assertThat(DocumentQuota.effective(null, Plan.builtFree())).isEqualTo(-1);
        assertThat(DocumentQuota.source(null)).isEqualTo("plan");
    }

    @Test
    void el_cupo_del_panel_se_aplica_aunque_el_plan_sea_ilimitado() {
        assertThat(DocumentQuota.effective(5000, plan("business", -1, false))).isEqualTo(5000);
        assertThat(DocumentQuota.effective(0, plan("pro", 1000, false))).isZero();
        assertThat(DocumentQuota.effective(20, plan("x", 500, true))).isEqualTo(20);
        assertThat(DocumentQuota.source(20)).isEqualTo("panel");
    }

    @Test
    void el_cupo_pedido_igual_al_del_plan_o_vacio_sigue_al_plan() {
        Plan pro = plan("pro", 1000, false);
        assertThat(DocumentQuota.overrideFor(null, pro)).isNull();
        assertThat(DocumentQuota.overrideFor(1000L, pro)).isNull();
        assertThat(DocumentQuota.overrideFor(500L, pro)).isEqualTo(500);
        assertThat(DocumentQuota.overrideFor(0L, pro)).isZero();
        // El 20 de siempre en un Free de 30: es un cupo del panel (lo pidió el administrador)
        assertThat(DocumentQuota.overrideFor(20L, plan("free", 30, false))).isEqualTo(20);
        assertThat(DocumentQuota.overrideFor(2000L, plan("business", -1, false))).isEqualTo(2000);
    }

    @Test
    void cabeceras_y_reporte_conservan_sus_valores_por_defecto_sin_cupo_del_panel() {
        Plan sinLimite = plan("x", null, false);
        assertThat(DocumentQuota.headerLimit(null, sinLimite)).isEqualTo(30);
        assertThat(DocumentQuota.headerLimit(null, plan("pro", 1000, false))).isEqualTo(1000);
        assertThat(DocumentQuota.headerLimit(250, plan("pro", 1000, false))).isEqualTo(250);

        Map<String, Object> sinDocs = Map.of();
        assertThat(DocumentQuota.reportLimit(null, sinDocs)).isEqualTo(-1);
        assertThat(DocumentQuota.reportUnlimited(null, sinDocs)).isTrue();
        Map<String, Object> business = Map.of("documents_month", -1);
        assertThat(DocumentQuota.reportUnlimited(null, business)).isTrue();
        assertThat(DocumentQuota.reportLimit(300, business)).isEqualTo(300);
        assertThat(DocumentQuota.reportUnlimited(300, business)).isFalse();
        Map<String, Object> pro = Map.of("documents_month", 1000);
        assertThat(DocumentQuota.reportLimit(null, pro)).isEqualTo(1000);
        assertThat(DocumentQuota.reportUnlimited(null, pro)).isFalse();
    }
}
