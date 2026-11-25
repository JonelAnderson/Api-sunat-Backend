package pe.facturacion.api.modules.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import pe.facturacion.api.modules.idempotency.IdempotencyStore;
import pe.facturacion.api.modules.tenant.domain.Tenant;

/** Idempotencia obligatoria de las emisiones del panel (§11.4). */
class AdminIdempotencyTest {

    static final String KEY = "0f8c2b4e-1d2a-4c3b-9e8f-112233445566";

    IdempotencyStore store;
    AdminIdempotency idem;
    Tenant tenant;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        idem = new AdminIdempotency(store);
        tenant = new Tenant();
        org.springframework.test.util.ReflectionTestUtils.setField(tenant, "id", 12L);
    }

    @Test
    void validacion_de_la_cabecera() {
        assertThat(AdminIdempotency.keyError(null)).isEqualTo(AdminIdempotency.MISSING);
        assertThat(AdminIdempotency.keyError("  ")).isEqualTo(AdminIdempotency.MISSING);
        assertThat(AdminIdempotency.keyError("corta")).isEqualTo(AdminIdempotency.INVALID);
        assertThat(AdminIdempotency.keyError("con espacio 123")).isEqualTo(AdminIdempotency.INVALID);
        assertThat(AdminIdempotency.keyError("x".repeat(101))).isEqualTo(AdminIdempotency.INVALID);
        assertThat(AdminIdempotency.keyError(KEY)).isNull();
        assertThat(AdminIdempotency.keyError("pedido:2026-11-24.001_a")).isNull();
    }

    @Test
    void sin_clave_no_ejecuta_nada() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> idem.run(request(null, "{}"), tenant, () -> {
            calls.incrementAndGet();
            return ok();
        })).isInstanceOf(AdminException.class).satisfies(e -> {
            AdminException a = (AdminException) e;
            assertThat(a.status()).isEqualTo(200);
            assertThat(a.errors()).extracting(m -> m.get("propertyName")).containsExactly("Idempotency-Key");
        });
        assertThat(calls).hasValue(0);
        verify(store, never()).reserve(anyLong(), anyString(), anyString());
    }

    @Test
    void la_primera_vez_ejecuta_y_guarda_solo_la_respuesta_correcta() {
        when(store.reserve(eq(12L), eq("panel:" + KEY), anyString())).thenReturn(true);
        ResponseEntity<Object> r = idem.run(request(KEY, "{\"serie\":\"F001\"}"), tenant, AdminIdempotencyTest::ok);
        assertThat(r.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        verify(store).complete(eq(12L), eq("panel:" + KEY), eq(200), anyString());
        verify(store, never()).release(anyLong(), anyString());
    }

    @Test
    void un_error_libera_la_clave_para_reintentar() {
        when(store.reserve(eq(12L), anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> idem.run(request(KEY, "{}"), tenant, () -> {
            throw AdminException.fail("Has alcanzado el limite de documentos SUNAT");
        })).isInstanceOf(AdminException.class);
        verify(store).release(12L, "panel:" + KEY);
        verify(store, never()).complete(anyLong(), anyString(), anyInt(), anyString());

        ResponseEntity<Object> fail = ResponseEntity.ok(AdminResponse.body(false, List.of("x"), null, 0, List.of()));
        idem.run(request(KEY, "{}"), tenant, () -> fail);
        verify(store, org.mockito.Mockito.times(2)).release(12L, "panel:" + KEY);
    }

    @Test
    void repetir_devuelve_lo_mismo_sin_volver_a_emitir() {
        String hash = AdminIdempotency.fingerprint("POST", "/api/admin/tenants/12/facturas", "{\"serie\":\"F001\"}");
        when(store.find(12L, "panel:" + KEY)).thenReturn(new IdempotencyStore.Record(hash, 200,
                "{\"succeeded\":true,\"messages\":[\"Factura creada\"],\"data\":{\"id\":15},\"totalRecords\":0,\"errors\":[]}"));
        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<Object> r = idem.run(request(KEY, "{\"serie\":\"F001\"}"), tenant, () -> {
            calls.incrementAndGet();
            return ok();
        });
        assertThat(calls).hasValue(0);
        assertThat(r.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertThat(body).containsEntry("succeeded", true);
        assertThat(((Number) ((Map<?, ?>) body.get("data")).get("id")).longValue()).isEqualTo(15L);
    }

    @Test
    void misma_clave_con_otro_cuerpo_o_en_curso() {
        when(store.find(12L, "panel:" + KEY)).thenReturn(new IdempotencyStore.Record("otra-huella", 200, "{}"));
        assertThatThrownBy(() -> idem.run(request(KEY, "{\"serie\":\"F002\"}"), tenant, AdminIdempotencyTest::ok))
                .isInstanceOf(AdminException.class).hasMessage(AdminIdempotency.MISMATCH);

        String hash = AdminIdempotency.fingerprint("POST", "/api/admin/tenants/12/facturas", "{}");
        when(store.find(12L, "panel:" + KEY)).thenReturn(new IdempotencyStore.Record(hash, null, null));
        assertThatThrownBy(() -> idem.run(request(KEY, "{}"), tenant, AdminIdempotencyTest::ok))
                .isInstanceOf(AdminException.class).satisfies(e -> assertThat(((AdminException) e).status()).isEqualTo(409));

        when(store.find(12L, "panel:" + KEY)).thenReturn(null);
        when(store.reserve(eq(12L), anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> idem.run(request(KEY, "{}"), tenant, AdminIdempotencyTest::ok))
                .isInstanceOf(AdminException.class).satisfies(e -> assertThat(((AdminException) e).status()).isEqualTo(409));
    }

    @Test
    void la_huella_cambia_con_metodo_ruta_y_cuerpo() {
        String a = AdminIdempotency.fingerprint("POST", "/api/admin/tenants/12/facturas", "{}");
        assertThat(a).hasSize(64).isEqualTo(AdminIdempotency.fingerprint("POST", "/api/admin/tenants/12/facturas", "{}"));
        assertThat(a).isNotEqualTo(AdminIdempotency.fingerprint("POST", "/api/admin/tenants/13/facturas", "{}"));
        assertThat(a).isNotEqualTo(AdminIdempotency.fingerprint("POST", "/api/admin/tenants/12/boletas", "{}"));
        assertThat(a).isNotEqualTo(AdminIdempotency.fingerprint("POST", "/api/admin/tenants/12/facturas", "{\"a\":1}"));
    }

    private static ResponseEntity<Object> ok() {
        return ResponseEntity.ok(AdminResponse.body(true, List.of("Factura creada"), Map.of("id", 15), 0, List.of()));
    }

    private static MockHttpServletRequest request(String key, String body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/admin/tenants/12/facturas");
        r.setContentType("application/json");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            r.addHeader("Idempotency-Key", key);
        }
        return r;
    }
}
