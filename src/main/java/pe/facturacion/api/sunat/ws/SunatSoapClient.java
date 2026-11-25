package pe.facturacion.api.sunat.ws;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Cliente SOAP 1.1 de los servicios de SUNAT ({@code billService}, {@code billConsultService}),
 * con el mismo sobre que el {@code SoapClient} de Greenter: operación en el namespace
 * {@code http://service.sunat.gob.pe}, hijos sin namespace y cabecera WS-Security
 * {@code UsernameToken} (usuario = RUC + usuario SOL, clave en texto).
 *
 * <p>Diferencias deliberadas: la clave SOL se escapa (Greenter la insertaba cruda) y el
 * certificado TLS de SUNAT se valida (Greenter desactivaba {@code verify_peer}).
 */
@Component
public class SunatSoapClient {

    static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    static final String PASSWORD_TEXT = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";

    /** Error SOAP o de transporte, con el código como lo calcula {@code BaseSunat::getErrorFromFault}. */
    public record Fault(String code, String message) {
    }

    /** Respuesta: el elemento de la operación ({@code sendBillResponse}...) o un {@link Fault}. */
    public record Response(Element payload, Fault fault) {

        public boolean isFault() {
            return fault != null;
        }

        /** Texto del primer descendiente con ese nombre local ({@code applicationResponse}, {@code ticket}...). */
        public String text(String localName) {
            Element e = find(payload, localName);
            return e == null ? null : e.getTextContent();
        }

        public Element element(String localName) {
            return find(payload, localName);
        }
    }

    private final HttpClient http;
    private final Duration timeout;

    public SunatSoapClient(AppProperties props) {
        int seconds = props.sunat() == null || props.sunat().httpTimeout() <= 0 ? 60 : props.sunat().httpTimeout();
        this.timeout = Duration.ofSeconds(seconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(seconds, 30))).build();
    }

    /** {@code sendBill}: devuelve el ZIP del CDR o el error. */
    public Response sendBill(String endpoint, String user, String password, String fileName, byte[] zip) {
        return call(endpoint, user, password, "urn:sendBill", "sendBill",
                "<fileName>" + esc(fileName) + "</fileName><contentFile>" + Base64.getEncoder().encodeToString(zip) + "</contentFile>");
    }

    /** {@code sendSummary}: devuelve el ticket. */
    public Response sendSummary(String endpoint, String user, String password, String fileName, byte[] zip) {
        return call(endpoint, user, password, "urn:sendSummary", "sendSummary",
                "<fileName>" + esc(fileName) + "</fileName><contentFile>" + Base64.getEncoder().encodeToString(zip) + "</contentFile>");
    }

    /** {@code getStatus(ticket)}. */
    public Response getStatus(String endpoint, String user, String password, String ticket) {
        return call(endpoint, user, password, "urn:getStatus", "getStatus", "<ticket>" + esc(ticket) + "</ticket>");
    }

    /** {@code getStatusCdr} del servicio de consulta. */
    public Response getStatusCdr(String endpoint, String user, String password, String ruc, String tipo, String serie, long numero) {
        return call(endpoint, user, password, "urn:getStatusCdr", "getStatusCdr",
                "<rucComprobante>" + esc(ruc) + "</rucComprobante><tipoComprobante>" + esc(tipo) + "</tipoComprobante>"
                        + "<serieComprobante>" + esc(serie) + "</serieComprobante><numeroComprobante>" + numero + "</numeroComprobante>");
    }

    Response call(String endpoint, String user, String password, String action, String operation, String children) {
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + SOAP_ENV + "\" xmlns:ns1=\"http://service.sunat.gob.pe\" xmlns:ns2=\"" + WSSE + "\">"
                + "<SOAP-ENV:Header><ns2:Security><ns2:UsernameToken><ns2:Username>" + esc(user) + "</ns2:Username>"
                + "<o:Password xmlns:o=\"" + WSSE + "\" Type=\"" + PASSWORD_TEXT + "\">" + esc(password) + "</o:Password>"
                + "</ns2:UsernameToken></ns2:Security></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><ns1:" + operation + ">" + children + "</ns1:" + operation + "></SOAP-ENV:Body></SOAP-ENV:Envelope>\n";
        HttpResponse<byte[]> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "\"" + action + "\"")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            return new Response(null, new Fault("HTTP", "Error Fetching http headers"));
        } catch (IOException e) {
            return new Response(null, new Fault("HTTP", "Could not connect to host"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(null, new Fault("HTTP", "Could not connect to host"));
        } catch (IllegalArgumentException e) {
            return new Response(null, new Fault("HTTP", "Unable to parse URL"));
        }
        return parse(res.statusCode(), res.body());
    }

    static Response parse(int status, byte[] body) {
        Document doc = null;
        try {
            if (body != null && body.length > 0) {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
                builder.setErrorHandler(QUIET);
                doc = builder.parse(new ByteArrayInputStream(body));
            }
        } catch (Exception e) {
            doc = null;
        }
        Element soapBody = doc == null ? null : findNs(doc.getDocumentElement(), SOAP_ENV, "Body");
        if (soapBody != null) {
            Element fault = findNs(soapBody, SOAP_ENV, "Fault");
            if (fault != null) {
                return new Response(null, toFault(fault));
            }
            if (status >= 200 && status < 300) {
                Element payload = firstElement(soapBody);
                if (payload != null) {
                    return new Response(payload, null);
                }
            }
        }
        if (status >= 200 && status < 300) {
            return new Response(null, new Fault("Client", "looks like we got no XML document"));
        }
        return new Response(null, new Fault("HTTP", reason(status)));
    }

    /** {@code BaseSunat::getErrorFromFault}: código = dígitos de faultcode o de faultstring. */
    static Fault toFault(Element fault) {
        String faultcode = textOf(find(fault, "faultcode"));
        String faultstring = textOf(find(fault, "faultstring"));
        Element detail = find(fault, "detail");
        String code = faultcode;
        String digits = faultcode.replaceAll("[^0-9]+", "");
        if (digits.isEmpty() && !faultstring.isEmpty()) {
            digits = faultstring.replaceAll("[^0-9]+", "");
        }
        if (!digits.isEmpty()) {
            code = digits;
        }
        String message = faultstring;
        if (detail != null) {
            message += " | " + PhpJson.encode(detailToMap(detail));
        }
        return new Fault(code, message);
    }

    private static Object detailToMap(Element e) {
        Map<String, Object> map = new LinkedHashMap<>();
        NodeList list = e.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element c) {
                map.put(c.getLocalName() == null ? c.getNodeName() : c.getLocalName(),
                        firstElement(c) == null ? c.getTextContent() : detailToMap(c));
            }
        }
        return map.isEmpty() ? e.getTextContent() : map;
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "HTTP " + status;
        };
    }

    static Element find(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        if (list.getLength() > 0) {
            return (Element) list.item(0);
        }
        list = parent.getElementsByTagName(localName);
        return list.getLength() > 0 ? (Element) list.item(0) : null;
    }

    private static Element findNs(Element parent, String ns, String localName) {
        if (parent == null) {
            return null;
        }
        if (ns.equals(parent.getNamespaceURI()) && localName.equals(parent.getLocalName())) {
            return parent;
        }
        NodeList list = parent.getElementsByTagNameNS(ns, localName);
        return list.getLength() > 0 ? (Element) list.item(0) : null;
    }

    private static Element firstElement(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static String textOf(Element e) {
        return e == null ? "" : e.getTextContent().trim();
    }

    /** Sin trazas del parser en stderr cuando SUNAT responde HTML (404, 401...). */
    public static final org.xml.sax.ErrorHandler QUIET = new org.xml.sax.helpers.DefaultHandler() {
        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
            throw e;
        }
    };

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
