package pe.facturacion.api.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TrimStrings + ConvertEmptyStringsToNull de Laravel. */
class InputSanitizerTest {

    @Test
    void recortaYConvierteVaciosEnNull() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("serie", "  F001 ");
        in.put("vacio", "   ");
        in.put("password", "  con espacios  ");
        in.put("cliente", Map.of("password", " x ", "razon_social", "​ ACME ﻿"));
        in.put("items", List.of(Map.of("descripcion", ""), "  a  "));
        in.put("numero", 10);

        Map<String, Object> out = InputSanitizer.cleanAll(in);

        assertThat(out.get("serie")).isEqualTo("F001");
        assertThat(out.get("vacio")).isNull();
        assertThat(out.get("password")).isEqualTo("  con espacios  ");
        @SuppressWarnings("unchecked")
        Map<String, Object> cliente = (Map<String, Object>) out.get("cliente");
        assertThat(cliente.get("password")).as("solo se exime el campo de primer nivel").isEqualTo("x");
        assertThat(cliente.get("razon_social")).isEqualTo("ACME");
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) out.get("items");
        assertThat(((Map<?, ?>) items.get(0)).get("descripcion")).isNull();
        assertThat(items.get(1)).isEqualTo("a");
        assertThat(out.get("numero")).isEqualTo(10);
    }
}
