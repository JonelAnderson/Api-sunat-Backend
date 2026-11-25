package pe.facturacion.api.modules.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.validation.ValidationException;

/** Conversión de las respuestas de /api/v1 al sobre del panel (vistas de empresa), sin tocar el recurso. */
class AdminV1ResponsesTest {

    @Test
    @SuppressWarnings("unchecked")
    void detalle_lleva_el_recurso_tal_cual() {
        Map<String, Object> recurso = map("id", 7L, "numero_completo", "F001-00000007", "sunat", map("estado", "aceptado"));
        Map<String, Object> body = (Map<String, Object>) AdminV1Responses.data(ApiResponse.success(recurso)).getBody();
        assertThat(body).containsEntry("succeeded", true).containsEntry("totalRecords", 0L).containsEntry("messages", List.of());
        assertThat(body.get("data")).isSameAs(recurso);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listado_con_datos_y_paginacion_dentro_de_datos() {
        List<Object> filas = List.of(map("id", 1L), map("id", 2L));
        ResponseEntity<Object> v1 = ApiResponse.success(map("datos", filas, "paginacion",
                map("pagina_actual", 1, "ultima_pagina", 5, "por_pagina", 2, "total", 9L)));
        Map<String, Object> body = (Map<String, Object>) AdminV1Responses.page(v1).getBody();
        assertThat(body.get("data")).isEqualTo(filas);
        assertThat(body).containsEntry("totalRecords", 9L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listado_con_paginacion_o_meta_en_la_raiz_y_sin_paginar() {
        Map<String, Object> resumenes = new LinkedHashMap<>();
        resumenes.put("estado", "exito");
        resumenes.put("datos", List.of(map("id", 3L)));
        resumenes.put("paginacion", map("total", 31L, "pagina_actual", 1, "ultima_pagina", 4));
        assertThat(((Map<String, Object>) AdminV1Responses.page(ResponseEntity.ok(resumenes)).getBody())).containsEntry("totalRecords", 31L);

        Map<String, Object> anulaciones = new LinkedHashMap<>();
        anulaciones.put("estado", "exito");
        anulaciones.put("datos", List.of(map("id", 4L)));
        anulaciones.put("meta", map("pagina_actual", 1, "por_pagina", 10, "total", 12L, "ultima_pagina", 2));
        assertThat(((Map<String, Object>) AdminV1Responses.page(ResponseEntity.ok(anulaciones)).getBody())).containsEntry("totalRecords", 12L);

        // Sucursales / series: lista sin paginar → totalRecords = tamaño
        Map<String, Object> sinPaginar = (Map<String, Object>) AdminV1Responses.page(ApiResponse.success(List.of(1, 2, 3))).getBody();
        assertThat(sinPaginar).containsEntry("totalRecords", 3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagos_sin_estado_van_completos_en_data() {
        Map<String, Object> pagos = map("datos", List.of(), "estado_pago", "pendiente", "monto_pagado", 0.0, "total_documento", 118.0);
        Map<String, Object> body = (Map<String, Object>) AdminV1Responses.whole(ResponseEntity.ok(pagos)).getBody();
        assertThat(body.get("data")).isEqualTo(pagos);
    }

    @Test
    void error_404_sigue_siendo_404_y_422_es_200_con_errors() {
        assertThatThrownBy(() -> AdminV1Responses.data(ApiResponse.error("XML no disponible", 404)))
                .isInstanceOfSatisfying(AdminException.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.messages()).containsExactly("XML no disponible");
                });
        Map<String, Object> errores = map("fecha_desde", List.of("El campo fecha desde es obligatorio."));
        assertThatThrownBy(() -> AdminV1Responses.data(ApiResponse.error("Error de validación", 422, errores)))
                .isInstanceOfSatisfying(AdminException.class, e -> {
                    assertThat(e.status()).isEqualTo(200);
                    assertThat(e.messages()).containsExactly("El campo fecha desde es obligatorio.");
                    assertThat(e.errors()).containsExactly(Map.of("propertyName", "fecha_desde",
                            "errorMessage", "El campo fecha desde es obligatorio."));
                });
        assertThatThrownBy(() -> AdminV1Responses.page(ApiResponse.error("Fallo (ref: x)", 500)))
                .isInstanceOfSatisfying(AdminException.class, e -> assertThat(e.status()).isEqualTo(500));
        // Error con HTTP 200 pero estado "error" (formato Laravel)
        assertThatThrownBy(() -> AdminV1Responses.data(ResponseEntity.ok(map("estado", "error", "mensaje", "No."))))
                .isInstanceOfSatisfying(AdminException.class, e -> assertThat(e.status()).isEqualTo(200));
    }

    @Test
    void descarga_mismos_bytes_y_cabeceras_sin_cache() {
        ResponseEntity<Object> v1 = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/xml")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"F001-00000001.xml\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body("<Invoice>ñ</Invoice>");
        ResponseEntity<Object> r = AdminV1Responses.file(v1);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("<Invoice>ñ</Invoice>".getBytes(StandardCharsets.UTF_8));
        assertThat(r.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/xml");
        assertThat(r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"F001-00000001.xml\"");
        assertThat(r.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(r.getHeaders().getContentLength()).isEqualTo("<Invoice>ñ</Invoice>".getBytes(StandardCharsets.UTF_8).length);

        byte[] zip = {0x50, 0x4b, 3, 4};
        ResponseEntity<Object> cdr = AdminV1Responses.file(ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/zip").body(zip));
        assertThat(cdr.getBody()).isEqualTo(zip);

        assertThatThrownBy(() -> AdminV1Responses.file(ApiResponse.error("CDR no disponible", 404)))
                .isInstanceOfSatisfying(AdminException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void quitar_y_elegir_claves_sin_modificar_el_original() {
        Map<String, Object> recurso = map("id", 1L, "sol_user", "USUARIO", "client_id", "abc", "ruc", "20000000001");
        assertThat(AdminV1Responses.without(recurso, List.of("sol_user", "client_id"))).containsOnlyKeys("id", "ruc");
        assertThat(recurso).containsKey("sol_user");
        assertThat(AdminV1Responses.pick(recurso, List.of("ruc", "id", "no_existe")).keySet()).containsExactly("ruc", "id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void excepciones_de_la_api_con_el_sobre_del_panel() {
        AdminExceptionHandler handler = new AdminExceptionHandler();
        ResponseEntity<Object> notFound = handler.handleApi(ApiException.notFound());
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat((Map<String, Object>) notFound.getBody()).containsEntry("succeeded", false)
                .containsEntry("messages", List.of("Recurso no encontrado."));
        ResponseEntity<Object> invalid = handler.handleValidation(ValidationException.single("periodo", "El campo periodo es obligatorio."));
        assertThat(invalid.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Object>) ((Map<String, Object>) invalid.getBody()).get("errors"))
                .containsExactly(Map.of("propertyName", "periodo", "errorMessage", "El campo periodo es obligatorio."));
    }

    @Test
    void descarga_inline_o_attachment_a_pedido_del_panel() {
        ResponseEntity<Object> v1 = ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"F001-000015.pdf\"").body(new byte[] {1, 2, 3});
        ResponseEntity<Object> attach = AdminV1Responses.file(v1, false);
        assertThat(attach.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"F001-000015.pdf\"");
        assertThat(attach.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(attach.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(AdminV1Responses.file(v1, true).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline; filename=\"F001-000015.pdf\"");
        assertThat(AdminV1Responses.file(v1, null).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline; filename=\"F001-000015.pdf\"");
        assertThat(AdminV1Responses.disposition(null, true)).isEqualTo("inline");
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
