package pe.facturacion.api.modules.admin.panel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocument.SaleItem;

/** {@code datos_edicion} (§12.1): el cuerpo completo del PUT de corrección, armado con lo guardado. */
class AdminEditDataTest {

    static Map<String, Object> guia(String tipo) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", 9L);
        g.put("tipo_documento", tipo);
        g.put("serie", "31".equals(tipo) ? "V001" : "T001");
        g.put("correlativo", 9);
        g.put("fecha_emision", LocalDateTime.of(2026, 11, 23, 9, 0, 0));
        g.put("destinatario_tipo_doc", "6");
        g.put("destinatario_num_doc", "20601234567");
        g.put("destinatario_razon_social", "CLIENTE SAC");
        g.put("cod_traslado", "01");
        g.put("mod_traslado", "02");
        g.put("fecha_traslado", LocalDate.of(2026, 11, 24));
        g.put("fecha_entrega_transportista", null);
        g.put("peso_total", new BigDecimal("12.500"));
        g.put("und_peso_total", "KGM");
        g.put("num_bultos", 2);
        g.put("indicadores", List.of("SUNAT_Envio_IndicadorTrasladoVehiculoM1L"));
        g.put("partida_ubigeo", "150122");
        g.put("partida_direccion", "JR. ALMACEN 200");
        g.put("partida_ruc", "20000000001");
        g.put("partida_cod_local", "0001");
        g.put("llegada_ubigeo", "150101");
        g.put("llegada_direccion", "AV. LIMA 1");
        g.put("llegada_ruc", null);
        g.put("llegada_cod_local", null);
        g.put("transportista", null);
        g.put("vehiculo", Map.of("placa", "ABC123", "tuc", "TUC1234567"));
        g.put("conductor", List.of(Map.of("tipo_doc", "1", "num_doc", "45678912", "nombres", "JUAN", "apellidos", "PEREZ",
                "licencia", "Q45678912")));
        g.put("remitente", Map.of("tipo_doc", "6", "num_doc", "20111111111", "razon_social", "REMITENTE SAC"));
        g.put("doc_relacionado", List.of(Map.of("tipo_codigo", "01", "numero", "F001-15", "ruc_emisor", "20111111111")));
        g.put("datos_subcontratador", null);
        g.put("datos_pagador_flete", Map.of("tipo", "remitente"));
        g.put("autorizacion_especial", null);
        g.put("tercero", null);
        g.put("comprador", null);
        g.put("observacion", "Corregir RUC");
        g.put("items", List.of(Map.of("codigo", "P1", "descripcion", "Producto", "unidad", "NIU", "cantidad", 5)));
        g.put("sunat_status", "rechazado");
        g.put("xml_path", "x.xml");
        return g;
    }

    @Test
    void guia_remitente_trae_todo_lo_que_el_put_necesita_sin_correlativo() {
        Map<String, Object> d = AdminEditData.guide(guia("09"));
        assertThat(d).containsEntry("tipo_documento", "09").containsEntry("serie", "T001").containsEntry("fecha_emision", "2026-11-23")
                .containsEntry("cod_traslado", "01").containsEntry("mod_traslado", "02").containsEntry("fecha_traslado", "2026-11-24")
                .containsEntry("fecha_entrega_transportista", null).containsEntry("peso_total", 12.5).containsEntry("num_bultos", 2L)
                .containsEntry("partida_ruc", "20000000001").containsEntry("partida_cod_local", "0001")
                .containsEntry("indicadores", List.of("SUNAT_Envio_IndicadorTrasladoVehiculoM1L"))
                .containsKeys("doc_relacionado", "vehiculo", "items", "llegada_ruc", "llegada_cod_local", "tercero", "comprador",
                        "datos_subcontratador", "autorizacion_especial", "observacion")
                .doesNotContainKeys("correlativo", "id", "sunat_status", "xml_path", "numero_completo");
        assertThat(d.get("destinatario")).isEqualTo(Map.of("tipo_doc", "6", "num_doc", "20601234567", "razon_social", "CLIENTE SAC"));
        // La lista de conductores va como "conductores" (la columna guarda el objeto o la lista)
        assertThat(d.get("conductor")).isNull();
        assertThat(d.get("conductores")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(1);
    }

    @Test
    void guia_transportista_trae_remitente_y_documentos_y_no_motivo_ni_modalidad() {
        Map<String, Object> g = guia("31");
        g.put("conductor", Map.of("tipo_doc", "1", "num_doc", "45678912", "nombres", "JUAN", "apellidos", "PEREZ", "licencia", "Q1"));
        Map<String, Object> d = AdminEditData.guide(g);
        assertThat(d).containsEntry("tipo_documento", "31").doesNotContainKeys("cod_traslado", "mod_traslado");
        assertThat(d.get("remitente")).isEqualTo(Map.of("tipo_doc", "6", "num_doc", "20111111111", "razon_social", "REMITENTE SAC"));
        assertThat(d.get("doc_relacionado")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(1);
        assertThat(d.get("datos_pagador_flete")).isEqualTo(Map.of("tipo", "remitente"));
        assertThat(d.get("conductor")).isInstanceOf(Map.class);
        assertThat(d.get("conductores")).isNull();
    }

    static SaleItem item(String tip, String precio, String pct) {
        SaleItem i = new SaleItem();
        i.codigo = "P1";
        i.codProductoSunat = "50201706";
        i.descripcion = "Producto";
        i.unidad = "NIU";
        i.cantidad = new BigDecimal("2.0000");
        i.mtoPrecioUnitario = new BigDecimal(precio);
        i.tipAfeIgv = tip;
        i.porcentajeIgv = new BigDecimal(pct);
        return i;
    }

    static double rate(Map<String, Object> item) {
        Object tip = item.get("tip_afe_igv");
        return "10".equals(tip) ? 18 : "17".equals(tip) ? 4 : 0;
    }

    @Test
    void factura_trae_afectacion_tasas_y_descuentos_globales_sin_leyenda() {
        SaleDocument d = new SaleDocument();
        d.kind = DocumentKind.FACTURA;
        d.clientTipoDoc = "6";
        d.clientNumDoc = "20601234567";
        d.clientRazonSocial = "CLIENTE SAC";
        d.formaPago = "Credito";
        d.fechaVencimiento = LocalDate.of(2026, 12, 23);
        d.leyenda = "CIENTO DIECIOCHO CON 00/100 SOLES";
        d.descuentosGlobales = List.of(Map.of("cod_tipo", "02", "monto", 10.0));
        SaleItem gravado = item("10", "118", "18");
        SaleItem exonerado = item("20", "50", "0");
        SaleItem restaurante = item("10", "110", "10");
        restaurante.porcentajeIsc = new BigDecimal("10");
        restaurante.tipSisIsc = "01";
        restaurante.isc = new BigDecimal("2.00");
        SaleItem bolsa = item("10", "1.18", "18");
        bolsa.icbper = new BigDecimal("1.00");
        bolsa.descuento = new BigDecimal("0.50");
        d.items = List.of(gravado, exonerado, restaurante, bolsa);

        Map<String, Object> e = AdminEditData.sale(d, AdminEditDataTest::rate);
        assertThat(e).containsEntry("forma_pago", "Credito").containsEntry("fecha_vencimiento", "2026-12-23")
                .containsEntry("tipo_operacion", "0101").containsEntry("tipo_moneda", "PEN")
                .containsEntry("descuentos_globales", List.of(Map.of("cod_tipo", "02", "monto", 10.0)))
                .doesNotContainKeys("leyenda", "correlativo", "doc_afectado_tipo", "cuotas");
        assertThat(e.get("cliente")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("tipo_doc", "6")
                .containsEntry("num_doc", "20601234567").containsEntry("razon_social", "CLIENTE SAC").containsEntry("direccion", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) e.get("items");
        assertThat(items.get(0)).containsEntry("tip_afe_igv", "10").containsEntry("precio_unitario", 118.0).containsEntry("cantidad", 2.0)
                .containsEntry("cod_producto_sunat", "50201706").containsEntry("porcentaje_igv", null);
        assertThat(items.get(1)).containsEntry("tip_afe_igv", "20").containsEntry("porcentaje_igv", null);
        // Tasa distinta de la del servidor (10 % de restaurantes): se envía; el ISC va por su tasa, no por el monto
        assertThat(items.get(2)).containsEntry("porcentaje_igv", 10.0).containsEntry("porcentaje_isc", 10.0)
                .containsEntry("tip_sis_isc", "01").containsEntry("isc", null);
        // ICBPER sin factor: el monto; descuento por línea sin lista de descuentos
        assertThat(items.get(3)).containsEntry("icbper", 1.0).containsEntry("factor_icbper", null).containsEntry("descuento", 0.5);
    }

    @Test
    void nota_de_credito_trae_el_documento_afectado_y_el_motivo() {
        SaleDocument d = new SaleDocument();
        d.kind = DocumentKind.NOTA_CREDITO;
        d.clientTipoDoc = "6";
        d.clientNumDoc = "20601234567";
        d.clientRazonSocial = "CLIENTE SAC";
        d.docAfectadoTipo = "01";
        d.docAfectadoSerie = "F001";
        d.docAfectadoCorrelativo = "15";
        d.codMotivo = "07";
        d.desMotivo = "Devolución parcial";
        d.items = List.of(item("10", "59", "18"));
        Map<String, Object> e = AdminEditData.sale(d, AdminEditDataTest::rate);
        assertThat(e).containsEntry("doc_afectado_tipo", "01").containsEntry("doc_afectado_serie", "F001")
                .containsEntry("doc_afectado_correlativo", "15").containsEntry("cod_motivo", "07").containsEntry("des_motivo", "Devolución parcial")
                .doesNotContainKeys("forma_pago", "fecha_vencimiento", "tipo_operacion");
        assertThat(e.get("items")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(1);
    }
}
