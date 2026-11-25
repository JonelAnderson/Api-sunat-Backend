package pe.facturacion.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import pe.facturacion.api.shared.storage.FileStorage;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import pe.facturacion.api.modules.admin.security.AdminInterceptor;
import pe.facturacion.api.modules.portal.security.PortalInterceptor;
import pe.facturacion.api.security.ApiLogInterceptor;
import pe.facturacion.api.security.IdempotencyInterceptor;
import pe.facturacion.api.security.PublicFeatureInterceptor;
import pe.facturacion.api.security.RateLimitInterceptor;
import pe.facturacion.api.security.TenantAuthInterceptor;
import pe.facturacion.api.shared.json.PhpJsonHttpMessageConverter;

/**
 * Grupos de rutas de {@code routes/api.php} y su pila de middlewares.
 * Como en Laravel, la pila solo corre en rutas que existen: una URL inexistente responde 404
 * (y un método no permitido 405) sin pasar por la autenticación.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** Grupo protegido: {@code resolve.tenant, idempotency, throttle:api, log.api, usage.headers}. */
    public static final String[] PROTECTED = {"/api/v1/**", "/api/sunat/**"};
    /** Panel de administración interno (JWT + MFA propios, sobre camelCase). */
    public static final String ADMIN = "/api/admin/**";
    /** Portal de clientes (JWT + MFA propios, distintos de los del panel). */
    public static final String PORTAL = "/api/portal/**";
    /** Rutas públicas de {@code /api/v1} (solo {@code throttle:api}). */
    public static final String[] PUBLIC_V1 = {
        "/api/v1/registro", "/api/v1/planes",
        "/api/v1/credenciales/recuperar", "/api/v1/credenciales/recuperar/verificar"
    };
    /**
     * Respuestas con secretos en claro que nunca se guardan en {@code idempotency_keys} (hallazgo H10): repetir la
     * petición con la misma {@code Idempotency-Key} vuelve a ejecutarla (no se reproduce el {@code api_secret}).
     */
    public static final String[] NO_IDEMPOTENCY = {"/api/v1/empresa/credenciales/regenerar"};

    private final TenantAuthInterceptor tenantAuth;
    private final IdempotencyInterceptor idempotency;
    private final RateLimitInterceptor rateLimit;
    private final ApiLogInterceptor apiLog;

    private final FileStorage storage;
    private final AdminInterceptor admin;
    private final PortalInterceptor portal;
    private final PublicFeatureInterceptor publicFeatures;

    public WebMvcConfig(TenantAuthInterceptor tenantAuth, IdempotencyInterceptor idempotency,
            RateLimitInterceptor rateLimit, ApiLogInterceptor apiLog, FileStorage storage, AdminInterceptor admin,
            PortalInterceptor portal, PublicFeatureInterceptor publicFeatures) {
        this.publicFeatures = publicFeatures;
        this.portal = portal;
        this.storage = storage;
        this.admin = admin;
        this.tenantAuth = tenantAuth;
        this.idempotency = idempotency;
        this.rateLimit = rateLimit;
        this.apiLog = apiLog;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Registro público y autoservicio de suscripción: apagados por defecto; van primero (antes de auth y throttle).
        registry.addInterceptor(publicFeatures).addPathPatterns(PublicFeatureInterceptor.REGISTRO_PATH,
                PublicFeatureInterceptor.SUSCRIPCION_PATH, PublicFeatureInterceptor.SUSCRIPCION_PATH + "/**");
        registry.addInterceptor(tenantAuth).addPathPatterns(PROTECTED).excludePathPatterns(PUBLIC_V1);
        registry.addInterceptor(idempotency).addPathPatterns(PROTECTED).excludePathPatterns(PUBLIC_V1).excludePathPatterns(NO_IDEMPOTENCY);
        registry.addInterceptor(rateLimit).addPathPatterns(PROTECTED).addPathPatterns("/api/health", "/api/bridge/**");
        registry.addInterceptor(apiLog).addPathPatterns(PROTECTED).excludePathPatterns(PUBLIC_V1);
        // Panel de administración: 503 sin ADMIN_JWT_SECRET, límite por IP en /auth, JWT + MFA y permisos por rol.
        // No pasa por la pila de /api/v1 (ni api_logs ni idempotencia: los cuerpos llevan contraseñas y códigos).
        registry.addInterceptor(admin).addPathPatterns(ADMIN);
        // Portal de clientes: 503 sin PORTAL_JWT_SECRET, límite por IP en /auth, JWT propio (emisor y audiencia del portal) y
        // solo lectura salvo regenerar credenciales. Tampoco pasa por la pila de /api/v1.
        registry.addInterceptor(portal).addPathPatterns(PORTAL);
    }

    /** Disco {@code public} servido en {@code /storage/**} (logos), como {@code php artisan storage:link}. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = storage.diskRoot(FileStorage.Disk.PUBLIC).toUri().toString();
        registry.addResourceHandler("/storage/**").addResourceLocations(location.endsWith("/") ? location : location + "/");
    }

    /** Respuestas JSON con el formato de Laravel: el convertidor propio va antes que los de Spring (Jackson). */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.addCustomConverter(new PhpJsonHttpMessageConverter());
    }
}
