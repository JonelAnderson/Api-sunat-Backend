package pe.facturacion.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Activación del panel y orígenes CORS de ADMIN_ALLOWED_ORIGINS. */
class AdminPropertiesTest {

    @Test
    void el_panel_exige_una_clave_de_32_bytes() {
        assertThat(new AdminProperties(null, null, null, null).enabled()).isFalse();
        assertThat(new AdminProperties("", null, null, null).enabled()).isFalse();
        assertThat(new AdminProperties("x".repeat(31), null, null, null).enabled()).isFalse();
        assertThat(new AdminProperties("x".repeat(32), null, null, null).enabled()).isTrue();
        assertThat(new AdminProperties("  " + "x".repeat(31) + "  ", null, null, null).enabled()).isFalse();
    }

    @Test
    void origenes_separados_por_coma_y_sin_comodin() {
        assertThat(new AdminProperties(null, " https://localhost:4220 , https://panel.example.com,,", null, null).origins())
                .containsExactly("https://localhost:4220", "https://panel.example.com");
        assertThat(new AdminProperties(null, "*", null, null).origins()).isEmpty();
        assertThat(new AdminProperties(null, null, null, null).origins()).isEmpty();
    }
}
