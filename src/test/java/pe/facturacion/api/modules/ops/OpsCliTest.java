package pe.facturacion.api.modules.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsCliTest {

    @Test
    void parseaOpcionesYBanderas() {
        Map<String, String> o = OpsCli.options(List.of("--days=30", "--alertar", "suelto", "--estados=enviado,pendiente"));
        assertThat(o).containsEntry("days", "30").containsEntry("alertar", "").containsEntry("estados", "enviado,pendiente").hasSize(3);
        assertThat(OpsCli.intOpt(o, "days", 90)).isEqualTo(30);
        assertThat(OpsCli.intOpt(o, "alertar", 5)).isEqualTo(5);
        assertThat(OpsCli.intOpt(o, "nada", 7)).isEqualTo(7);
    }

    @Test
    void tareaDesconocidaDevuelveCodigoDosYSinTareaMuestraLaAyuda() {
        assertThat(OpsCli.run(null, new String[] {"ops", "no-existe"})).isEqualTo(2);
        assertThat(OpsCli.run(null, new String[] {"ops"})).isZero();
    }
}
