package pe.facturacion.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.crypto.SecretCipher;

/**
 * Middleware {@code resolve.tenant} de API-PRO: autenticación por cabeceras
 * {@code X-Api-Key} + {@code X-Api-Secret} (solo cabeceras, nunca query string).
 *
 * <p>Diferencia: API-PRO cachea el tenant 600 s (60 s en la rama de seguridad); aquí se lee
 * siempre de BD por índice único, así una credencial rotada o una empresa desactivada dejan de
 * funcionar de inmediato en todas las instancias.
 */
@Component
public class TenantAuthInterceptor implements HandlerInterceptor {

    private final TenantRepository tenants;

    public TenantAuthInterceptor(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String apiKey = request.getHeader("X-Api-Key");
        String apiSecret = request.getHeader("X-Api-Secret");

        if (isFalsy(apiKey) || isFalsy(apiSecret)) {
            ApiContext.writeJson(response, 401, ApiContext.error("Las cabeceras X-Api-Key y X-Api-Secret son requeridas."));
            return false;
        }

        Optional<Tenant> found = tenants.findByApiKey(apiKey);
        if (found.isEmpty()) {
            ApiContext.writeJson(response, 401, ApiContext.error("Credenciales de API inválidas."));
            return false;
        }
        Tenant tenant = found.get();

        boolean valid;
        try {
            valid = tenant.getApiSecret() != null && SecretCipher.constantTimeEquals(tenant.getApiSecret().reveal(), apiSecret);
        } catch (SecretCipher.DecryptException e) {
            ApiContext.writeJson(response, 401, ApiContext.error(
                    "No se pudo validar el API Secret de la empresa (credencial ilegible). Regenérela desde el panel: Configuración → Credenciales → Regenerar."));
            return false;
        }
        if (!valid) {
            ApiContext.writeJson(response, 401, ApiContext.error("Credenciales de API inválidas."));
            return false;
        }

        if (!tenant.isActive()) {
            ApiContext.writeJson(response, 403, ApiContext.error("Empresa desactivada. Contacte al administrador."));
            return false;
        }

        // Suspendida por falta de pago (consola de plataforma): consulta y descarga sí, emisión nueva no.
        if (tenant.isBillingSuspended() && EmissionRoutes.isNewEmission(request.getMethod(), servletPath(request))) {
            ApiContext.writeJson(response, 402, ApiContext.error(EmissionRoutes.BILLING_SUSPENDED_MESSAGE));
            return false;
        }

        ApiContext.setTenant(request, tenant);
        return true;
    }

    /** La ruta como la ve Spring (decodificada y sin parámetros de ruta). */
    private static String servletPath(HttpServletRequest request) {
        String info = request.getPathInfo();
        return request.getServletPath() + (info == null ? "" : info);
    }

    /** {@code ! $valor} de PHP para cabeceras: null, vacío o "0". */
    private static boolean isFalsy(String v) {
        return v == null || v.isEmpty() || "0".equals(v);
    }
}
