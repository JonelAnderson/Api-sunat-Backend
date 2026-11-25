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

/** Upsert idempotente de sucursales y series desde el ERP: piso del correlativo ({@code correlativo_minimo}). */
class SeriePisoIT extends IntegrationTest {

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
    void piso_en_alta_y_en_actualizacion() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20200000051");
        long suc = id(send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"Principal\",\"cod_local\":\"0000\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\"}", auth));

        // Alta con piso: la serie nace en el piso
        HttpResponse<String> alta = send("POST", "/api/v1/series", "{\"tipo\":\"factura\",\"serie\":\"F001\",\"sucursal_id\":" + suc
                + ",\"correlativo_minimo\":150}", auth);
        assertThat(alta.statusCode()).isEqualTo(201);
        Map<String, Object> serie = ((List<Map<String, Object>>) datos(alta).get("creadas")).get(0);
        assertThat(serie).containsEntry("correlativo", 150);
        long serieId = ((Number) serie.get("id")).longValue();

        // Repetir el mismo piso no cambia nada; un piso menor tampoco baja el contador ni falla
        for (int piso : new int[] {150, 100, 0}) {
            HttpResponse<String> put = send("PUT", "/api/v1/series/" + serieId, "{\"correlativo_minimo\":" + piso + "}", auth);
            assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
            assertThat(datos(put)).containsEntry("correlativo", 150);
        }
        // Un piso mayor sube
        assertThat(datos(send("PUT", "/api/v1/series/" + serieId, "{\"correlativo_minimo\":200}", auth))).containsEntry("correlativo", 200);

        // No se mezcla con el correlativo exacto
        HttpResponse<String> ambos = send("PUT", "/api/v1/series/" + serieId, "{\"correlativo\":5,\"correlativo_minimo\":5}", auth);
        assertThat(ambos.statusCode()).isEqualTo(422);
        assertThat(ambos.body()).contains("no ambos");

        // Con un comprobante emitido (F001-201) el piso nunca queda por debajo
        HttpResponse<String> f = send("POST", "/api/v1/facturas", "{\"serie\":\"F001\",\"fecha_emision\":\""
                + pe.facturacion.api.shared.time.Clock.todayLima() + "\",\"enviar_automatico\":false,\"cliente\":{\"tipo_doc\":\"6\","
                + "\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\",\"direccion\":\"AV. LIMA 1\"},\"items\":[{\"codigo\":\"P1\","
                + "\"descripcion\":\"Producto\",\"unidad\":\"NIU\",\"cantidad\":1,\"precio_unitario\":118,\"tip_afe_igv\":\"10\"}]}", auth);
        assertThat(f.statusCode()).as(f.body()).isEqualTo(201);
        assertThat(datos(f)).containsEntry("correlativo", 201);
        assertThat(datos(send("PUT", "/api/v1/series/" + serieId, "{\"correlativo_minimo\":10}", auth))).containsEntry("correlativo", 201);

        // Serie desactivada y vuelta a dar de alta con piso: se reactiva sin bajar
        send("DELETE", "/api/v1/series/" + serieId, null, auth);
        HttpResponse<String> react = send("POST", "/api/v1/series", "{\"tipo\":\"factura\",\"serie\":\"F001\",\"sucursal_id\":" + suc
                + ",\"correlativo_minimo\":50}", auth);
        assertThat(react.statusCode()).isEqualTo(201);
        assertThat(((List<Map<String, Object>>) datos(react).get("creadas")).get(0)).containsEntry("correlativo", 201).containsEntry("activo", true);
    }

    @Test
    void sucursal_se_reconcilia_por_cod_local() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20200000052");
        String body = "{\"nombre\":\"Sede\",\"cod_local\":\"0001\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\"}";
        long id = id(send("POST", "/api/v1/sucursales", body, auth));
        // Repetir el alta responde 409 (el cliente reconcilia con GET y actualiza por id)
        assertThat(send("POST", "/api/v1/sucursales", body, auth).statusCode()).isEqualTo(409);
        HttpResponse<String> put = send("PUT", "/api/v1/sucursales/" + id, "{\"direccion\":\"Av 2\"}", auth);
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(datos(put)).containsEntry("direccion", "Av 2").containsEntry("cod_local", "0001");
    }
}
