package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Migraciones del panel sobre PostgreSQL real (sin la aplicación): el callback {@code beforeMigrate.sql} + V10
 * conservan el rol {@code soporte} de API-PRO al actualizar una base que no había aplicado V7, no hacen nada en una
 * base que ya lo había aplicado ni en una nueva, y V9 deja {@code max_documents_month} con el cupo que se aplica sin
 * cambiar ningún cupo (§12.2 y §12.3 del diseño). Cada caso usa su propia base.
 */
class FlywayMigracionesPanelIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("flyway_panel").withUsername("test").withPassword("test");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void base_de_api_pro_sin_v7_conserva_soporte_y_los_demas_roles_se_traducen() throws Exception {
        String url = newDatabase("desde_v6");
        flyway(url, "6", false).migrate();
        exec(url, "INSERT INTO users (name, email, password, is_admin, role, is_active, created_at, updated_at) VALUES "
                + "('Soporte', 'soporte@x.pe', 'x', true, 'soporte', true, now(), now()),"
                + "('Super', 'super@x.pe', 'x', true, 'super_admin', true, now(), now()),"
                + "('Admin', 'admin@x.pe', 'x', true, 'admin', true, now(), now()),"
                + "('SinRol', 'sinrol@x.pe', 'x', true, NULL, true, now(), now())");

        flyway(url, null, false).migrate();

        assertThat(role(url, "soporte@x.pe")).isEqualTo("soporte");
        assertThat(role(url, "super@x.pe")).isEqualTo("superadmin");
        assertThat(role(url, "admin@x.pe")).isEqualTo("operador");
        assertThat(role(url, "sinrol@x.pe")).isEqualTo("lectura");
        assertThat(scalar(url, "SELECT count(*) FROM pg_tables WHERE tablename = 'admin_legacy_roles'")).isEqualTo("0");
        // Volver a migrar (cada arranque corre el callback) no cambia nada ni falla
        flyway(url, null, false).migrate();
        assertThat(role(url, "soporte@x.pe")).isEqualTo("soporte");
        assertThat(scalar(url, "SELECT count(*) FROM pg_tables WHERE tablename = 'admin_legacy_roles'")).isEqualTo("0");
    }

    @Test
    void base_que_ya_paso_v7_no_cambia_y_no_falla() throws Exception {
        String url = newDatabase("ya_en_v8");
        flyway(url, "6", true).migrate();
        exec(url, "INSERT INTO users (name, email, password, is_admin, role, is_active, created_at, updated_at) VALUES "
                + "('Soporte', 'soporte@x.pe', 'x', true, 'soporte', true, now(), now())");
        // Versión anterior (sin el callback): V7 y V8 convierten "soporte" en "lectura"
        flyway(url, "8", true).migrate();
        assertThat(role(url, "soporte@x.pe")).isEqualTo("lectura");

        flyway(url, null, false).migrate();

        assertThat(role(url, "soporte@x.pe")).as("lo reasigna un superadmin desde Administradores").isEqualTo("lectura");
        assertThat(scalar(url, "SELECT count(*) FROM pg_tables WHERE tablename = 'admin_legacy_roles'")).isEqualTo("0");
        assertThat(scalar(url, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("10");
    }

    @Test
    void base_nueva_migra_completa_y_v9_deja_el_cupo_que_se_aplica() throws Exception {
        String url = newDatabase("nueva");
        flyway(url, "8", false).migrate();
        exec(url, "INSERT INTO plans (slug, name, price_monthly, price_yearly, limits, features, is_unlimited, sort_order, is_active, "
                + "created_at, updated_at) VALUES ('free', 'Free', 0, 0, '{\"documents_month\":30}', '[]', false, 0, true, now(), now()),"
                + "('pro', 'Pro', 49, 490, '{\"documents_month\":1000}', '[]', false, 1, true, now(), now()),"
                + "('business', 'Business', 99, 990, '{\"documents_month\":-1}', '[]', false, 2, true, now(), now())");
        exec(url, "INSERT INTO tenants (ruc, sol_user, sol_pass, api_key, api_secret, plan, max_documents_month, created_at, updated_at) VALUES "
                + "('20000000011', 'u', 'p', 'k1', 's', 'free', 20, now(), now()),"
                + "('20000000012', 'u', 'p', 'k2', 's', 'pro', 20, now(), now()),"
                + "('20000000013', 'u', 'p', 'k3', 's', 'business', 500, now(), now())");
        exec(url, "INSERT INTO subscriptions (tenant_id, plan_id, status, billing_cycle, created_at, updated_at) "
                + "SELECT t.id, p.id, 'active', 'monthly', now(), now() FROM tenants t, plans p WHERE t.ruc = '20000000012' AND p.slug = 'pro'");
        exec(url, "INSERT INTO subscriptions (tenant_id, plan_id, status, billing_cycle, created_at, updated_at) "
                + "SELECT t.id, p.id, 'active', 'monthly', now(), now() FROM tenants t, plans p WHERE t.ruc = '20000000013' AND p.slug = 'business'");

        flyway(url, null, false).migrate();

        // Sin suscripción: el Free (30), aunque la columna dijera 20; con suscripción: el de su plan
        assertThat(scalar(url, "SELECT max_documents_month FROM tenants WHERE ruc = '20000000011'")).isEqualTo("30");
        assertThat(scalar(url, "SELECT max_documents_month FROM tenants WHERE ruc = '20000000012'")).isEqualTo("1000");
        assertThat(scalar(url, "SELECT max_documents_month FROM tenants WHERE ruc = '20000000013'")).isEqualTo("-1");
        // Ningún cupo del panel: nadie cambia de cupo al actualizar
        assertThat(scalar(url, "SELECT count(*) FROM tenants WHERE documents_month_override IS NOT NULL")).isEqualTo("0");
    }

    // ------------------------------------------------------------------ apoyo

    static Flyway flyway(String url, String target, boolean sinCallbacks) {
        var c = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration")
                .skipDefaultCallbacks(sinCallbacks);
        if (target != null) {
            c.target(target);
        }
        return c.load();
    }

    static String newDatabase(String name) throws Exception {
        exec(POSTGRES.getJdbcUrl(), "CREATE DATABASE " + name);
        return POSTGRES.getJdbcUrl().replace("/flyway_panel", "/" + name);
    }

    static void exec(String url, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static String scalar(String url, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    static String role(String url, String email) throws Exception {
        return scalar(url, "SELECT role FROM users WHERE email = '" + email + "'");
    }
}
