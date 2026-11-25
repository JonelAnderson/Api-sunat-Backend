package pe.facturacion.api.modules.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.modules.tenant.domain.Tenant;

/** H2 (docs/SEGURIDAD_2026-11-23.md): el bridge solo reprovisiona la empresa de su propio usuario. */
class BridgeOwnershipTest {

    private static Tenant tenant(Long userId) {
        Tenant t = new Tenant();
        t.setUserId(userId);
        return t;
    }

    @Test
    void el_dueno_puede_reprovisionar() {
        assertThat(BridgeController.ownedBy(tenant(7L), Optional.of(7L))).isTrue();
    }

    @Test
    void otro_usuario_no_adopta_la_empresa() {
        assertThat(BridgeController.ownedBy(tenant(7L), Optional.of(8L))).isFalse();
    }

    @Test
    void una_empresa_sin_dueno_no_se_adopta() {
        // registro público o consola: user_id nulo
        assertThat(BridgeController.ownedBy(tenant(null), Optional.of(7L))).isFalse();
    }

    @Test
    void un_correo_sin_usuario_no_es_dueno_de_nada() {
        assertThat(BridgeController.ownedBy(tenant(7L), Optional.empty())).isFalse();
        assertThat(BridgeController.ownedBy(tenant(null), Optional.empty())).isFalse();
    }
}
