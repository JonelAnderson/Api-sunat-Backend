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

/** Catálogo UNSPSC (código de producto SUNAT) y ubigeo. */
class CatalogosIT extends IntegrationTest {

    @Autowired
    TenantRepository tenants;

    @Test
    @SuppressWarnings("unchecked")
    void productoSunat() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20300000001");

        HttpResponse<String> corto = get("/api/v1/catalogos/producto-sunat?q=a", auth);
        assertThat(corto.statusCode()).isEqualTo(422);
        assertThat(corto.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"El par\\u00e1metro \\\"q\\\" debe tener al menos 2 caracteres.\"}");

        Map<String, Object> porCodigo = PhpJson.decodeObject(get("/api/v1/catalogos/producto-sunat?q=101015&per_page=3", auth).body());
        assertThat(porCodigo.keySet()).containsExactly("estado", "total", "pagina", "por_pagina", "datos");
        assertThat(porCodigo.get("por_pagina")).isEqualTo(3);
        List<Map<String, Object>> datos = (List<Map<String, Object>>) porCodigo.get("datos");
        assertThat(datos).hasSize(3);
        assertThat(datos.get(0)).containsEntry("codigo", "10101500").containsEntry("descripcion", "Animales de granja");

        // Texto: todas las palabras, sin distinguir mayúsculas ni tildes (como MySQL utf8mb4_unicode_ci)
        Map<String, Object> texto = PhpJson.decodeObject(get("/api/v1/catalogos/producto-sunat?q=ANIMALES%20granja", auth).body());
        assertThat((List<Map<String, Object>>) texto.get("datos")).extracting(x -> x.get("codigo")).contains("10101500");
        Map<String, Object> tildes = PhpJson.decodeObject(get("/api/v1/catalogos/producto-sunat?q=camion", auth).body());
        assertThat(((Number) tildes.get("total")).intValue()).isPositive();
        assertThat((List<Map<String, Object>>) tildes.get("datos")).anyMatch(x -> x.get("descripcion").toString().toLowerCase().contains("camión"));

        HttpResponse<String> show = get("/api/v1/catalogos/producto-sunat/10101501", auth);
        assertThat(show.body()).startsWith("{\"estado\":\"exito\",\"valido\":true,\"datos\":{\"codigo\":\"10101501\",\"descripcion\":\"Gatos\",\"jerarquia\":{\"segmento\":{\"codigo\":\"10\"");
        HttpResponse<String> noExiste = get("/api/v1/catalogos/producto-sunat/99999998", auth);
        assertThat(noExiste.statusCode()).isEqualTo(404);
        assertThat(noExiste.body()).isEqualTo("{\"estado\":\"error\",\"valido\":false,\"mensaje\":\"El c\\u00f3digo 99999998 no existe en el cat\\u00e1logo UNSPSC v14.\"}");
        assertThat(get("/api/v1/catalogos/producto-sunat/123", auth).body()).as("fuera del patrón de ruta → 404 global")
                .isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Recurso no encontrado.\"}");

        Map<String, Object> seg = PhpJson.decodeObject(get("/api/v1/catalogos/producto-sunat/segmentos", auth).body());
        assertThat(seg.keySet()).containsExactly("estado", "total", "datos");
        assertThat(((Number) seg.get("total")).intValue()).isGreaterThan(50);
        assertThat(get("/api/v1/catalogos/producto-sunat/familias?segmento=1", auth).body())
                .isEqualTo("{\"estado\":\"error\",\"mensaje\":\"El par\\u00e1metro \\\"segmento\\\" es obligatorio y debe tener 2 d\\u00edgitos.\"}");
        Map<String, Object> prods = PhpJson.decodeObject(get("/api/v1/catalogos/producto-sunat/productos?clase=101015", auth).body());
        assertThat((List<Map<String, Object>>) prods.get("datos")).allMatch(x -> !x.get("codigo").toString().endsWith("00"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ubigeo() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20300000002");
        List<Map<String, Object>> regiones = (List<Map<String, Object>>) PhpJson.decodeObject(get("/api/v1/catalogos/ubigeo/regiones", auth).body()).get("datos");
        assertThat(regiones).hasSize(25);
        assertThat(regiones.get(0).keySet()).containsExactly("id", "nombre");
        assertThat(regiones.get(0).get("nombre")).isEqualTo("Amazonas");
        assertThat(regiones).extracting(r -> r.get("nombre")).contains("Áncash");
        // Orden sin tildes: Áncash va entre Amazonas y Apurímac
        List<Object> nombres = regiones.stream().map(r -> r.get("nombre")).toList();
        assertThat(nombres.indexOf("Áncash")).isEqualTo(1);

        List<Map<String, Object>> distritos = (List<Map<String, Object>>) PhpJson.decodeObject(
                get("/api/v1/catalogos/ubigeo/distritos?provincia_id=1501", auth).body()).get("datos");
        assertThat(distritos.get(0).keySet()).containsExactly("id", "nombre", "provincia_id");
        assertThat(distritos).extracting(d -> d.get("id")).contains("150101");
        assertThat(get("/api/v1/catalogos/ubigeo/provincias?region_id=1", auth).body())
                .isEqualTo("{\"estado\":\"error\",\"mensaje\":\"El par\\u00e1metro region_id es requerido (2 d\\u00edgitos).\"}");
    }
}
