package pe.facturacion.api.modules.portal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import pe.facturacion.api.config.AdminProperties;
import pe.facturacion.api.config.PortalProperties;
import pe.facturacion.api.modules.admin.api.AdminException;

/** El portal solo se enciende con su propia clave: de 32 bytes o más y distinta de la del panel. */
class PortalTokensTest {

    static final String ADMIN = "clave-de-prueba-del-panel-0123456789abcdef";
    static final String PORTAL = "clave-de-prueba-del-portal-0123456789abcdef";

    private static PortalTokens tokens(String portalSecret, String adminSecret) {
        return new PortalTokens(new PortalProperties(portalSecret, null), new AdminProperties(adminSecret, null, null, null));
    }

    @Test
    void con_su_clave_propia_queda_encendido() {
        PortalTokens t = tokens(PORTAL, ADMIN);
        assertThat(t.enabled()).isTrue();
        assertThat(t.jwt()).isNotNull();
        // También si el panel está apagado
        assertThat(tokens(PORTAL, "").enabled()).isTrue();
    }

    @Test
    void sin_clave_o_con_clave_corta_queda_apagado_y_responde_503() {
        for (String secret : new String[] {null, "", "   ", "corta", "x".repeat(31)}) {
            PortalTokens t = tokens(secret, ADMIN);
            assertThat(t.enabled()).as("secret=%s", secret).isFalse();
            assertThatThrownBy(t::jwt).isInstanceOf(AdminException.class).satisfies(e -> {
                assertThat(((AdminException) e).status()).isEqualTo(503);
                assertThat(((AdminException) e).messages().get(0)).contains("PORTAL_JWT_SECRET");
            });
        }
    }

    @Test
    void una_clave_igual_a_la_del_panel_apaga_el_portal() {
        assertThat(tokens(ADMIN, ADMIN).enabled()).isFalse();
        // Con espacios alrededor sigue siendo la misma clave
        assertThat(tokens("  " + ADMIN + " ", ADMIN).enabled()).isFalse();
    }

    @Test
    void los_origenes_nunca_aceptan_el_comodin() {
        PortalProperties p = new PortalProperties(PORTAL, " https://portal.ejemplo.pe , * ,, https://otro.ejemplo.pe");
        assertThat(p.origins()).containsExactly("https://portal.ejemplo.pe", "https://otro.ejemplo.pe");
        assertThat(new PortalProperties(PORTAL, null).origins()).isEmpty();
        assertThat(new PortalProperties(PORTAL, "  ").origins()).isEmpty();
    }
}
