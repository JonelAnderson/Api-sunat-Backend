package pe.facturacion.api.modules.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.shared.http.Input;

/** Periodo del panel leído de la entrada (lo comparten /api/v1/panel y la vista de empresa del panel admin). */
class PanelPeriodoTest {

    @Test
    void mes_desde_hasta_e_invalidos() {
        PanelController.Periodo mes = PanelController.periodo(new Input(Map.of("mes", "2026-02")));
        assertThat(mes.json(true)).containsEntry("desde", "2026-02-01 00:00:00").containsEntry("hasta", "2026-02-28 23:59:59")
                .containsEntry("label", "Febrero 2026");

        Map<String, Object> rango = new LinkedHashMap<>();
        rango.put("desde", "2026-11-01");
        rango.put("hasta", "2026-11-15");
        assertThat(PanelController.periodo(new Input(rango)).json(true)).containsEntry("label", "2026-11-01 al 2026-11-15")
                .containsEntry("hasta", "2026-11-15 23:59:59");

        assertThat(PanelController.periodo(new Input(Map.of("mes", "2026-13")))).isNull();
        assertThat(PanelController.periodo(new Input(Map.of("desde", "2026-11-01", "hasta", "x")))).isNull();
    }
}
