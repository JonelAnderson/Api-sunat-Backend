package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.support.IntegrationTest;

/** Sucursales, clientes y series: contrato de API-PRO. */
class ConfiguracionIT extends IntegrationTest {

    @Autowired
    TenantRepository tenants;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> datos(HttpResponse<String> r) {
        return (Map<String, Object>) PhpJson.decodeObject(r.body()).get("datos");
    }

    private static long id(HttpResponse<String> r) {
        return ((Number) datos(r).get("id")).longValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sucursales() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20200000001");
        HttpResponse<String> bad = send("POST", "/api/v1/sucursales", "{\"nombre\":\"Sede\",\"cod_local\":\"ab12\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\"}", auth);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(bad.body()).contains("\"cod_local\":[\"El c\\u00f3digo de local debe ser 4 d\\u00edgitos (ej: 0000, 0001).\"]");

        HttpResponse<String> first = send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"Principal\",\"cod_local\":\"0000\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\"}", auth);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.body()).isEqualTo("{\"estado\":\"exito\",\"mensaje\":\"Creado exitosamente\",\"datos\":{\"id\":" + id(first)
                + ",\"nombre\":\"Principal\",\"cod_local\":\"0000\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\",\"telefono\":null,"
                + "\"email\":null,\"es_principal\":true,\"activo\":true,\"total_series\":0}}");

        HttpResponse<String> dup = send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"Otra\",\"cod_local\":\"0000\",\"direccion\":\"Av 2\",\"ubigeo\":\"150101\"}", auth);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(dup.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"El c\\u00f3digo de local 0000 ya existe.\"}");

        HttpResponse<String> second = send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"Anexo\",\"cod_local\":\"0001\",\"direccion\":\"Av 2\",\"ubigeo\":\"150101\",\"es_principal\":true}", auth);
        assertThat(datos(second).get("es_principal")).isEqualTo(true);
        List<Map<String, Object>> list = (List<Map<String, Object>>) PhpJson.decodeObject(get("/api/v1/sucursales", auth).body()).get("datos");
        assertThat(list).extracting(s -> s.get("nombre")).containsExactly("Anexo", "Principal");

        HttpResponse<String> delPrincipal = send("DELETE", "/api/v1/sucursales/" + id(second), null, auth);
        assertThat(delPrincipal.statusCode()).isEqualTo(409);
        HttpResponse<String> upd = send("PUT", "/api/v1/sucursales/" + id(first), "{\"telefono\":\"999\",\"activo\":false}", auth);
        assertThat(upd.body()).contains("\"mensaje\":\"Sucursal actualizada.\"").contains("\"telefono\":\"999\"").contains("\"activo\":false");
        assertThat(send("DELETE", "/api/v1/sucursales/" + id(first), null, auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/sucursales/" + id(first), auth).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/sucursales/abc", auth).statusCode()).isEqualTo(404);

        // Aislamiento: otra empresa no ve la sucursal
        Map<String, String> otra = newTenant(tenants, "20200000009");
        assertThat(get("/api/v1/sucursales/" + id(second), otra).statusCode()).isEqualTo(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientes() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20200000002");
        HttpResponse<String> alias = send("POST", "/api/v1/clientes",
                "{\"tipo_doc\":\"6\",\"num_doc\":\"20555666777\",\"razon_social\":\"Óptica Lima SAC\",\"ubigeo\":\"150101\"}", auth);
        assertThat(alias.statusCode()).isEqualTo(201);
        Map<String, Object> c = datos(alias);
        assertThat(c.keySet()).containsExactly("id", "tipo_documento", "numero_documento", "razon_social", "nombre_comercial",
                "direccion", "email", "telefono", "creado_en");
        assertThat(c.get("numero_documento")).isEqualTo("20555666777");

        // Mismo documento → actualiza y responde 201 igual que API-PRO
        HttpResponse<String> again = send("POST", "/api/v1/clientes",
                "{\"tipo_documento\":\"6\",\"numero_documento\":\"20555666777\",\"razon_social\":\"OPTICA LIMA SAC\"}", auth);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(id(again)).isEqualTo(((Number) c.get("id")).longValue());

        send("POST", "/api/v1/clientes", "{\"tipo_documento\":\"1\",\"numero_documento\":\"12345678\",\"razon_social\":\"ana perez\"}", auth);
        send("POST", "/api/v1/clientes", "{\"tipo_documento\":\"1\",\"numero_documento\":\"87654321\",\"razon_social\":\"Bruno Díaz\"}", auth);

        HttpResponse<String> buscar = get("/api/v1/clientes?buscar=optica&por_pagina=2", auth);
        Map<String, Object> d = datos(buscar);
        assertThat((List<Object>) d.get("datos")).hasSize(1);
        assertThat(d.get("paginacion")).isEqualTo(Map.of("pagina_actual", 1, "ultima_pagina", 1, "por_pagina", 2, "total", 1));

        Map<String, Object> page2 = datos(get("/api/v1/clientes?por_pagina=2&page=2", auth));
        assertThat((List<Map<String, Object>>) page2.get("datos")).extracting(x -> x.get("razon_social")).containsExactly("OPTICA LIMA SAC");
        assertThat(((Map<String, Object>) page2.get("paginacion")).get("ultima_pagina")).isEqualTo(2);
        Map<String, Object> page1 = datos(get("/api/v1/clientes?por_pagina=2", auth));
        assertThat((List<Map<String, Object>>) page1.get("datos")).extracting(x -> x.get("razon_social"))
                .as("orden por razón social sin distinguir mayúsculas").containsExactly("ana perez", "Bruno Díaz");

        HttpResponse<String> invalid = send("POST", "/api/v1/clientes", "{\"tipo_documento\":\"9\",\"numero_documento\":123}", auth);
        assertThat(invalid.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Error de validaci\\u00f3n\",\"errores\":{"
                + "\"tipo_documento\":[\"El campo tipo documento no est\\u00e1 en la lista de valores permitidos.\"],"
                + "\"numero_documento\":[\"El campo numero documento debe ser una cadena de caracteres.\"],"
                + "\"razon_social\":[\"El campo razon social es obligatorio.\"]}}");

        long cid = ((Number) c.get("id")).longValue();
        HttpResponse<String> upd = send("PUT", "/api/v1/clientes/" + cid, "{\"email\":\"ventas@optica.pe\",\"tipo_documento\":\"1\"}", auth);
        assertThat(upd.body()).contains("\"mensaje\":\"Cliente actualizado.\"").contains("\"tipo_documento\":\"6\"").contains("ventas@optica.pe");
        assertThat(send("DELETE", "/api/v1/clientes/" + cid, null, auth).body())
                .isEqualTo("{\"estado\":\"exito\",\"mensaje\":\"Cliente eliminado.\",\"datos\":null}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void series() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20200000003");
        long suc = id(send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"Principal\",\"cod_local\":\"0000\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\"}", auth));

        HttpResponse<String> lote = send("POST", "/api/v1/series", "{\"series\":["
                + "{\"tipo\":\"factura\",\"serie\":\"F001\",\"sucursal_id\":" + suc + ",\"correlativo_inicial\":10},"
                + "{\"tipo\":\"boleta\",\"serie\":\"F002\",\"sucursal_id\":" + suc + "},"
                + "{\"tipo\":\"nota_credito\",\"serie\":\"BC01\",\"sucursal_id\":" + suc + "}]}", auth);
        assertThat(lote.statusCode()).isEqualTo(201);
        Map<String, Object> d = datos(lote);
        List<Map<String, Object>> creadas = (List<Map<String, Object>>) d.get("creadas");
        assertThat(creadas).hasSize(2);
        assertThat(creadas.get(0)).containsEntry("tipo", "factura").containsEntry("correlativo", 10).containsEntry("siguiente_numero", "F001-11");
        assertThat((Map<String, Object>) creadas.get(0).get("sucursal")).containsEntry("cod_local", "0000");
        assertThat(d.get("errores")).isEqualTo(List.of("#1 (F002): para boleta la serie debe empezar con: BXXX."));

        HttpResponse<String> dup = send("POST", "/api/v1/series", "{\"tipo\":\"factura\",\"serie\":\"F001\",\"sucursal_id\":" + suc + "}", auth);
        assertThat(dup.statusCode()).isEqualTo(422);
        assertThat(dup.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"No se pudo crear ninguna serie.\",\"errores\":[\"#0 (F001): ya existe para factura.\"]}");

        HttpResponse<String> invalid = send("POST", "/api/v1/series", "{\"series\":[{\"tipo\":\"otro\",\"serie\":\"f1\"}]}", auth);
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("\"series.0.tipo\":[\"El tipo debe ser uno de: factura,boleta,nota_credito,nota_debito,guia_remision,guia_transportista,retencion,percepcion,liquidacion_compra\"]")
                .contains("\"series.0.sucursal_id\":[\"Debe indicar la sucursal.\"]");

        long serieId = ((Number) creadas.get(0).get("id")).longValue();
        assertThat(send("DELETE", "/api/v1/series/" + serieId, null, auth).body()).contains("Serie desactivada.");
        List<Object> activas = (List<Object>) PhpJson.decodeObject(get("/api/v1/series", auth).body()).get("datos");
        List<Object> todas = (List<Object>) PhpJson.decodeObject(get("/api/v1/series?include_inactive=1", auth).body()).get("datos");
        assertThat(todas).hasSize(activas.size() + 1);
        // Una serie inactiva se reactiva con el correlativo indicado
        HttpResponse<String> react = send("POST", "/api/v1/series", "{\"tipo\":\"factura\",\"serie\":\"F001\",\"sucursal_id\":" + suc
                + ",\"correlativo_inicial\":3}", auth);
        assertThat(react.statusCode()).isEqualTo(201);
        assertThat(react.body()).contains("\"siguiente_numero\":\"F001-4\"");

        Map<String, String> otra = newTenant(tenants, "20200000008");
        long sucOtra = id(send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"X\",\"cod_local\":\"0000\",\"direccion\":\"Av\",\"ubigeo\":\"150101\"}", otra));
        HttpResponse<String> ajena = send("PUT", "/api/v1/series/" + serieId, "{\"sucursal_id\":" + sucOtra + "}", auth);
        assertThat(ajena.statusCode()).isEqualTo(403);
        assertThat(ajena.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"La sucursal no pertenece a su empresa.\"}");

        HttpResponse<String> init = send("POST", "/api/v1/series/init-defaults", "{\"correlativo_boleta\":50}", auth);
        assertThat(init.statusCode()).isEqualTo(200);
        assertThat(init.body()).contains("\"mensaje\":\"Series configuradas correctamente.\"").contains("\"siguiente_numero\":\"B001-51\"");

        // Corrección: el correlativo no baja del último emitido (el siguiente repetiría un número declarado)
        HttpResponse<String> f = send("POST", "/api/v1/facturas", "{\"serie\":\"F001\",\"fecha_emision\":\""
                + pe.facturacion.api.shared.time.Clock.todayLima() + "\",\"enviar_automatico\":false,\"cliente\":{\"tipo_doc\":\"6\","
                + "\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\",\"direccion\":\"AV. LIMA 1\"},\"items\":[{\"codigo\":\"P1\","
                + "\"descripcion\":\"Producto\",\"unidad\":\"NIU\",\"cantidad\":1,\"precio_unitario\":118,\"tip_afe_igv\":\"10\"}]}", auth);
        assertThat(f.statusCode()).as(f.body()).isEqualTo(201);
        assertThat(datos(f)).containsEntry("correlativo", 4);
        HttpResponse<String> bajar = send("PUT", "/api/v1/series/" + serieId, "{\"correlativo\":2}", auth);
        assertThat(bajar.statusCode()).isEqualTo(422);
        assertThat(bajar.body()).contains("no puede ser menor que el \\u00faltimo emitido (4)");
        HttpResponse<String> initBaja = send("POST", "/api/v1/series/init-defaults", "{\"correlativo_factura\":0,\"correlativo_boleta\":60}", auth);
        assertThat(initBaja.statusCode()).isEqualTo(422);
        assertThat(get("/api/v1/series", auth).body()).contains("\"siguiente_numero\":\"F001-5\"").contains("\"siguiente_numero\":\"B001-51\"");
        HttpResponse<String> igual = send("PUT", "/api/v1/series/" + serieId, "{\"correlativo\":4}", auth);
        assertThat(igual.statusCode()).isEqualTo(200);
    }
}
