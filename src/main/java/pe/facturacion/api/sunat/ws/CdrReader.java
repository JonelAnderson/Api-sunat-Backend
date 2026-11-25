package pe.facturacion.api.sunat.ws;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Lectura del CDR ({@code ApplicationResponse}), como {@code Greenter\Ws\Reader\DomCdrReader}. */
public final class CdrReader {

    static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    /** Respuesta de SUNAT contenida en el CDR. */
    public record Cdr(String id, String code, String description, List<String> notes, String reference) {

        /** {@code CdrResponse::isAccepted()}: código 0 o ≥ 4000. */
        public boolean accepted() {
            int c = intCode();
            return c == 0 || c >= 4000;
        }

        public int intCode() {
            try {
                return Integer.parseInt(code == null ? "" : code.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private CdrReader() {
    }

    /** Lee el CDR desde el ZIP devuelto por SUNAT; lanza si está vacío o no es XML válido. */
    public static Cdr fromZip(byte[] zip) {
        byte[] xml = SunatZip.firstXml(zip);
        if (xml == null || xml.length == 0) {
            throw new IllegalStateException("XML del CDR no puede estar vacío.");
        }
        return read(xml);
    }

    public static Cdr read(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
            builder.setErrorHandler(SunatSoapClient.QUIET);
            Document doc = builder.parse(new ByteArrayInputStream(xml));
            Element root = doc.getDocumentElement();
            Element docResponse = firstChild(root, CAC, "DocumentResponse");
            Element response = firstChild(docResponse, CAC, "Response");
            Element docRef = firstChild(docResponse, CAC, "DocumentReference");
            List<String> notes = new ArrayList<>();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n instanceof Element e && CBC.equals(e.getNamespaceURI()) && "Note".equals(e.getLocalName())) {
                    notes.add(e.getTextContent());
                }
            }
            return new Cdr(
                    text(firstChild(response, CBC, "ReferenceID")),
                    text(firstChild(response, CBC, "ResponseCode")),
                    text(firstChild(response, CBC, "Description")),
                    notes,
                    text(firstChild(docRef, CBC, "DocumentDescription")));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el CDR: " + e.getMessage(), e);
        }
    }

    private static Element firstChild(Element parent, String ns, String local) {
        if (parent == null) {
            return null;
        }
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element e) {
        return e == null ? null : e.getTextContent();
    }
}
