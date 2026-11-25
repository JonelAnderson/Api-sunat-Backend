package pe.facturacion.api.sunat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pe.facturacion.api.modules.certificate.CertificateService;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.sunat.builder.NoteModelBuilder;
import pe.facturacion.api.sunat.builder.TicketModelBuilder;
import pe.facturacion.api.sunat.sign.XmlSigner;
import pe.facturacion.api.sunat.xml.NoteXmlBuilder;
import pe.facturacion.api.sunat.xml.TicketXmlBuilder;

/**
 * XML de notas de crédito/débito, resumen diario y comunicación de baja idéntico al de Greenter
 * (oráculo {@code fixtures/oracle/fase4.json}, generado con API-PRO real).
 */
class TicketAndNoteXmlParityTest {

    static Stream<Arguments> casos() throws Exception {
        List<Map<String, Object>> cases;
        try (InputStream in = TicketAndNoteXmlParityTest.class.getResourceAsStream("/fixtures/oracle/fase4.json")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> c = (List<Map<String, Object>>) PhpJson.decode(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            cases = c;
        }
        List<Arguments> args = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            if (c.get("xml_unsigned") != null) {
                args.add(Arguments.of(c.get("name"), c));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casos")
    @SuppressWarnings("unchecked")
    void xmlIgualQueGreenter(String name, Map<String, Object> c) throws Exception {
        Map<String, Object> input = (Map<String, Object>) c.get("builder_input");
        String docName = String.valueOf(c.get("document_name"));
        var tenant = GreenterXmlParityTest.tenant();
        String unsigned;
        String generatedName;
        if (docName.contains("-RC-")) {
            var s = TicketModelBuilder.summary(tenant, input);
            unsigned = TicketXmlBuilder.summary(s);
            generatedName = s.name();
        } else if (docName.contains("-RA-")) {
            var v = TicketModelBuilder.voided(tenant, input);
            unsigned = TicketXmlBuilder.voided(v);
            generatedName = v.name();
        } else {
            var note = new NoteModelBuilder(tenant, new TaxRateService()).build(input);
            unsigned = SunatSender.injectContratoColaboracion(NoteXmlBuilder.build(note),
                    input.get("contrato_colaboracion") instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
            generatedName = note.name();
        }
        assertThat(generatedName).isEqualTo(docName);
        if (c.get("sign_error") != null) {
            // Greenter no pudo firmar (texto con "&" fuera de CDATA); aquí se escapa y sí se firma
            assertThat(unsigned).contains("&amp;").contains("&lt;");
            return;
        }
        GreenterXmlParityTest.assertSame("sin firmar", (String) c.get("xml_unsigned"), unsigned);
        CertificateService.Material material;
        try (InputStream in = TicketAndNoteXmlParityTest.class.getResourceAsStream("/fixtures/certs/cert-con-clave.pem")) {
            material = new CertificateService().parse(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        }
        GreenterXmlParityTest.assertSame("firmado", (String) c.get("xml_signed"),
                XmlSigner.sign(unsigned, material.certificate(), material.privateKey()).xml());
    }
}
