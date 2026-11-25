package pe.facturacion.api.sunat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pe.facturacion.api.modules.certificate.CertificateService;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.sunat.builder.InvoiceModelBuilder;
import pe.facturacion.api.sunat.model.SaleModels.Invoice;
import pe.facturacion.api.sunat.sign.XmlSigner;
import pe.facturacion.api.sunat.xml.InvoiceXmlBuilder;

/**
 * Paridad byte a byte del XML con Greenter: el oráculo ({@code fixtures/oracle/fact_bol.json})
 * se generó ejecutando el código real de API-PRO sobre los ejemplos de facturas y boletas de la
 * colección Postman. Para cada caso se toma la entrada exacta del builder ({@code documentToArray})
 * y se compara el XML sin firmar y el firmado con el mismo certificado de prueba.
 */
class GreenterXmlParityTest {

    private static List<Map<String, Object>> cases;
    private static CertificateService.Material material;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        try (InputStream in = GreenterXmlParityTest.class.getResourceAsStream("/fixtures/oracle/fact_bol.json")) {
            cases = (List<Map<String, Object>>) PhpJson.decode(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = GreenterXmlParityTest.class.getResourceAsStream("/fixtures/certs/cert-con-clave.pem")) {
            material = new CertificateService().parse(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    static Stream<Arguments> casos() throws Exception {
        load();
        List<Arguments> args = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            if (c.get("xml_unsigned") != null) {
                args.add(Arguments.of(c.get("name"), c));
            }
        }
        return args.stream();
    }

    static Tenant tenant() {
        Tenant t = new Tenant();
        t.setRuc("20000000001");
        t.setRazonSocial("EMPRESA DEMO S.A.C.");
        t.setNombreComercial("EMPRESA DEMO");
        t.setDireccion("AV. DEMO 123");
        t.setUbigeo("150101");
        t.setDepartamento("LIMA");
        t.setProvincia("LIMA");
        t.setDistrito("LIMA");
        t.setTaxRegime("general");
        return t;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casos")
    @SuppressWarnings("unchecked")
    void xmlIgualQueGreenter(String name, Map<String, Object> c) {
        Map<String, Object> input = (Map<String, Object>) c.get("builder_input");
        Invoice invoice = new InvoiceModelBuilder(tenant(), new TaxRateService()).build(input);
        assertThat(invoice.name()).isEqualTo(c.get("document_name"));

        String unsigned = InvoiceXmlBuilder.build(invoice);
        assertSame("sin firmar", (String) c.get("xml_unsigned"), unsigned);

        XmlSigner.Signed signed = XmlSigner.sign(unsigned, material.certificate(), material.privateKey());
        assertSame("firmado", (String) c.get("xml_signed"), signed.xml());
    }

    /** Igualdad exacta; si falla, muestra el contexto de la primera diferencia. */
    static void assertSame(String what, String expected, String actual) {
        if (expected.equals(actual)) {
            return;
        }
        int i = 0;
        while (i < expected.length() && i < actual.length() && expected.charAt(i) == actual.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 120);
        throw new AssertionError(what + ": primera diferencia en " + i
                + "\n  esperado: …" + expected.substring(from, Math.min(expected.length(), i + 120))
                + "\n  obtenido: …" + actual.substring(from, Math.min(actual.length(), i + 120)));
    }
}
