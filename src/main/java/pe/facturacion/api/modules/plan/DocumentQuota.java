package pe.facturacion.api.modules.plan;

import java.util.Map;
import pe.facturacion.api.modules.plan.domain.Plan;

/**
 * Regla del cupo mensual de comprobantes SUNAT (Docs/api-sunat-panel-admin-diseno.md §12.2). Sin acceso a datos.
 *
 * <ul>
 *   <li><b>Cupo del panel</b> ({@code tenants.documents_month_override}, V9): si tiene valor, es el cupo que se
 *       aplica, aunque el plan diga otra cosa (también si el plan es ilimitado).</li>
 *   <li>Sin cupo del panel ({@code null}): el {@code documents_month} del plan efectivo (el de la suscripción activa,
 *       o Free), exactamente como antes de V9: {@code -1} o {@code is_unlimited} = ilimitado y 30 si no está definido.</li>
 *   <li>{@code tenants.max_documents_month} es una <b>copia informativa</b> del cupo efectivo ({@link #effective});
 *       la mantienen el panel, la suscripción propia de la empresa y las tareas de renovación.</li>
 * </ul>
 * Las cabeceras {@code X-Usage-*} y el reporte de uso de {@code /api/v1} conservan sus valores por defecto de siempre
 * cuando no hay cupo del panel (sus formatos no cambian).
 */
public final class DocumentQuota {

    /** Ilimitado. */
    public static final int UNLIMITED = -1;
    public static final String SOURCE_PANEL = "panel";
    public static final String SOURCE_PLAN = "plan";

    private DocumentQuota() {
    }

    /** Cupo por defecto del plan: {@code -1} si el plan es ilimitado; si no, {@code documents_month} (30 si falta). */
    public static int planDefault(Plan plan) {
        if (plan == null) {
            return 30;
        }
        return plan.isUnlimited() ? UNLIMITED : plan.getLimit("documents_month", 30);
    }

    /** Cupo que aplica {@code EmissionPolicy} (y que se copia en {@code max_documents_month}); {@code -1} = ilimitado. */
    public static int effective(Integer override, Plan plan) {
        return override != null ? override : planDefault(plan);
    }

    /** {@code panel} si hay cupo del panel; {@code plan} si se usa el del plan. */
    public static String source(Integer override) {
        return override != null ? SOURCE_PANEL : SOURCE_PLAN;
    }

    /**
     * Cupo del panel que se guarda para un cupo pedido: {@code null} (seguir al plan) si no se pidió o si es igual al
     * del plan; si no, el valor pedido.
     */
    public static Integer overrideFor(Long requested, Plan plan) {
        if (requested == null || requested == planDefault(plan)) {
            return null;
        }
        return requested.intValue();
    }

    /** Límite de la cabecera {@code X-Usage-Documents} (sin cupo del panel: el de siempre, 30 por defecto). */
    public static int headerLimit(Integer override, Plan plan) {
        return override != null ? override : plan.getLimit("documents_month", 30);
    }

    /** {@code usage.documents_month.limit} del reporte de uso (sin cupo del panel: el de siempre, -1 por defecto). */
    public static Object reportLimit(Integer override, Map<String, Object> limits) {
        return override != null ? (Object) override : limits.getOrDefault("documents_month", -1);
    }

    /** {@code usage.documents_month.unlimited} del reporte de uso (el cupo del panel nunca es ilimitado). */
    public static boolean reportUnlimited(Integer override, Map<String, Object> limits) {
        if (override != null) {
            return false;
        }
        Object v = limits.getOrDefault("documents_month", -1);
        return v instanceof Number n && !(v instanceof Double) && n.longValue() == -1;
    }
}
