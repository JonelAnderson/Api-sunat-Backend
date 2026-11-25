package pe.facturacion.api.sunat.sign;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.xml.security.c14n.Canonicalizer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Firma XMLDSig enveloped idéntica a {@code Greenter\XMLSecLibs\Sunat\SignedXml}:
 * RSA-SHA1, digest SHA-1, C14N 1.0 inclusiva sin comentarios, {@code Reference URI=""},
 * {@code <ds:Signature Id="GreenterSign">} en el primer {@code ext:ExtensionContent} vacío y
 * {@code KeyInfo/X509Data/X509Certificate} en una sola línea.
 *
 * <p>La salida reproduce el {@code saveXML()} de libxml2: declaración + salto de línea, la raíz
 * en una línea, elementos vacíos como {@code <a/>} y salto de línea final.
 */
public final class XmlSigner {

    public static final String DS = "http://www.w3.org/2000/09/xmldsig#";
    static final String EXT = "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2";
    static final String C14N = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";
    static final String RSA_SHA1 = "http://www.w3.org/2000/09/xmldsig#rsa-sha1";
    static final String SHA1 = "http://www.w3.org/2000/09/xmldsig#sha1";
    static final String ENVELOPED = "http://www.w3.org/2000/09/xmldsig#enveloped-signature";

    private static final Pattern EMPTY_ELEMENT = Pattern.compile("<([A-Za-z_][\\w.:-]*)((?:\\s[^<>]*)?)></\\1>");
    private static final Pattern DECLARATION = Pattern.compile("^<\\?xml[^>]*\\?>");

    static {
        org.apache.xml.security.Init.init();
    }

    /** Resultado de la firma: XML firmado y DigestValue (hash del CPE para PDF/QR). */
    public record Signed(String xml, String digestValue) {
    }

    private XmlSigner() {
    }

    public static Signed sign(String unsignedXml, X509Certificate certificate, PrivateKey key) {
        try {
            Document doc = parse(unsignedXml);
            Canonicalizer c14n = Canonicalizer.getInstance(Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS);

            // 1. Digest del documento sin firma (equivale a la transformación enveloped)
            ByteArrayOutputStream docC14n = new ByteArrayOutputStream();
            c14n.canonicalizeSubtree(doc, docC14n);
            String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(docC14n.toByteArray()));

            // 2. SignedInfo canónico: C14N inclusiva de un subárbol = arrastra todas las
            //    declaraciones de namespace en ámbito (las de los ancestros del destino),
            //    ordenadas: la de por defecto primero y luego por prefijo.
            Element target = signatureTarget(doc);
            TreeMap<String, String> ns = new TreeMap<>();
            for (Node n = target; n instanceof Element e; n = n.getParentNode()) {
                NamedNodeMap attrs = e.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    Node a = attrs.item(i);
                    String name = a.getNodeName();
                    if (name.equals("xmlns") || name.startsWith("xmlns:")) {
                        String prefix = name.equals("xmlns") ? "" : name.substring(6);
                        ns.putIfAbsent(prefix, a.getNodeValue());
                    }
                }
            }
            ns.putIfAbsent("ds", DS);
            StringBuilder decls = new StringBuilder();
            ns.forEach((prefix, uri) -> {
                if (prefix.isEmpty() && uri.isEmpty()) {
                    return;
                }
                decls.append(prefix.isEmpty() ? " xmlns=\"" : " xmlns:" + prefix + "=\"").append(escapeC14nAttr(uri)).append('"');
            });
            String signedInfo = "<ds:SignedInfo" + decls + ">"
                    + "<ds:CanonicalizationMethod Algorithm=\"" + C14N + "\"></ds:CanonicalizationMethod>"
                    + "<ds:SignatureMethod Algorithm=\"" + RSA_SHA1 + "\"></ds:SignatureMethod>"
                    + "<ds:Reference URI=\"\"><ds:Transforms><ds:Transform Algorithm=\"" + ENVELOPED + "\"></ds:Transform></ds:Transforms>"
                    + "<ds:DigestMethod Algorithm=\"" + SHA1 + "\"></ds:DigestMethod>"
                    + "<ds:DigestValue>" + digest + "</ds:DigestValue></ds:Reference></ds:SignedInfo>";

            Signature rsa = Signature.getInstance("SHA1withRSA");
            rsa.initSign(key);
            rsa.update(signedInfo.getBytes(StandardCharsets.UTF_8));
            String signatureValue = Base64.getEncoder().encodeToString(rsa.sign());
            String certificateB64 = Base64.getEncoder().encodeToString(certificate.getEncoded());

            String signatureXml = "<ds:Signature Id=\"GreenterSign\"><ds:SignedInfo>"
                    + "<ds:CanonicalizationMethod Algorithm=\"" + C14N + "\"/>"
                    + "<ds:SignatureMethod Algorithm=\"" + RSA_SHA1 + "\"/>"
                    + "<ds:Reference URI=\"\"><ds:Transforms><ds:Transform Algorithm=\"" + ENVELOPED + "\"/></ds:Transforms>"
                    + "<ds:DigestMethod Algorithm=\"" + SHA1 + "\"/>"
                    + "<ds:DigestValue>" + digest + "</ds:DigestValue></ds:Reference></ds:SignedInfo>"
                    + "<ds:SignatureValue>" + signatureValue + "</ds:SignatureValue>"
                    + "<ds:KeyInfo><ds:X509Data><ds:X509Certificate>" + certificateB64
                    + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></ds:Signature>";

            return new Signed(serialize(unsignedXml, signatureXml), digest);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el XML: " + e.getMessage(), e);
        }
    }

    /** Serialización tipo libxml2 del XML de Greenter con la firma insertada. */
    static String serialize(String unsignedXml, String signatureXml) {
        java.util.regex.Matcher decl = DECLARATION.matcher(unsignedXml);
        String declaration = "<?xml version=\"1.0\"?>";
        String body = unsignedXml;
        if (decl.find()) {
            declaration = decl.group();
            body = unsignedXml.substring(decl.end());
        }
        body = body.strip();
        body = body.replace("<cac:AdditionalItemProperty >", "<cac:AdditionalItemProperty>");
        body = EMPTY_ELEMENT.matcher(body).replaceAll("<$1$2/>");
        String marker = "<ext:ExtensionContent/>";
        int at = body.indexOf(marker);
        if (at >= 0) {
            body = body.substring(0, at) + "<ext:ExtensionContent>" + signatureXml + "</ext:ExtensionContent>"
                    + body.substring(at + marker.length());
        } else {
            int close = body.lastIndexOf("</");
            body = body.substring(0, close) + signatureXml + body.substring(close);
        }
        return declaration + "\n" + body + "\n";
    }

    /** Primer {@code ext:ExtensionContent} sin contenido; si no hay, la raíz. */
    private static Element signatureTarget(Document doc) {
        NodeList list = doc.getElementsByTagNameNS(EXT, "ExtensionContent");
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getTextContent() == null || n.getTextContent().strip().isEmpty()) {
                return (Element) n;
            }
        }
        return doc.getDocumentElement();
    }

    /** Escape de valores de atributo en C14N 1.0. */
    private static String escapeC14nAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                .replace("\t", "&#x9;").replace("\n", "&#xA;").replace("\r", "&#xD;");
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(pe.facturacion.api.sunat.ws.SunatSoapClient.QUIET);
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
