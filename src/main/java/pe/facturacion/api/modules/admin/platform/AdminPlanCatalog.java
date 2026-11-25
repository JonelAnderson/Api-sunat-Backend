package pe.facturacion.api.modules.admin.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de límites y funcionalidades conocidos para el formulario de planes (el {@code config/plans.php} de la
 * referencia): etiquetas y grupos. Un plan puede llevar claves que no estén aquí (quedan sin etiqueta).
 */
public final class AdminPlanCatalog {

    private record Item(String group, String key, String label, String help) {
    }

    private static final List<Item> LIMITS = List.of(
            new Item("SUNAT", "documents_month", "Documentos SUNAT por mes",
                    "Facturas, boletas, notas, guías, retenciones, percepciones y anulaciones. -1 = ilimitado."),
            new Item("SUNAT", "internal_documents_month", "Documentos internos por mes", "Cotizaciones y notas de venta (no van a SUNAT)."),
            new Item("Uso general", "ai_messages_month", "Mensajes de IA por mes", null),
            new Item("Uso general", "team_members", "Miembros del equipo", null),
            new Item("Uso general", "sucursales", "Sucursales", null),
            new Item("Uso general", "productos", "Productos en catálogo", null),
            new Item("Marketplace y B2B", "catalog_listings", "Listings en el marketplace", null),
            new Item("Marketplace y B2B", "rfqs_month", "Cotizaciones recibidas por mes", null),
            new Item("Marketplace y B2B", "b2b_requests_month", "Solicitudes B2B por mes", null),
            new Item("Marketplace y B2B", "reviews_month", "Reseñas por mes", null),
            new Item("Marketplace y B2B", "feed_posts_month", "Publicaciones en el feed por mes", null));

    private static final List<Item> FEATURES = List.of(
            new Item("Emisión SUNAT", "facturacion", "Emisión de facturas", null),
            new Item("Emisión SUNAT", "boletas", "Emisión de boletas + resumen diario", null),
            new Item("Emisión SUNAT", "notas", "Notas de crédito y débito", null),
            new Item("Emisión SUNAT", "guias_remision", "Guías de remisión (GRR/GRT)", null),
            new Item("Emisión SUNAT", "retenciones", "Comprobantes de retención", null),
            new Item("Emisión SUNAT", "percepciones", "Comprobantes de percepción", null),
            new Item("Emisión SUNAT", "anulaciones", "Comunicaciones de baja", null),
            new Item("Emisión SUNAT", "sire", "SIRE - Registro de Compras", null),
            new Item("Documentos internos", "cotizaciones", "Cotizaciones", null),
            new Item("Documentos internos", "notas_venta", "Notas de venta", null),
            new Item("Documentos internos", "pagos", "Registro de pagos", null),
            new Item("Operaciones", "inventario_basico", "Inventario básico", null),
            new Item("Operaciones", "inventario_avanzado", "Inventario avanzado (lotes, series, kardex)", null),
            new Item("Operaciones", "compras", "Compras y proveedores", null),
            new Item("Reportes y datos", "reportes_basicos", "Reportes básicos", null),
            new Item("Reportes y datos", "reportes_avanzados", "Reportes avanzados", null),
            new Item("Reportes y datos", "audit_logs", "Auditoría de acciones", null),
            new Item("Reportes y datos", "export_zip", "Exportación masiva ZIP", null),
            new Item("Marketplace", "marketplace_browse", "Navegar el marketplace", null),
            new Item("B2B", "b2b_basic", "Solicitudes B2B básicas", null),
            new Item("Feed / social", "feed_view", "Ver el feed", null),
            new Item("Integraciones", "webhooks", "Webhooks", null),
            new Item("Integraciones", "api", "Acceso a la API", null),
            new Item("Integraciones", "ai_assistant", "Asistente IA", null),
            new Item("Score y admin", "soporte_prioritario", "Soporte prioritario", null),
            new Item("Score y admin", "panel", "Panel de control", null));

    private AdminPlanCatalog() {
    }

    /** {@code {limits: [{group, key, label, help}], features: [{group, key, label}]}}. */
    public static Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limits", items(LIMITS, true));
        m.put("features", items(FEATURES, false));
        return m;
    }

    private static List<Map<String, Object>> items(List<Item> items, boolean withHelp) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", i.group());
            m.put("key", i.key());
            m.put("label", i.label());
            if (withHelp) {
                m.put("help", i.help());
            }
            out.add(m);
        }
        return out;
    }
}
