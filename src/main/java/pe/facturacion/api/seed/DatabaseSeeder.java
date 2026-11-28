package pe.facturacion.api.seed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.plan.domain.Plan;
import pe.facturacion.api.modules.plan.domain.PlanRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.security.ApiCredentials;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.time.Clock;

/**
 * Seeders equivalentes a {@code DatabaseSeeder} de API-PRO (planes, UNSPSC, taxonomía, ubigeo)
 * más lo que pide el prompt y API-PRO no trae: una empresa demo en BETA con sus series.
 * Se ejecuta al arrancar si {@code APP_SEED=true} y es idempotente.
 */
@Component
public class DatabaseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);

    /** RUC y usuario SOL públicos del entorno de pruebas de SUNAT. */
    static final String DEMO_RUC = "20000000001";

    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlanRepository plans;
    private final TenantRepository tenants;

    public DatabaseSeeder(AppProperties props, JdbcTemplate jdbc, TransactionTemplate tx,
            PlanRepository plans, TenantRepository tenants) {
        this.props = props;
        this.jdbc = jdbc;
        this.tx = tx;
        this.plans = plans;
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.seed()) {
            ensurePlans();
            ensureUbigeo();
            return;
        }
        tx.executeWithoutResult(s -> seedPlans());
        seedUnspscCodes();
        seedUnspscTaxonomy();
        seedUbigeo();
        tx.executeWithoutResult(s -> seedDemoTenant());
    }

    // ------------------------------------------------------------------ planes (PlanSeeder)

    void seedPlans() {
        // Mismos planes que API-PRO, sin los límites/funciones de notas de venta y cotizaciones (fuera de alcance).
        upsertPlan("free", "Gratis", "0", "0", 0,
                limits(30, 10, 1, 1, 100, 5, 1, 3, 1, 2),
                List.of("facturacion", "boletas", "notas", "guias_remision", "pagos", "inventario_basico",
                        "reportes_basicos", "feed_view", "marketplace_browse", "b2b_basic"));
        upsertPlan("pro", "Profesional", "49.00", "490.00", 1,
                limits(500, 100, 5, 3, -1, 20, 10, 15, 10, 20),
                List.of("facturacion", "boletas", "notas", "guias_remision", "pagos", "inventario_basico",
                        "reportes_basicos", "feed_view", "marketplace_browse", "b2b_basic",
                        "compras", "inventario_avanzado", "reportes_avanzados", "crm", "citas", "feed_posts",
                        "marketplace_advanced", "score_analytics"));
        upsertPlan("business", "Empresarial", "99.00", "990.00", 2,
                limits(-1, -1, 15, 10, -1, -1, -1, -1, -1, -1),
                List.of("facturacion", "boletas", "notas", "guias_remision", "pagos", "inventario_basico",
                        "reportes_basicos", "feed_view", "marketplace_browse", "b2b_basic",
                        "compras", "inventario_avanzado", "reportes_avanzados", "crm", "citas", "feed_posts",
                        "marketplace_advanced", "score_analytics",
                        "finanzas", "rrhh", "contratos", "produccion", "whatsapp_business", "custom_roles", "audit_logs",
                        "soporte_prioritario", "b2b_invoicing", "b2b_unlimited", "b2b_templates", "feed_promoted",
                        "marketplace_unlimited", "marketplace_promoted_listings", "score_export"));
        jdbc.update("UPDATE plans SET is_active = false WHERE slug IN ('starter', 'growth')");
    }

    private static Map<String, Object> limits(int docs, int ai, int team, int sucursales, int productos,
            int catalog, int rfqs, int b2b, int reviews, int feed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documents_month", docs);
        m.put("ai_messages_month", ai);
        m.put("team_members", team);
        m.put("sucursales", sucursales);
        m.put("productos", productos);
        m.put("catalog_listings", catalog);
        m.put("rfqs_month", rfqs);
        m.put("b2b_requests_month", b2b);
        m.put("reviews_month", reviews);
        m.put("feed_posts_month", feed);
        return m;
    }

    private void upsertPlan(String slug, String name, String monthly, String yearly, int sort,
            Map<String, Object> limits, List<String> features) {
        Plan p = plans.findBySlug(slug).orElseGet(Plan::new);
        p.setSlug(slug);
        p.setName(name);
        p.setPriceMonthly(new BigDecimal(monthly));
        p.setPriceYearly(new BigDecimal(yearly));
        p.setSortOrder(sort);
        p.setLimits(limits);
        p.setFeatures(features);
        plans.save(p);
    }

    // ------------------------------------------------------------------ catálogos

    private void seedUnspscCodes() throws IOException {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM unspsc_codes", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        int total = 0;
        try (BufferedReader r = reader("seed/unspsc_v14.csv")) {
            CsvReader.next(r); // cabecera
            List<String> row;
            while ((row = CsvReader.next(r)) != null) {
                if (row.size() < 3 || row.get(0).isEmpty()) {
                    continue;
                }
                batch.add(new Object[] {row.get(0), substr(row.get(1), 255), row.get(2)});
                if (batch.size() >= 2000) {
                    total += flush("INSERT INTO unspsc_codes (codigo, descripcion, clase) VALUES (?, ?, ?)", batch);
                }
            }
        }
        total += flush("INSERT INTO unspsc_codes (codigo, descripcion, clase) VALUES (?, ?, ?)", batch);
        log.info("UNSPSC v14: {} códigos cargados.", total);
    }

    private void seedUnspscTaxonomy() throws IOException {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM unspsc_taxonomy", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        int total = 0;
        try (BufferedReader r = reader("seed/unspsc_taxonomy.csv")) {
            CsvReader.next(r);
            List<String> row;
            while ((row = CsvReader.next(r)) != null) {
                if (row.size() < 4 || row.get(1).isEmpty()) {
                    continue;
                }
                batch.add(new Object[] {row.get(0), row.get(1), substr(row.get(2), 255), row.get(3).isEmpty() ? null : row.get(3)});
                if (batch.size() >= 1000) {
                    total += flush("INSERT INTO unspsc_taxonomy (nivel, codigo, nombre, parent) VALUES (?, ?, ?, ?)", batch);
                }
            }
        }
        total += flush("INSERT INTO unspsc_taxonomy (nivel, codigo, nombre, parent) VALUES (?, ?, ?, ?)", batch);
        log.info("UNSPSC taxonomía: {} niveles cargados.", total);
    }

    /**
     * Los planes (free, pro, business) son datos base: sin ellos no se puede dar de alta una empresa. Con
     * {@code APP_SEED=false} se crean solo si la tabla está vacía (nunca pisan un plan editado desde el panel).
     */
    private void ensurePlans() {
        try {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM plans", Integer.class);
            if (count == null || count == 0) {
                tx.executeWithoutResult(s -> seedPlans());
                log.info("Planes base creados (la tabla estaba vacía).");
            }
        } catch (Exception e) {
            log.warn("Planes: no se pudieron crear los planes base ({}).", e.getClass().getSimpleName());
        }
    }

    /**
     * El ubigeo es un catálogo, no un dato de prueba: con {@code APP_SEED=false} (producción) también se carga si las tablas
     * están vacías, porque el alta de empresas, las sucursales y las guías lo necesitan. Si ya tienen datos no toca nada.
     */
    private void ensureUbigeo() {
        try {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM ubigeo_distritos", Integer.class);
            if (count == null || count == 0) {
                seedUbigeo();
            }
        } catch (Exception e) {
            // Sin catálogo los selectores quedan vacíos, pero la API debe arrancar igual.
            log.warn("Ubigeo: no se pudo cargar el catálogo ({}).", e.getClass().getSimpleName());
        }
    }

    private void seedUbigeo() throws IOException {
        Map<String, String> regiones = new LinkedHashMap<>();
        Map<String, String[]> provincias = new LinkedHashMap<>();
        Map<String, String[]> distritos = new LinkedHashMap<>();
        try (BufferedReader r = reader("seed/data_ubi.txt")) {
            r.readLine(); // cabecera
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\\|", -1);
                if (p.length < 3) {
                    continue;
                }
                String id = p[0].trim();
                if (id.length() < 6) {
                    continue;
                }
                String[] parts = p[2].split("/");
                String regName = parts.length > 2 ? parts[2].trim() : "";
                String provName = parts.length > 1 ? parts[1].trim() : "";
                String rcode = id.substring(0, 2);
                String pcode = id.substring(0, 4);
                regiones.put(rcode, regName);
                provincias.put(pcode, new String[] {provName, rcode});
                distritos.put(id, new String[] {p[1].trim(), pcode, rcode});
            }
        }
        tx.executeWithoutResult(s -> {
            regiones.forEach((id, nombre) -> jdbc.update(
                    "INSERT INTO ubigeo_regiones (id, nombre) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET nombre = EXCLUDED.nombre", id, nombre));
            provincias.forEach((id, v) -> jdbc.update(
                    "INSERT INTO ubigeo_provincias (id, nombre, region_id) VALUES (?, ?, ?) "
                            + "ON CONFLICT (id) DO UPDATE SET nombre = EXCLUDED.nombre, region_id = EXCLUDED.region_id", id, v[0], v[1]));
            List<Object[]> rows = new ArrayList<>();
            distritos.forEach((id, v) -> rows.add(new Object[] {id, v[0], v[1], v[2]}));
            jdbc.batchUpdate("INSERT INTO ubigeo_distritos (id, nombre, provincia_id, region_id) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (id) DO UPDATE SET nombre = EXCLUDED.nombre, provincia_id = EXCLUDED.provincia_id, "
                    + "region_id = EXCLUDED.region_id", rows);
        });
        log.info("Ubigeo: {} regiones, {} provincias, {} distritos.", regiones.size(), provincias.size(), distritos.size());
    }

    // ------------------------------------------------------------------ empresa demo (BETA)

    void seedDemoTenant() {
        if (tenants.existsByRuc(DEMO_RUC)) {
            return;
        }
        String apiKey = blankToNull(props.demo().apiKey());
        String apiSecret = blankToNull(props.demo().apiSecret());
        boolean generated = apiKey == null || apiSecret == null;
        if (apiKey == null) {
            apiKey = ApiCredentials.newApiKey();
        }
        if (apiSecret == null) {
            apiSecret = ApiCredentials.newApiSecret();
        }

        Tenant t = new Tenant();
        t.setRuc(DEMO_RUC);
        t.setRazonSocial("EMPRESA DEMO S.A.C.");
        t.setNombreComercial("EMPRESA DEMO");
        t.setDireccion("AV. DEMO 123");
        t.setUbigeo("150101");
        t.setDepartamento("LIMA");
        t.setProvincia("LIMA");
        t.setDistrito("LIMA");
        t.setSolUser(EncryptedString.ofPlain("MODDATOS"));
        t.setSolPass(EncryptedString.ofPlain("MODDATOS"));
        t.setEnvironment(Tenant.ENV_BETA);
        t.setApiKey(apiKey);
        t.setApiSecret(EncryptedString.ofPlain(apiSecret));
        t.setPlan("free");
        tenants.save(t);

        String[][] series = {
            {"01", "F001"}, {"03", "B001"}, {"07", "FC01"}, {"07", "BC01"}, {"08", "FD01"}, {"08", "BD01"},
            {"09", "T001"}, {"31", "V001"}, {"20", "R001"}, {"40", "P001"}
        };
        Timestamp now = Timestamp.valueOf(Clock.nowUtc());
        for (String[] s : series) {
            jdbc.update("INSERT INTO series (tenant_id, tipo_documento, serie, correlativo, is_active, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 0, true, ?, ?)", t.getId(), s[0], s[1], now, now);
        }

        if (generated) {
            // Nunca en el log (el prompt prohíbe secretos en logs): se escriben en el almacenamiento privado.
            java.nio.file.Path file = java.nio.file.Path.of(props.storagePath(), "demo-credentials.txt");
            try {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, "RUC=" + DEMO_RUC + "\nX-Api-Key=" + apiKey + "\nX-Api-Secret=" + apiSecret + "\n");
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo escribir " + file, e);
            }
            log.warn("Empresa demo BETA creada (RUC {}). Credenciales generadas en {}", DEMO_RUC, file);
        } else {
            log.info("Empresa demo BETA creada (RUC {}) con las credenciales DEMO_API_KEY/DEMO_API_SECRET.", DEMO_RUC);
        }
    }

    // ------------------------------------------------------------------ utilidades

    private int flush(String sql, List<Object[]> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int n = batch.size();
        jdbc.batchUpdate(sql, batch);
        batch.clear();
        return n;
    }

    private static BufferedReader reader(String classpath) throws IOException {
        return new BufferedReader(new InputStreamReader(new ClassPathResource(classpath).getInputStream(), StandardCharsets.UTF_8));
    }

    private static String substr(String s, int max) {
        return s.codePointCount(0, s.length()) > max ? s.substring(0, s.offsetByCodePoints(0, max)) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
