package pe.facturacion.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pe.facturacion.api.config.AppProperties;

/** Registro público y autoservicio de suscripción: apagados por defecto, 404 sin ejecutar nada, lecturas siempre activas. */
class PublicFeatureInterceptorTest {

    static AppProperties props(boolean registro, boolean suscripcion) {
        return new AppProperties(null, null, null, false, null, false, null, null, null, null, null,
                new AppProperties.Registro(registro), new AppProperties.SuscripcionAutoservicio(suscripcion));
    }

    @Test
    void sin_configuracion_todo_queda_apagado() {
        AppProperties sinBloques = new AppProperties(null, null, null, false, null, false, null, null, null, null, null, null, null);
        assertThat(sinBloques.registroEnabled()).isFalse();
        assertThat(sinBloques.suscripcionAutoservicioEnabled()).isFalse();
        assertThat(PublicFeatureInterceptor.disabledMessage(sinBloques, "POST", "/api/v1/registro")).isPresent();
        assertThat(PublicFeatureInterceptor.disabledMessage(sinBloques, "POST", "/api/v1/suscripcion")).isPresent();
    }

    @Test
    void los_nombres_de_las_propiedades_enlazan() {
        Binder off = new Binder(new MapConfigurationPropertySource(Map.of("app.registro.enabled", "false")));
        assertThat(off.bind("app.registro", AppProperties.Registro.class).get().enabled()).isFalse();
        Binder on = new Binder(new MapConfigurationPropertySource(Map.of(
                "app.registro.enabled", "true", "app.suscripcion-autoservicio.enabled", "true")));
        assertThat(on.bind("app.registro", AppProperties.Registro.class).get().enabled()).isTrue();
        assertThat(on.bind("app.suscripcion-autoservicio", AppProperties.SuscripcionAutoservicio.class).get().enabled()).isTrue();
    }

    @Test
    void registro_apagado_responde_404_en_espanol_y_no_deja_pasar() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/registro");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(new PublicFeatureInterceptor(props(false, true)).preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"estado\":\"error\"")
                .contains("El registro p\\u00fablico de empresas no est\\u00e1 disponible");
    }

    @Test
    void registro_encendido_deja_pasar() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(new PublicFeatureInterceptor(props(true, false)).preHandle(
                new MockHttpServletRequest("POST", "/api/v1/registro"), response, new Object())).isTrue();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void autoservicio_apagado_bloquea_solo_lo_que_cambia_el_plan() {
        AppProperties off = props(true, false);
        for (String[] write : new String[][] {{"POST", "/api/v1/suscripcion"}, {"PUT", "/api/v1/suscripcion/cambiar-plan"},
                {"PUT", "/api/v1/suscripcion/cancelar"}, {"POST", "/api/v1/suscripcion/"}, {"DELETE", "/api/v1/suscripcion"}}) {
            assertThat(PublicFeatureInterceptor.disabledMessage(off, write[0], write[1]))
                    .as(write[0] + " " + write[1]).contains(PublicFeatureInterceptor.SUSCRIPCION_OFF_MESSAGE);
        }
        // Lecturas (no cambian estado ni dependen de Culqi) y rutas ajenas
        for (String[] read : new String[][] {{"GET", "/api/v1/suscripcion"}, {"GET", "/api/v1/suscripcion/uso"},
                {"GET", "/api/v1/suscripcion/pagos"}, {"HEAD", "/api/v1/suscripcion/uso"}, {"GET", "/api/v1/planes"},
                {"POST", "/api/v1/facturas"}, {"POST", "/api/v1/suscripcionx"}}) {
            assertThat(PublicFeatureInterceptor.disabledMessage(off, read[0], read[1])).as(read[0] + " " + read[1]).isEmpty();
        }
    }

    @Test
    void autoservicio_encendido_deja_pasar_y_es_independiente_del_registro() throws Exception {
        AppProperties on = props(false, true);
        for (String[] write : new String[][] {{"POST", "/api/v1/suscripcion"}, {"PUT", "/api/v1/suscripcion/cambiar-plan"},
                {"PUT", "/api/v1/suscripcion/cancelar"}}) {
            assertThat(PublicFeatureInterceptor.disabledMessage(on, write[0], write[1])).as(write[1]).isEmpty();
        }
        assertThat(PublicFeatureInterceptor.disabledMessage(on, "POST", "/api/v1/registro")).isPresent();

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(new PublicFeatureInterceptor(props(true, false)).preHandle(
                new MockHttpServletRequest("PUT", "/api/v1/suscripcion/cancelar"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("autoservicio de suscripci\\u00f3n");
    }
}
