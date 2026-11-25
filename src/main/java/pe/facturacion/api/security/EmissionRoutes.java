package pe.facturacion.api.security;

import java.util.Set;

/**
 * Rutas que emiten un comprobante nuevo (un {@code POST} que crea factura, boleta, nota, guía, retención, percepción o
 * liquidación de compra). Una empresa suspendida por falta de pago ({@code tenants.suspension_kind = 'pago'}) no puede
 * llamarlas (402), pero sí consultar, descargar y terminar lo que ya estaba en cola: reenviar o enviar lo ya registrado,
 * resúmenes diarios, comunicaciones de baja y reversiones son obligaciones ante SUNAT y siguen funcionando.
 */
public final class EmissionRoutes {

    public static final String BILLING_SUSPENDED_MESSAGE = "La emisión de comprobantes está suspendida por falta de pago. "
            + "Puede consultar y descargar sus comprobantes; contacte al administrador para reactivarla.";

    private static final Set<String> POST_PATHS = Set.of(
            "/api/v1/facturas", "/api/v1/facturas/masivo", "/api/v1/boletas",
            "/api/v1/notas-credito", "/api/v1/notas-debito",
            "/api/v1/guias-remision", "/api/v1/guias-remision-transportista",
            "/api/v1/retenciones", "/api/v1/percepciones", "/api/v1/liquidaciones-compra",
            "/api/sunat/facturas", "/api/sunat/boletas");

    private EmissionRoutes() {
    }

    /**
     * true si la petición crea un comprobante nuevo. {@code path} debe ser la ruta que usa Spring para elegir el
     * controlador ({@code request.getServletPath()}: ya decodificada y sin parámetros de ruta), no {@code getRequestURI()},
     * que se puede escribir con {@code %xx} o {@code ;x=1} y no coincidiría.
     */
    public static boolean isNewEmission(String httpMethod, String path) {
        if (!"POST".equals(httpMethod) || path == null) {
            return false;
        }
        String p = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return POST_PATHS.contains(p);
    }
}
