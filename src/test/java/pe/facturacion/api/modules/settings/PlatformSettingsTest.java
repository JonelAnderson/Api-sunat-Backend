package pe.facturacion.api.modules.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Lectura tolerante de los valores de {@code settings} (los de API-PRO y los del panel) y enmascarado del token. */
class PlatformSettingsTest {

    @Test
    void booleanos_guardados_por_api_pro_o_por_el_panel() {
        assertThat(PlatformSettings.truthy(true)).isTrue();
        assertThat(PlatformSettings.truthy(1)).isTrue();
        assertThat(PlatformSettings.truthy("1")).isTrue();
        assertThat(PlatformSettings.truthy(" TRUE ")).isTrue();
        assertThat(PlatformSettings.truthy(false)).isFalse();
        assertThat(PlatformSettings.truthy(0)).isFalse();
        assertThat(PlatformSettings.truthy("si")).isFalse();
        assertThat(PlatformSettings.truthy(null)).isFalse();
    }

    @Test
    void solo_los_ultimos_cuatro_caracteres_de_un_token_largo() {
        assertThat(PlatformSettings.last4("abcdefghijklmnop")).isEqualTo("mnop");
        assertThat(PlatformSettings.last4("cortito1234")).isNull();
        assertThat(PlatformSettings.last4(null)).isNull();
    }
}
