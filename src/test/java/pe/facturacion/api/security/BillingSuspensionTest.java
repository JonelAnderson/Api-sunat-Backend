package pe.facturacion.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.crypto.EncryptedString;

/**
 * Empresa suspendida por falta de pago (consola de plataforma): consulta, descarga y la cola siguen; la emisión nueva
 * responde 402. La suspensión manual del panel (is_active = false) sigue respondiendo 403 en todo.
 */
class BillingSuspensionTest {

    static final String KEY = "key-de-prueba";
    static final String SECRET = "secret-de-prueba";

    @Test
    void rutas_de_emision_nueva() {
        for (String p : new String[] {"/api/v1/facturas", "/api/v1/facturas/masivo", "/api/v1/boletas", "/api/v1/notas-credito",
                "/api/v1/notas-debito", "/api/v1/guias-remision", "/api/v1/guias-remision-transportista", "/api/v1/retenciones",
                "/api/v1/percepciones", "/api/v1/liquidaciones-compra", "/api/sunat/facturas", "/api/sunat/boletas", "/api/v1/facturas/"}) {
            assertThat(EmissionRoutes.isNewEmission("POST", p)).as(p).isTrue();
            assertThat(EmissionRoutes.isNewEmission("GET", p)).as("GET " + p).isFalse();
        }
        // Lo que ya estaba registrado o en cola, y lo que SUNAT exige, sigue funcionando
        for (String p : new String[] {"/api/v1/facturas/10/enviar", "/api/v1/facturas/10/reenviar", "/api/v1/boletas/10/enviar",
                "/api/v1/resumenes", "/api/v1/anulaciones", "/api/v1/reversiones", "/api/v1/anulaciones/3/enviar",
                "/api/v1/consultar-cdr", "/api/v1/facturas/10/pdf", "/api/v1/series", "/api/v1/clientes", "/api/sunat/facturas/10/anular"}) {
            assertThat(EmissionRoutes.isNewEmission("POST", p)).as(p).isFalse();
        }
        assertThat(EmissionRoutes.isNewEmission("POST", null)).isFalse();
    }

    @Test
    void suspendida_por_pago_no_emite_pero_consulta_y_sigue_su_cola() throws Exception {
        TenantAuthInterceptor interceptor = interceptor(tenant(true, "pago"));

        MockHttpServletResponse emit = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("POST", "/api/v1/facturas"), emit, null)).isFalse();
        assertThat(emit.getStatus()).isEqualTo(402);
        assertThat(emit.getContentAsString()).contains("\"estado\":\"error\"").contains("falta de pago");

        assertThat(interceptor.preHandle(request("POST", "/api/sunat/boletas"), new MockHttpServletResponse(), null)).isFalse();
        for (String[] ok : new String[][] {{"GET", "/api/v1/facturas"}, {"GET", "/api/v1/facturas/7/pdf"}, {"GET", "/api/v1/facturas/7/xml"},
                {"POST", "/api/v1/facturas/7/reenviar"}, {"POST", "/api/v1/resumenes"}, {"POST", "/api/v1/anulaciones"},
                {"POST", "/api/v1/consultar-cdr"}}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(request(ok[0], ok[1]), res, null)).as(ok[0] + " " + ok[1]).isTrue();
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void la_ruta_se_toma_como_la_ve_spring_no_del_uri_crudo() throws Exception {
        TenantAuthInterceptor interceptor = interceptor(tenant(true, "pago"));
        // %66 = 'f' y ;x=1: el URI crudo no coincide con la lista, pero Spring la enruta igual a /api/v1/facturas
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/%66acturas;x=1");
        req.setServletPath("/api/v1/facturas");
        req.addHeader("X-Api-Key", KEY);
        req.addHeader("X-Api-Secret", SECRET);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, res, null)).isFalse();
        assertThat(res.getStatus()).isEqualTo(402);
    }

    @Test
    void empresa_normal_emite_y_suspension_manual_sigue_en_403() throws Exception {
        assertThat(interceptor(tenant(true, null)).preHandle(request("POST", "/api/v1/facturas"), new MockHttpServletResponse(), null)).isTrue();

        MockHttpServletResponse manual = new MockHttpServletResponse();
        assertThat(interceptor(tenant(false, null)).preHandle(request("GET", "/api/v1/facturas"), manual, null)).isFalse();
        assertThat(manual.getStatus()).isEqualTo(403);
        assertThat(manual.getContentAsString()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Empresa desactivada. Contacte al administrador.\"}");
    }

    private static TenantAuthInterceptor interceptor(Tenant tenant) {
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findByApiKey(KEY)).thenReturn(Optional.of(tenant));
        return new TenantAuthInterceptor(repo);
    }

    private static Tenant tenant(boolean active, String kind) {
        Tenant t = mock(Tenant.class);
        EncryptedString secret = mock(EncryptedString.class);
        when(secret.reveal()).thenReturn(SECRET);
        when(t.getApiSecret()).thenReturn(secret);
        when(t.isActive()).thenReturn(active);
        when(t.isBillingSuspended()).thenReturn("pago".equals(kind));
        return t;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        r.addHeader("X-Api-Key", KEY);
        r.addHeader("X-Api-Secret", SECRET);
        return r;
    }
}
