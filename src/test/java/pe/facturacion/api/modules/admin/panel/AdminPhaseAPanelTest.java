package pe.facturacion.api.modules.admin.panel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.shared.http.Input;

/** Lógica pura de la fase A del panel: filtros de la vista unificada, topes del ZIP, métricas y auditoría de SUNAT. */
class AdminPhaseAPanelTest {

    // ------------------------------------------------------------------ vista unificada

    @Test
    void tipos_y_estados_validos() {
        assertThat(AdminAllDocuments.parseTipos("01, 03,rc,ra")).containsExactly("01", "03", "RC", "RA");
        assertThat(AdminAllDocuments.parseTipos(null)).isEmpty();
        assertThat(AdminAllDocuments.parseTipos(" ")).isEmpty();
        assertThatThrownBy(() -> AdminAllDocuments.parseTipos("01,99")).isInstanceOf(AdminException.class)
                .satisfies(e -> assertThat(((AdminException) e).status()).isEqualTo(200));
        assertThat(AdminAllDocuments.parseEstado("Observado")).isEqualTo("observado");
        assertThat(AdminAllDocuments.parseEstado("")).isNull();
        assertThatThrownBy(() -> AdminAllDocuments.parseEstado("borrado")).isInstanceOf(AdminException.class);
    }

    @Test
    void cada_tipo_abre_su_vista_de_empresa() {
        assertThat(AdminAllDocuments.tipo("01").vista()).isEqualTo("facturas");
        assertThat(AdminAllDocuments.tipo("31").vista()).isEqualTo("guias-remision");
        assertThat(AdminAllDocuments.tipo("04").vista()).isEqualTo("liquidaciones-compra");
        assertThat(AdminAllDocuments.tipo("RR").vista()).isEqualTo("anulaciones");
        assertThat(AdminAllDocuments.tipo("XX")).isNull();
        assertThat(AdminAllDocuments.TIPOS).extracting(AdminAllDocuments.Tipo::codigo).doesNotHaveDuplicates().hasSize(12);
    }

    @Test
    void las_diez_fuentes_del_union_tienen_las_mismas_21_columnas() {
        assertThat(AdminAllDocuments.SOURCES).hasSize(10);
        for (String s : AdminAllDocuments.SOURCES) {
            String select = s.substring("SELECT ".length(), s.indexOf(" FROM "));
            assertThat(topLevelCommas(select)).as(s).isEqualTo(20);
            assertThat(s).doesNotContain("xml_content AS").doesNotContain("cdr_content AS").doesNotContain("_path AS");
        }
    }

    @Test
    void filtros_parametrizados_y_texto_escapado() {
        List<Object> args = new ArrayList<>();
        AdminAllDocuments.Filter f = new AdminAllDocuments.Filter(12L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                Set.of("01"), "observado", "f0", 3L, "50%_x");
        String where = AdminAllDocuments.where(f, args);
        assertThat(where).contains("x.tenant_id = ?", "x.fecha_emision >= ?", "x.fecha_emision < ?", "x.tipo IN (?)",
                "x.estado = 'aceptado' AND x.observado", "x.sucursal_id = ?").doesNotContain("50%").doesNotContain("t.ruc");
        assertThat(args).contains(12L, LocalDate.of(2026, 11, 1).atStartOfDay(), LocalDate.of(2026, 12, 1).atStartOfDay(), "01", "%F0%", 3L,
                "%50\\%\\_X%", "%50\\%\\_x%");
        // Sin empresa fija también busca por RUC / razón social del emisor y la descripción de SUNAT
        List<Object> global = new ArrayList<>();
        assertThat(AdminAllDocuments.where(new AdminAllDocuments.Filter(null, null, null, Set.of(), "rechazado", null, null, "ab"), global))
                .contains("t.ruc LIKE ?", "x.sunat_description", "x.estado = ?").doesNotContain("x.tenant_id");
        assertThat(global).first().isEqualTo("rechazado");
    }

    @Test
    void orden_solo_de_la_lista_blanca() {
        assertThat(AdminAllDocuments.ORDER).containsKeys("fechaEmision", "createdAt", "sentAt", "total", "tipo", "serie", "numero", "estado");
        assertThat(AdminAllDocuments.ORDER.values()).allMatch(v -> v.startsWith("x.") || v.startsWith("coalesce(x."));
    }

    @Test
    void ventana_por_defecto_de_30_dias() {
        LocalDate[] r = AdminAllDocuments.lastDays(LocalDate.of(2026, 11, 24), 30);
        assertThat(r[0]).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(r[1]).isEqualTo(LocalDate.of(2026, 11, 24));
    }

    // ------------------------------------------------------------------ ZIP

    @Test
    void topes_del_zip() {
        assertThat(AdminZipLimits.rangeError("2026-11-01", "2026-11-30")).isNull();
        assertThat(AdminZipLimits.rangeError("2026-10-01", "2026-10-31")).isNull();
        assertThat(AdminZipLimits.rangeError("2026-10-01", "2026-11-01")).contains("31 días");
        // Fechas mal escritas o invertidas: las valida la API con su propio mensaje
        assertThat(AdminZipLimits.rangeError("2026/11/01", "2026-11-30")).isNull();
        assertThat(AdminZipLimits.rangeError("2026-11-30", "2026-11-01")).isNull();
        assertThat(AdminZipLimits.rangeError(null, "")).isNull();

        assertThat(AdminZipLimits.sizeError(AdminZipLimits.MAX_FILES, AdminZipLimits.MAX_BYTES)).isNull();
        assertThat(AdminZipLimits.sizeError(AdminZipLimits.MAX_FILES + 1, 10)).contains("2001 archivos");
        assertThat(AdminZipLimits.sizeError(1, AdminZipLimits.MAX_BYTES + 1)).contains("200 MB");
    }

    // ------------------------------------------------------------------ tablero

    @Test
    void crecimiento_y_etiquetas_del_tablero() {
        assertThat(AdminDashboardMetrics.growth(150, 100)).isEqualTo(50.0);
        assertThat(AdminDashboardMetrics.growth(1, 3)).isEqualTo(-66.7);
        assertThat(AdminDashboardMetrics.growth(10, 0)).isNull();
        assertThat(AdminDashboardMetrics.label("tax_regime", "rus")).isEqualTo("Nuevo RUS");
        assertThat(AdminDashboardMetrics.label("environment", "production")).isEqualTo("Producción");
        assertThat(AdminDashboardMetrics.label("plan", "business")).isEqualTo("Business");
        assertThat(AdminDashboardMetrics.label("plan", null)).isEqualTo("Sin definir");
    }

    // ------------------------------------------------------------------ consultas en vivo

    @Test
    void la_auditoria_de_la_consulta_sunat_no_guarda_el_cdr_ni_credenciales() {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("success", true);
        datos.put("code", "0");
        datos.put("accepted", true);
        datos.put("cdr_zip", "UEsDBBQAAAAI...");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("datos", datos);
        Input in = new Input(new LinkedHashMap<>(Map.of("tipo_documento", "01", "serie", "F001", "correlativo", "15", "sol_pass", "x")));
        Map<String, Object> meta = AdminTenantSunatController.meta(in, List.of("tipo_documento", "serie", "correlativo"),
                ResponseEntity.ok(body));
        assertThat(meta).containsEntry("tipo_documento", "01").containsEntry("serie", "F001").containsEntry("http", 200)
                .containsEntry("code", "0").containsEntry("accepted", true).doesNotContainKeys("cdr_zip", "sol_pass");

        Map<String, Object> error = Map.of("estado", "error", "mensaje", "SUNAT rechazó las credenciales");
        assertThat(AdminTenantSunatController.meta(in, List.of("serie"), ResponseEntity.status(401).body(error)))
                .containsEntry("http", 401).doesNotContainKey("mensaje");
    }

    @Test
    void el_parametro_inline_del_pdf() {
        org.springframework.mock.web.MockHttpServletRequest r = new org.springframework.mock.web.MockHttpServletRequest();
        assertThat(AdminTenantViews.inline(r)).isNull();
        r.setParameter("inline", "1");
        assertThat(AdminTenantViews.inline(r)).isTrue();
        r.setParameter("inline", "false");
        assertThat(AdminTenantViews.inline(r)).isFalse();
        r.setParameter("inline", "quizas");
        assertThat(AdminTenantViews.inline(r)).isNull();
        // No pasa a los servicios de /api/v1
        assertThat(AdminTenantViews.ADMIN_PARAMS).contains("inline");
    }

    /** Comas fuera de paréntesis (separan columnas del SELECT). */
    private static int topLevelCommas(String s) {
        int depth = 0;
        int n = 0;
        boolean quote = false;
        for (char c : s.toCharArray()) {
            if (c == '\'') {
                quote = !quote;
            } else if (!quote && c == '(') {
                depth++;
            } else if (!quote && c == ')') {
                depth--;
            } else if (!quote && depth == 0 && c == ',') {
                n++;
            }
        }
        return n;
    }
}
