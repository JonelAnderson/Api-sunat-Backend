package pe.facturacion.api.modules.admin.panel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.retper.RetPerKind;
import pe.facturacion.api.shared.http.Input;

/** Entrada que reciben los servicios de /api/v1 desde las vistas de empresa, secretos quitados y tipos de la ruta. */
class AdminTenantViewsTest {

    @Test
    void paginacion_del_panel_pisa_la_de_v1_con_tope_50() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("records", "500");
        raw.put("numPage", "3");
        raw.put("page", "99");
        raw.put("por_pagina", "100");
        raw.put("per_page", "100");
        raw.put("sunat_status", "rechazado");
        raw.put("stateFilter", "1");
        Input in = AdminTenantViews.toV1Input(raw, AdminPaging.of("500", "3"), null);
        assertThat(in.all()).containsEntry("page", "3").containsEntry("por_pagina", "50").containsEntry("per_page", "50")
                .containsEntry("sunat_status", "rechazado").doesNotContainKeys("records", "numPage", "stateFilter", "textFilter");
        // El mapa original no cambia
        assertThat(raw).containsEntry("page", "99");
    }

    @Test
    void sin_records_usa_10_y_sin_paginacion_no_agrega_page() {
        Input paged = AdminTenantViews.toV1Input(Map.of(), AdminPaging.of(null, null), null);
        assertThat(paged.all()).containsEntry("page", "1").containsEntry("por_pagina", "10");
        Input plain = AdminTenantViews.toV1Input(Map.of("mes", "2026-11", "records", "5"), null, null);
        assertThat(plain.all()).containsOnlyKeys("mes");
    }

    @Test
    void text_filter_pasa_al_parametro_de_busqueda_de_la_vista() {
        Input in = AdminTenantViews.toV1Input(Map.of("textFilter", "  acme  "), AdminPaging.of("10", "1"), "search");
        assertThat(in.all()).containsEntry("search", "acme").doesNotContainKey("textFilter");
        // Si el cliente ya mandó el parámetro propio de /api/v1, se respeta
        Input own = AdminTenantViews.toV1Input(Map.of("textFilter", "x", "buscar", "y"), AdminPaging.of("10", "1"), "buscar");
        assertThat(own.all()).containsEntry("buscar", "y");
        // Sin alias (vistas sin búsqueda por texto) se descarta
        assertThat(AdminTenantViews.toV1Input(Map.of("textFilter", "x"), null, null).all()).isEmpty();
    }

    @Test
    void metadatos_de_auditoria_cortos() {
        assertThat(AdminTenantViews.meta("facturas", "01", 15L, "xml"))
                .containsExactly(Map.entry("vista", "facturas"), Map.entry("tipo", "01"), Map.entry("documento_id", 15L), Map.entry("archivo", "xml"));
        assertThat(AdminTenantViews.meta("sucursales", null, null, null)).containsOnlyKeys("vista");
    }

    @Test
    void el_recurso_de_la_empresa_sale_sin_credenciales_de_sunat() {
        Map<String, Object> recurso = new LinkedHashMap<>();
        recurso.put("id", 1L);
        recurso.put("ruc", "20000000001");
        recurso.put("sol_user", "MODDATOS");
        recurso.put("tiene_sol_pass", true);
        recurso.put("client_id", "cliente-gre");
        recurso.put("tiene_client_secret", true);
        recurso.put("tiene_certificado", true);
        Map<String, Object> admin = AdminTenantConfigController.empresaSinSecretos(recurso);
        assertThat(admin).doesNotContainKeys("sol_user", "client_id")
                .containsKeys("id", "ruc", "tiene_sol_pass", "tiene_client_secret", "tiene_certificado");
    }

    @Test
    void tipos_de_la_ruta() {
        assertThat(AdminTenantDocumentsController.kind("facturas")).isEqualTo(DocumentKind.FACTURA);
        assertThat(AdminTenantDocumentsController.kind("boletas")).isEqualTo(DocumentKind.BOLETA);
        assertThat(AdminTenantDocumentsController.kind("notas-credito")).isEqualTo(DocumentKind.NOTA_CREDITO);
        assertThat(AdminTenantDocumentsController.kind("notas-debito")).isEqualTo(DocumentKind.NOTA_DEBITO);
        assertThatThrownBy(() -> AdminTenantDocumentsController.kind("guias"))
                .isInstanceOfSatisfying(AdminException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThat(AdminTenantOtherDocumentsController.retPerKind("retenciones")).isEqualTo(RetPerKind.RETENCION);
        assertThat(AdminTenantOtherDocumentsController.retPerKind("percepciones")).isEqualTo(RetPerKind.PERCEPCION);
    }
}
