package pe.facturacion.api.sunat.xml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Los XML firmados contra los XSD OASIS UBL 2.1 ({@code src/test/resources/xsd/ubl-2.1}). Se validan
 * los de las fixtures doradas: las pruebas de paridad exigen que el XML de esta API sea idéntico a
 * esos. El de la liquidación de compra (sin oráculo) se valida en {@code LiquidacionCompraFlowIT}.
 */
public class UblXsdTest {

    static final Pattern ROOT = Pattern.compile("<\\?xml[^>]*\\?>\\s*<([A-Za-z]+)");

    /** Esquema del documento raíz (Invoice, CreditNote, DebitNote, DespatchAdvice, SelfBilledInvoice). */
    public static Schema schema(String root) throws Exception {
        URL url = UblXsdTest.class.getResource("/xsd/ubl-2.1/maindoc/UBL-" + root + "-2.1.xsd");
        SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        return f.newSchema(url);
    }

    public static void validate(String xml) throws Exception {
        Matcher m = ROOT.matcher(xml);
        assertThat(m.find()).as("documento raíz").isTrue();
        schema(m.group(1)).newValidator().validate(new StreamSource(new StringReader(xml)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void xmlFirmadosValidanContraUbl21() throws Exception {
        Map<String, Integer> validados = new TreeMap<>();
        List<String> fallas = new ArrayList<>();
        for (String f : List.of("fact_bol", "fase4", "fase5")) {
            String json = new String(UblXsdTest.class.getResourceAsStream("/fixtures/oracle/" + f + ".json").readAllBytes(), StandardCharsets.UTF_8);
            for (Map<String, Object> r : (List<Map<String, Object>>) PhpJson.decode(json)) {
                Object xml = r.get("xml_signed");
                if (xml == null) {
                    continue;
                }
                Matcher m = ROOT.matcher((String) xml);
                m.find();
                String root = m.group(1);
                if (!List.of("Invoice", "CreditNote", "DebitNote", "DespatchAdvice").contains(root)) {
                    continue; // esquemas SUNAT 2.0 no disponibles (ver README del directorio xsd)
                }
                try {
                    validate((String) xml);
                    validados.merge(root, 1, Integer::sum);
                } catch (Exception e) {
                    fallas.add(f + " / " + r.get("name") + ": " + e.getMessage());
                }
            }
        }
        assertThat(fallas).as(String.join("\n", fallas)).isEmpty();
        assertThat(validados).containsEntry("Invoice", 31).containsEntry("CreditNote", 6).containsEntry("DebitNote", 6)
                .containsEntry("DespatchAdvice", 14);
    }
}
