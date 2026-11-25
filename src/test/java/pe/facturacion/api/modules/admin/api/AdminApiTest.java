package pe.facturacion.api.modules.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pe.facturacion.api.shared.json.PhpJson;

/** Paginación, sobre JSON camelCase, fechas ISO, cuerpo y validación del panel. */
class AdminApiTest {

    @ParameterizedTest(name = "records={0} numPage={1} → {2} / {3}")
    @CsvSource(value = {
        "NULL, NULL, 10, 1",
        "'', '', 10, 1",
        "0, 0, 10, 1",
        "-5, -3, 10, 1",
        "abc, x, 10, 1",
        "1, 1, 1, 1",
        "25, 3, 25, 3",
        "50, 2, 50, 2",
        "51, 2, 50, 2",
        "100000, 7, 50, 7",
        "' 20 ', ' 4 ', 20, 4",
        "10, 99999999999, 10, 1000000"
    }, nullValues = "NULL")
    void paginacion_acotada(String records, String numPage, int expectedRecords, int expectedPage) {
        AdminPaging p = AdminPaging.of(records, numPage);
        assertThat(p.records()).isEqualTo(expectedRecords);
        assertThat(p.numPage()).isEqualTo(expectedPage);
        assertThat(p.offset()).isEqualTo((long) (expectedPage - 1) * expectedRecords);
    }

    @Test
    void sobre_camelcase_con_fechas_iso_utc() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("razonSocial", "EMPRESA S.A.C.");
        row.put("createdAt", AdminFormat.instant(LocalDateTime.of(2026, 11, 23, 15, 4, 5, 123)));
        row.put("suspensionReason", null);
        String json = PhpJson.encode(AdminResponse.body(true, List.of(), List.of(row), 41, List.of()));
        assertThat(json).isEqualTo("{\"succeeded\":true,\"messages\":[],\"data\":[{\"razonSocial\":\"EMPRESA S.A.C.\","
                + "\"createdAt\":\"2026-11-23T15:04:05Z\",\"suspensionReason\":null}],\"totalRecords\":41,\"errors\":[]}");
    }

    @Test
    void error_de_validacion_con_property_name_y_error_message() {
        AdminValidation v = new AdminValidation().add("email", "El correo no es válido.");
        assertThatThrownBy(v::throwIfAny).isInstanceOfSatisfying(AdminException.class, e -> {
            assertThat(e.status()).isEqualTo(200);
            assertThat(e.messages()).containsExactly("El correo no es válido.");
            assertThat(e.errors()).containsExactly(Map.of("propertyName", "email", "errorMessage", "El correo no es válido."));
        });
        String json = PhpJson.encode(AdminResponse.body(false, List.of("x"), null, 0, List.of(AdminResponse.fieldError("email", "x"))));
        assertThat(json).contains("\"errors\":[{\"propertyName\":\"email\",\"errorMessage\":\"x\"}]").contains("\"succeeded\":false");
    }

    @Test
    void formatos_de_fecha() {
        assertThat(AdminFormat.instant(Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 3, 4, 5)))).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(AdminFormat.instant((Object) null)).isNull();
        assertThat(AdminFormat.day(LocalDateTime.of(2026, 11, 23, 23, 59, 59))).isEqualTo("2026-11-23");
        assertThat(AdminFormat.parseDay("startDate", "2026-11-01")).hasToString("2026-11-01");
        assertThat(AdminFormat.parseDay("startDate", " ")).isNull();
        assertThatThrownBy(() -> AdminFormat.parseDay("startDate", "01/11/2026")).isInstanceOf(AdminException.class);
    }

    @Test
    void like_escapa_comodines() {
        assertThat(AdminFormat.likeContains("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
    }

    @Test
    void cuerpo_json_sin_recortar_contrasenas() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("password", " con espacios ");
        m.put("activo", "false");
        m.put("flag", 1);
        m.put("max", 500);
        m.put("decimal", new java.math.BigDecimal("1.5"));
        m.put("texto", "  ");
        AdminBody b = AdminBody.of(m);
        assertThat(b.raw("password")).isEqualTo(" con espacios ");
        assertThat(b.text("password")).isEqualTo("con espacios");
        assertThat(b.text("texto")).isNull();
        assertThat(b.bool("activo")).isFalse();
        assertThat(b.bool("flag")).isTrue();
        assertThat(b.bool("max")).isNull();
        assertThat(b.integer("max")).isEqualTo(500L);
        assertThat(b.integer("decimal")).isNull();
        assertThat(b.integer("nada")).isNull();
    }

    @Test
    void correos() {
        assertThat(AdminValidation.isEmail("ana@example.com")).isTrue();
        assertThat(AdminValidation.isEmail("ana@example")).isFalse();
        assertThat(AdminValidation.isEmail("ana example.com")).isFalse();
        assertThat(AdminValidation.isEmail(null)).isFalse();
    }
}
