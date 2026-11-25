package pe.facturacion.api.seed;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import pe.facturacion.api.config.AdminProperties;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.PasswordPolicy;
import pe.facturacion.api.modules.admin.user.AdminAuthService;
import pe.facturacion.api.modules.admin.user.AdminUserStore;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.shared.time.Clock;

/**
 * Primer superadmin del panel: si no hay ningún administrador ({@code users.is_admin}) y están definidas
 * {@code ADMIN_INITIAL_EMAIL} y {@code ADMIN_INITIAL_PASSWORD}, lo crea con rol {@code superadmin}. Configura el MFA en
 * su primer ingreso. Corre en cada arranque (no depende de {@code APP_SEED}) y no hace nada si ya hay administradores.
 * Nunca escribe la contraseña en el log.
 */
@Component
@ConditionalOnWebApplication
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final AdminProperties props;
    private final AdminUserStore users;
    private final AdminAuthService auth;
    private final AuditLogService audit;

    public AdminSeeder(AdminProperties props, AdminUserStore users, AdminAuthService auth, AuditLogService audit) {
        this.props = props;
        this.users = users;
        this.auth = auth;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** true si creó el superadmin. */
    boolean seed() {
        String email = props.initialEmail() == null ? "" : props.initialEmail().trim().toLowerCase();
        String password = props.initialPassword();
        if (email.isEmpty() || password == null || password.isEmpty()) {
            return false;
        }
        if (users.countAdmins() > 0) {
            return false;
        }
        if (!AdminValidation.isEmail(email)) {
            log.warn("ADMIN_INITIAL_EMAIL no es un correo válido: no se creó el superadministrador inicial");
            return false;
        }
        List<String> problems = PasswordPolicy.validate(password);
        if (!problems.isEmpty()) {
            log.warn("ADMIN_INITIAL_PASSWORD no cumple la política ({}): no se creó el superadministrador inicial", String.join(" ", problems));
            return false;
        }
        if (users.emailTaken(email, null)) {
            log.warn("ADMIN_INITIAL_EMAIL ya pertenece a un usuario que no es administrador: no se creó el superadministrador inicial");
            return false;
        }
        try {
            long id = users.insert("Superadministrador", email, auth.hashPassword(password), "superadmin", Clock.nowUtc());
            audit.registrar(null, "admin.usuario.creado", null, AuditLogService.SUBJECT_USER, id,
                    "Superadministrador inicial creado desde ADMIN_INITIAL_EMAIL", null);
            log.info("Superadministrador inicial del panel creado (id={}); configurará el MFA en su primer ingreso", id);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Otra instancia lo creó al mismo tiempo
            return false;
        }
    }
}
