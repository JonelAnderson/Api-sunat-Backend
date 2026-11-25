package pe.facturacion.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import pe.facturacion.api.modules.apilog.ApiLogWriter;
import pe.facturacion.api.modules.idempotency.IdempotencyStore;
import pe.facturacion.api.modules.plan.PlanService;
import pe.facturacion.api.modules.tenant.domain.Tenant;

/**
 * Post-proceso de la pila de middlewares de API-PRO, en el orden inverso de Laravel:
 * {@code usage.headers} → {@code log.api} → {@code idempotency}. Mantiene la respuesta en
 * memoria para poder añadir cabeceras y guardar el cuerpo antes de enviarla.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiPipelineFilter extends OncePerRequestFilter {

    private final IdempotencyStore idempotency;
    private final ApiLogWriter apiLogs;
    private final PlanService plans;

    public ApiPipelineFilter(IdempotencyStore idempotency, ApiLogWriter apiLogs, PlanService plans) {
        this.idempotency = idempotency;
        this.apiLogs = apiLogs;
        this.plans = plans;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            afterResponse(request, wrapped);
            wrapped.copyBodyToResponse();
        }
    }

    private void afterResponse(HttpServletRequest request, ContentCachingResponseWrapper response) {
        Tenant tenant = ApiContext.tenant(request);
        boolean replayed = Boolean.TRUE.equals(request.getAttribute(ApiContext.IDEMPOTENCY_REPLAYED));

        if (!replayed && tenant != null && Boolean.TRUE.equals(request.getAttribute(ApiContext.USAGE_HEADERS))) {
            plans.applyUsageHeaders(tenant, response);
        }

        Object start = request.getAttribute(ApiContext.LOG_START);
        if (!replayed && start instanceof Long nanos) {
            int durationMs = (int) ((System.nanoTime() - nanos) / 1_000_000);
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) request.getAttribute(ApiContext.LOG_INPUT);
            apiLogs.log(request, input == null ? Map.of() : input, response.getStatus(), response.getContentAsByteArray(),
                    response.getContentType(), tenant, durationMs);
        }

        Object key = request.getAttribute(ApiContext.IDEMPOTENCY_KEY);
        if (key instanceof String idemKey && tenant != null) {
            int status = response.getStatus();
            String contentType = response.getContentType() == null ? "" : response.getContentType();
            byte[] body = response.getContentAsByteArray();
            // Diferencias con API-PRO (docs/DIFERENCIAS.md): no se guardan respuestas 429 ni
            // respuestas binarias (PDF/XML/ZIP), que al repetirse saldrían como application/json.
            boolean storable = status < 500 && status != 429 && contentType.contains("json") && body.length > 0;
            if (storable) {
                idempotency.complete(tenant.getId(), idemKey, status, new String(body, StandardCharsets.UTF_8));
                response.setHeader("Idempotency-Replayed", "false");
            } else {
                idempotency.release(tenant.getId(), idemKey);
            }
        }
    }
}
