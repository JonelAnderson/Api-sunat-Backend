package pe.facturacion.api.config;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security se usa para lo transversal: API sin estado, sin CSRF/sesiones/login,
 * cabeceras de seguridad ({@code SecurityHeaders} de API-PRO) y CORS ({@code config/cors.php}).
 * La autenticación de la API (X-Api-Key/X-Api-Secret) la hace {@code TenantAuthInterceptor},
 * que corre solo sobre rutas existentes, igual que el middleware de grupo de Laravel.
 */
@Configuration
@ConditionalOnWebApplication
public class SecurityConfig {

    /** Cabeceras que emite la API (en API-PRO {@code exposed_headers} tenía nombres que no existen). */
    private static final List<String> EXPOSED = List.of(
            "X-Usage-Documents", "X-Usage-Warning", "X-Usage-Remaining", "X-Usage-AI", "X-Plan",
            "Idempotency-Replayed", "X-RateLimit-Limit", "X-RateLimit-Remaining", "Content-Disposition");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .anonymous(a -> {
                })
                // Usa el bean "corsConfigurationSource" definido abajo
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .headers(h -> h
                        .cacheControl(c -> c.disable())
                        .xssProtection(x -> x.disable())
                        .contentTypeOptions(c -> {
                        })
                        .frameOptions(f -> f.sameOrigin())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // HSTS solo sobre HTTPS (comportamiento por defecto de Spring, igual que API-PRO)
                        .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .addHeaderWriter(new StaticHeadersWriter("X-Permitted-Cross-Domain-Policies", "none")));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props, AdminProperties admin, PortalProperties portal) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Panel de administración: solo los orígenes de ADMIN_ALLOWED_ORIGINS (sin comodín). Va antes que /api/**
        // porque gana el primer patrón que coincide.
        CorsConfiguration adminCfg = new CorsConfiguration();
        adminCfg.setAllowedOrigins(admin.origins());
        adminCfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // Idempotency-Key: la emisión desde el panel (§11.6) la envía; sin permitirla, el preflight falla en otro dominio.
        adminCfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
        // Las descargas (XML / CDR / PDF) traen el nombre del archivo en Content-Disposition, y una emisión repetida responde
        // Idempotency-Replayed: sin exponerlas, el navegador las oculta cuando el panel está en otro dominio.
        adminCfg.setExposedHeaders(List.of("Content-Disposition", "Idempotency-Replayed"));
        adminCfg.setMaxAge(3600L);
        adminCfg.setAllowCredentials(false);
        source.registerCorsConfiguration("/api/admin/**", adminCfg);

        // Portal de clientes: solo los orígenes de PORTAL_ALLOWED_ORIGINS (sin comodín), sin credenciales. No admite la
        // cabecera Idempotency-Key (no emite nada) ni DELETE.
        CorsConfiguration portalCfg = new CorsConfiguration();
        portalCfg.setAllowedOrigins(portal.origins());
        portalCfg.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        portalCfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // Las descargas (XML / CDR / PDF) traen el nombre del archivo en Content-Disposition
        portalCfg.setExposedHeaders(List.of("Content-Disposition"));
        portalCfg.setMaxAge(3600L);
        portalCfg.setAllowCredentials(false);
        source.registerCorsConfiguration("/api/portal/**", portalCfg);

        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = props.security().corsOrigins(props.url());
        if (origins.contains("*")) {
            cfg.addAllowedOriginPattern("*");
        } else {
            cfg.setAllowedOrigins(origins);
        }
        cfg.addAllowedMethod("*");
        cfg.addAllowedHeader("*");
        cfg.setExposedHeaders(EXPOSED);
        cfg.setMaxAge(86400L);
        cfg.setAllowCredentials(false);
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
