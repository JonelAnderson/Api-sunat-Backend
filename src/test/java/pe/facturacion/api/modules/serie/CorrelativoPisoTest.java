package pe.facturacion.api.modules.serie;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Piso del correlativo que usa la sincronización desde el ERP: el contador nunca baja y repetir la petición no cambia nada. */
class CorrelativoPisoTest {

    @Test
    void sube_hasta_el_piso() {
        assertThat(CorrelativoEmitido.piso(0, 150, 0)).isEqualTo(150);
        assertThat(CorrelativoEmitido.piso(10, 11, 0)).isEqualTo(11);
    }

    @Test
    void nunca_baja_del_actual() {
        assertThat(CorrelativoEmitido.piso(200, 150, 0)).isEqualTo(200);
        assertThat(CorrelativoEmitido.piso(200, 0, 0)).isEqualTo(200);
    }

    @Test
    void nunca_baja_del_ultimo_emitido() {
        // Contador desfasado (p. ej. serie reactivada) con comprobantes ya declarados: manda el emitido
        assertThat(CorrelativoEmitido.piso(3, 5, 40)).isEqualTo(40);
    }

    @Test
    void es_idempotente() {
        int una = CorrelativoEmitido.piso(7, 150, 20);
        int dos = CorrelativoEmitido.piso(una, 150, 20);
        assertThat(dos).isEqualTo(una).isEqualTo(150);
    }
}
