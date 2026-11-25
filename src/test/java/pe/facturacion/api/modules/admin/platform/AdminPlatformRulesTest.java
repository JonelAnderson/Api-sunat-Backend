package pe.facturacion.api.modules.admin.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.shared.json.PhpJson;

/** Reglas puras de la fase B: validación de planes, catálogo y token de consulta. */
class AdminPlatformRulesTest {

    @Test
    void plan_valido_con_limites_y_funcionalidades() {
        AdminPlanRules.Data d = validate("{\"slug\":\"starter\",\"name\":\"Starter\",\"priceMonthly\":29.9,\"priceYearly\":\"299\","
                + "\"sortOrder\":2,\"limits\":{\"documents_month\":500,\"sucursales\":\"2\",\"vacio\":null},\"features\":[\"facturacion\","
                + "\"boletas\",\"facturacion\"],\"durationDays\":30,\"isActive\":true}", null);
        assertThat(d.slug()).isEqualTo("starter");
        assertThat(d.priceMonthly()).isEqualByComparingTo("29.90");
        assertThat(d.priceYearly()).isEqualByComparingTo("299.00");
        assertThat(d.limits()).containsExactly(Map.entry("documents_month", 500), Map.entry("sucursales", 2));
        assertThat(d.features()).containsExactly("facturacion", "boletas");
        assertThat(d.durationDays()).isEqualTo(30);
        assertThat(d.unlimited()).isFalse();
        assertThat(d.active()).isTrue();
    }

    @Test
    void plan_ilimitado_fuerza_menos_uno_y_valores_por_defecto() {
        AdminPlanRules.Data d = validate("{\"slug\":\"vip\",\"name\":\"VIP\",\"priceMonthly\":0,\"isUnlimited\":true}", null);
        assertThat(d.limits()).containsEntry("documents_month", -1).containsEntry("internal_documents_month", -1);
        assertThat(d.priceYearly()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(d.sortOrder()).isZero();
        assertThat(d.active()).isTrue();
        assertThat(d.durationDays()).isNull();
    }

    @Test
    void errores_de_validacion_por_campo() {
        assertThatThrownBy(() -> validate("{\"slug\":\"Con Espacio\",\"priceMonthly\":-1,\"limits\":{\"Mal-Clave\":1,\"x\":-2,\"y\":1.5},"
                + "\"features\":[\"ok\",\"No Vale\"],\"durationDays\":0,\"isActive\":\"tal vez\"}", null))
                .isInstanceOf(AdminException.class).satisfies(e -> {
                    AdminException a = (AdminException) e;
                    assertThat(a.status()).isEqualTo(200);
                    assertThat(a.errors()).extracting(m -> m.get("propertyName"))
                            .contains("slug", "name", "priceMonthly", "limits.Mal-Clave", "limits.x", "limits.y", "features", "durationDays",
                                    "isActive");
                });
        assertThatThrownBy(() -> validate("{\"slug\":\"a\",\"name\":\"A\",\"priceMonthly\":1.234}", null)).isInstanceOf(AdminException.class);
        assertThatThrownBy(() -> validate("{\"slug\":\"a\",\"name\":\"A\",\"priceMonthly\":1,\"limits\":[1,2]}", null))
                .isInstanceOf(AdminException.class);
    }

    @Test
    void los_planes_del_sistema_no_cambian_de_slug() {
        assertThatThrownBy(() -> validate("{\"slug\":\"gratis\",\"name\":\"Free\",\"priceMonthly\":0}", "free"))
                .isInstanceOf(AdminException.class).hasMessageContaining("free, pro, business");
        assertThat(validate("{\"slug\":\"free\",\"name\":\"Gratis\",\"priceMonthly\":0}", "free").name()).isEqualTo("Gratis");
        // Un plan propio sí puede cambiar de slug
        assertThat(validate("{\"slug\":\"starter-2\",\"name\":\"S\",\"priceMonthly\":0}", "starter").slug()).isEqualTo("starter-2");
        assertThat(AdminPlanRules.SYSTEM_SLUGS).containsExactlyInAnyOrder("free", "pro", "business");
    }

    @Test
    void catalogo_con_documentos_del_mes() {
        Map<String, Object> c = AdminPlanCatalog.asMap();
        assertThat(c).containsOnlyKeys("limits", "features");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> limits = (List<Map<String, Object>>) c.get("limits");
        assertThat(limits).extracting(m -> m.get("key")).contains("documents_month", "sucursales").doesNotHaveDuplicates();
        assertThat(limits.get(0).keySet()).containsExactly("group", "key", "label", "help");
    }

    @Test
    void el_token_de_consulta_es_imprimible_sin_espacios() {
        assertThat(AdminSettingsController.TOKEN.matcher("apis-net-pe_TOKEN.123").matches()).isTrue();
        assertThat(AdminSettingsController.TOKEN.matcher("corto").matches()).isFalse();
        assertThat(AdminSettingsController.TOKEN.matcher("con espacio 12345").matches()).isFalse();
        assertThat(AdminSettingsController.TOKEN.matcher("salto\r\nX-Inyectada: 1").matches()).isFalse();
    }

    private static AdminPlanRules.Data validate(String json, String currentSlug) {
        Map<String, Object> raw = new LinkedHashMap<>(PhpJson.decodeObject(json));
        return AdminPlanRules.validate(AdminBody.of(raw), raw, currentSlug);
    }
}
