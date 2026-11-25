package pe.facturacion.api;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import pe.facturacion.api.modules.ops.OpsCli;
import pe.facturacion.api.modules.ops.OpsTasks;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class ApiApplication {

    public static void main(String[] args) {
        // Igual que API-PRO (config/app.php timezone=UTC): created_at/updated_at en UTC.
        // Las fechas de negocio (fecha_emision) se calculan explícitamente en America/Lima.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if (args.length > 0 && "ops".equals(args[0])) {
            // Comandos de operación (php artisan ...): sin servidor web, worker ni planificador
            // Propiedades del sistema: tienen prioridad sobre application.yml y el .env
            java.util.Map.of("app.queue.worker-enabled", "false", "app.scheduler.enabled", "false", "app.seed", "false",
                    "spring.main.banner-mode", "off", "logging.level.root", "WARN", "logging.level.pe.facturacion.api", "WARN")
                    .forEach(System::setProperty);
            SpringApplication app = new SpringApplication(ApiApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            int code;
            try (ConfigurableApplicationContext ctx = app.run()) {
                code = OpsCli.run(ctx.getBean(OpsTasks.class), args);
            }
            System.exit(code);
        }
        SpringApplication.run(ApiApplication.class, args);
    }
}
