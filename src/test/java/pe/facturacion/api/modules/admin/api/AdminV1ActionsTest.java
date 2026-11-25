package pe.facturacion.api.modules.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Escrituras de /api/v1 traducidas al sobre del panel (fases C y D) y datos que la auditoría toma del resultado. */
class AdminV1ActionsTest {

    @Test
    void exito_con_datos_mensaje_y_advertencias() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("mensaje", "Guía de Remisión Transportista creada y encolada para envío a SUNAT.");
        body.put("datos", Map.of("id", 9, "numero_completo", "V001-000009", "sunat", Map.of("estado", "enviado")));
        body.put("advertencias", List.of(Map.of("codigo", "grt_beta_no_soportado", "mensaje", "SUNAT beta no valida completamente…")));
        ResponseEntity<Object> r = AdminV1Actions.envelope(ResponseEntity.status(201).body(body));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) r.getBody();
        assertThat(env).containsEntry("succeeded", true);
        assertThat((List<?>) env.get("messages")).hasSize(2);
        Object data = env.get("data");
        assertThat(AdminV1Actions.documentId(data)).isEqualTo(9L);
        assertThat(AdminV1Actions.documentNumber(data)).isEqualTo("V001-000009");
        assertThat(AdminV1Actions.sunatStatus(data)).isEqualTo("enviado");
    }

    @Test
    void respuesta_sin_datos_lleva_el_resto_del_cuerpo() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "pendiente");
        body.put("mensaje", "La guía todavía no tiene ticket SUNAT.");
        body.put("guia", Map.of("id", 9));
        body.put("siguiente_accion", "Use POST /guias-remision/9/enviar");
        Object data = AdminV1Actions.data(ResponseEntity.status(202).body(body));
        assertThat(data).isEqualTo(Map.of("guia", Map.of("id", 9), "siguiente_accion", "Use POST /guias-remision/9/enviar"));
    }

    @Test
    void errores_como_las_vistas() {
        Map<String, Object> cupo = new LinkedHashMap<>();
        cupo.put("estado", "error");
        cupo.put("mensaje", "Has alcanzado el limite de documentos SUNAT (30/30 por mes).");
        cupo.put("codigo_error", "limite_alcanzado");
        assertThatThrownBy(() -> AdminV1Actions.envelope(ResponseEntity.status(429).body(cupo))).isInstanceOf(AdminException.class)
                .satisfies(e -> {
                    assertThat(((AdminException) e).status()).as("el cupo del plan no es un 429 del panel").isEqualTo(200);
                    assertThat(((AdminException) e).messages()).containsExactly("Has alcanzado el limite de documentos SUNAT (30/30 por mes).");
                });
        assertThatThrownBy(() -> AdminV1Actions.envelope(ResponseEntity.status(404).body(Map.of("estado", "error", "mensaje", "Recurso no encontrado."))))
                .satisfies(e -> assertThat(((AdminException) e).status()).isEqualTo(404));
        assertThatThrownBy(() -> AdminV1Actions.envelope(ResponseEntity.status(202).body(Map.of("estado", "error", "mensaje", "SUNAT no responde"))))
                .satisfies(e -> assertThat(((AdminException) e).status()).isEqualTo(200));
        assertThat(AdminV1Actions.ok(ResponseEntity.status(422).body(Map.of("estado", "error")))).isFalse();
        assertThat(AdminV1Actions.ok(ResponseEntity.ok(Map.of("estado", "exito")))).isTrue();
    }

    @Test
    void identificadores_de_anulaciones_y_resumenes() {
        assertThat(AdminV1Actions.documentId(Map.of("id_anulacion", 5, "identifier", "RA-20261124-001"))).isEqualTo(5L);
        assertThat(AdminV1Actions.documentNumber(Map.of("id_anulacion", 5, "identifier", "RA-20261124-001"))).isEqualTo("RA-20261124-001");
        assertThat(AdminV1Actions.documentId(Map.of("id_resumen", 3))).isEqualTo(3L);
        assertThat(AdminV1Actions.documentId(Map.of("voided_id", 7))).isEqualTo(7L);
        assertThat(AdminV1Actions.sunatStatus(Map.of("estado_sunat", "enviado"))).isEqualTo("enviado");
        assertThat(AdminV1Actions.sunatStatus(Map.of("sunat_status", "aceptado"))).isEqualTo("aceptado");
        assertThat(AdminV1Actions.documentId(List.of())).isNull();
    }

    @Test
    void motivo_de_las_acciones() {
        assertThat(new AdminValidation().reason(null, true).hasErrors()).isTrue();
        assertThat(new AdminValidation().reason("  ", true).hasErrors()).isTrue();
        assertThat(new AdminValidation().reason(null, false).hasErrors()).isFalse();
        assertThat(new AdminValidation().reason("x".repeat(401), false).hasErrors()).isTrue();
        assertThat(new AdminValidation().reason("Cliente pidió corregir el RUC", true).hasErrors()).isFalse();
    }
}
