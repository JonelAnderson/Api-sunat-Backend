package pe.facturacion.api.modules.admin.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.retper.RetPerKind;

/** Segmentos de la URL y cuerpos que el panel arma para los servicios de /api/v1 al anular (fase D). */
class AdminDocumentRulesTest {

    @Test
    void segmentos_de_la_url() {
        assertThat(AdminDocumentRules.saleKind("facturas")).isEqualTo(DocumentKind.FACTURA);
        assertThat(AdminDocumentRules.saleKind("boletas")).isEqualTo(DocumentKind.BOLETA);
        assertThat(AdminDocumentRules.saleKind("notas-credito")).isEqualTo(DocumentKind.NOTA_CREDITO);
        assertThat(AdminDocumentRules.saleKind("notas-debito")).isEqualTo(DocumentKind.NOTA_DEBITO);
        assertThat(AdminDocumentRules.retPerKind("retenciones")).isEqualTo(RetPerKind.RETENCION);
        assertThat(AdminDocumentRules.retPerKind("percepciones")).isEqualTo(RetPerKind.PERCEPCION);
        assertThatThrownBy(() -> AdminDocumentRules.saleKind("cotizaciones")).isInstanceOf(AdminException.class)
                .satisfies(e -> assertThat(((AdminException) e).status()).isEqualTo(404));
        assertThatThrownBy(() -> AdminDocumentRules.retPerKind("facturas")).isInstanceOf(AdminException.class);
    }

    @Test
    void baja_de_factura_con_la_fecha_de_emision_del_comprobante() {
        Map<String, Object> ra = AdminDocumentRules.raPayload(DocumentKind.NOTA_CREDITO, "FC01", 4, LocalDate.of(2026, 11, 23), "Error en el RUC");
        assertThat(ra).containsEntry("fecha_generacion", "2026-11-23").doesNotContainKey("fecha_comunicacion");
        assertThat(ra.get("detalles")).isEqualTo(List.of(Map.of("tipo_documento", "07", "serie", "FC01", "correlativo", "4", "motivo",
                "Error en el RUC")));
    }

    @Test
    void anulacion_de_boleta_por_resumen() {
        Map<String, Object> rc = AdminDocumentRules.rcPayload(40, LocalDate.of(2026, 11, 22), "Venta duplicada");
        assertThat(rc).containsEntry("fecha_resumen", "2026-11-22");
        assertThat(rc.get("anular")).isEqualTo(List.of(Map.of("id", 40L, "motivo", "Venta duplicada", "tipo_documento", "03")));
        assertThat(AdminDocumentRules.isSummaryVoid(rc)).isTrue();
    }

    @Test
    void reversion_de_retencion() {
        Map<String, Object> rr = AdminDocumentRules.rrPayload(RetPerKind.RETENCION, "R001", 2, LocalDate.of(2026, 11, 20), "Pago anulado");
        assertThat(rr).containsEntry("fecha_generacion", "2026-11-20");
        assertThat(rr.get("detalles")).isEqualTo(List.of(Map.of("tipo_documento", "20", "serie", "R001", "correlativo", "2", "motivo",
                "Pago anulado")));
    }

    @Test
    void un_resumen_de_envio_no_es_anulacion() {
        assertThat(AdminDocumentRules.isSummaryVoid(Map.of("fecha_resumen", "2026-11-24"))).isFalse();
        assertThat(AdminDocumentRules.isSummaryVoid(Map.of("anular", List.of()))).isFalse();
        assertThat(AdminDocumentRules.isSummaryVoid(Map.of("anular", Map.of()))).isFalse();
        assertThat(AdminDocumentRules.isSummaryVoid(Map.of("anular", List.of(Map.of("id", 1))))).isTrue();
    }
}
