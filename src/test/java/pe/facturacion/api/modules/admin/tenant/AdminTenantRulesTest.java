package pe.facturacion.api.modules.admin.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reglas puras de la fase C: RUC (H13), logo por contenido (H4), campos fuera de la edición y diferencias auditadas. */
class AdminTenantRulesTest {

    @Test
    void ruc_valido_con_digito_verificador() {
        // 2077777777 → suma 234, 234 mód 11 = 3, 11 − 3 = 8
        assertThat(AdminTenantRules.checkDigit("20777777778")).isEqualTo(8);
        assertThat(AdminTenantRules.rucError("20777777778")).isNull();
        assertThat(AdminTenantRules.rucError("20666666667")).isNull();
        // 10 → 0 y 11 → 1
        String base = buscarConResto(0);
        assertThat(AdminTenantRules.checkDigit(base + "0")).isEqualTo(1);
        String base10 = buscarConResto(1);
        assertThat(AdminTenantRules.checkDigit(base10 + "0")).isEqualTo(0);
    }

    @Test
    void ruc_invalido_con_mensaje_claro() {
        assertThat(AdminTenantRules.rucError(null)).isEqualTo("El RUC es obligatorio.");
        assertThat(AdminTenantRules.rucError(" ")).isEqualTo("El RUC es obligatorio.");
        assertThat(AdminTenantRules.rucError("2077777777")).contains("11 dígitos");
        assertThat(AdminTenantRules.rucError("2077777777A")).contains("11 dígitos");
        assertThat(AdminTenantRules.rucError("30777777778")).contains("10, 15, 16, 17 o 20");
        assertThat(AdminTenantRules.rucError("20777777771")).contains("dígito verificador");
    }

    @Test
    void logo_reconocido_por_su_contenido() {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        assertThat(AdminTenantRules.logoFormat(png)).isEqualTo("png");
        assertThat(AdminTenantRules.logoFormat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00})).isEqualTo("jpg");
        assertThat(AdminTenantRules.logoFormat("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo("webp");
        // Un SVG o un HTML con nombre .png no pasan (XSS almacenado, hallazgo H4)
        assertThat(AdminTenantRules.logoFormat("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(AdminTenantRules.logoFormat("<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(AdminTenantRules.logoFormat("GIF89a".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(AdminTenantRules.logoFormat(new byte[] {(byte) 0x89, 'P', 'N', 'G'})).as("firma PNG incompleta").isNull();
        assertThat(AdminTenantRules.logoFormat(null)).isNull();
        assertThat(AdminTenantRules.LOGO_FORMATS).containsExactly("png", "jpg", "webp");
    }

    @Test
    void la_edicion_general_no_acepta_credenciales_ni_archivos() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("razon_social", "X");
        body.put("sol_pass", "secreto");
        body.put("client_secret", "otro");
        body.put("logo", "x");
        assertThat(AdminTenantRules.forbiddenInEdit(body)).containsExactly("client_secret", "logo", "sol_pass");
        assertThat(AdminTenantRules.forbiddenInEdit(Map.of("razon_social", "X", "emails", List.of()))).isEmpty();
    }

    @Test
    void diferencias_para_la_auditoria_sin_derivados() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("razon_social", "A");
        before.put("igv_rate_override", 18);
        before.put("documentos_este_mes", 3);
        before.put("emails", List.of("a@x.pe"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("razon_social", "B");
        after.put("igv_rate_override", 18.0);
        after.put("documentos_este_mes", 4);
        after.put("emails", List.of("a@x.pe"));
        Map<String, Object> changes = AdminTenantRules.changes(before, after);
        assertThat(changes).containsOnlyKeys("razon_social");
        assertThat(changes.get("razon_social")).isEqualTo(Map.of("antes", "A", "despues", "B"));
    }

    @Test
    void ultimos_cuatro_de_la_api_key() {
        assertThat(AdminTenantRules.last4("abcdefghijklmnop")).isEqualTo("mnop");
        assertThat(AdminTenantRules.last4("corta")).isNull();
        assertThat(AdminTenantRules.last4(null)).isNull();
    }

    /** Primeros 10 dígitos (prefijo 20) cuya suma ponderada deja el resto indicado. */
    private static String buscarConResto(int resto) {
        for (long n = 0; n < 100_000_000L; n++) {
            String base = "20" + String.format("%08d", n);
            int sum = 0;
            int[] w = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
            for (int i = 0; i < 10; i++) {
                sum += (base.charAt(i) - '0') * w[i];
            }
            if (sum % 11 == resto) {
                return base;
            }
        }
        throw new IllegalStateException();
    }
}
