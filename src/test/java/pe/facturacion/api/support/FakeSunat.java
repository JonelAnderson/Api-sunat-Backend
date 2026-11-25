package pe.facturacion.api.support;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * SUNAT simulado para pruebas: {@code billService} (sendBill) que valida la firma XMLDSig del
 * comprobante y responde un CDR con el código configurado por documento; además recibe webhooks.
 */
public final class FakeSunat implements AutoCloseable {

    /** Comprobante recibido por sendBill. */
    public record Received(String fileName, String xml, boolean signatureValid, String soapUser, String soapPassword) {
    }

    /** Webhook recibido. */
    public record Webhook(String body, String signature, String event) {
    }

    private final HttpServer server;
    public final List<Received> received = new CopyOnWriteArrayList<>();
    public final List<Webhook> webhooks = new CopyOnWriteArrayList<>();
    /** {@code serie-correlativo} → código de respuesta (por defecto 0). Valor "FAULT:xxxx" responde un soap:Fault. */
    public final Map<String, String> responses = new ConcurrentHashMap<>();

    public FakeSunat() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/billService", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply;
            int status = 200;
            try {
                reply = handleSoap(body);
                if (reply.contains("<soap-env:Fault>")) {
                    status = 500;
                }
            } catch (Exception e) {
                reply = fault("soap-env:Server", "Error interno " + e.getMessage());
                status = 500;
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        // Servicio "otros CPE" (retenciones, percepciones y reversiones): mismo SOAP, otra ruta
        server.createContext("/otroscpe/billService", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply;
            int status = 200;
            try {
                otrosCpe.add(body.contains("<ns1:getStatus>") ? "getStatus:" + tag(body, "ticket") : tag(body, "fileName"));
                reply = handleSoap(body);
                if (reply.contains("<soap-env:Fault>")) {
                    status = 500;
                }
            } catch (Exception e) {
                reply = fault("soap-env:Server", "Error interno " + e.getMessage());
                status = 500;
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.createContext("/gre", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath().substring("/gre".length());
            int status = 200;
            String reply;
            try {
                String[] r = handleGre(ex.getRequestMethod(), path, body, ex.getRequestHeaders().getFirst("Authorization"));
                status = Integer.parseInt(r[0]);
                reply = r[1];
            } catch (Exception e) {
                status = 500;
                reply = "{\"cod\":\"500\",\"msg\":\"" + e.getMessage() + "\"}";
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.createContext("/webhook", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            webhooks.add(new Webhook(body, ex.getRequestHeaders().getFirst("X-Signature"), ex.getRequestHeaders().getFirst("X-Webhook-Event")));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Tickets emitidos: ticket → [nombre del archivo, código de respuesta, consultas pendientes]. */
    public final Map<String, String[]> ticketsByNumber = new ConcurrentHashMap<>();
    /** Consultas "98 en proceso" que devuelve cada ticket antes de resolverse. */
    public volatile int pendingPolls = 1;
    private final java.util.concurrent.atomic.AtomicInteger ticketSeq = new java.util.concurrent.atomic.AtomicInteger(1000);

    /** Peticiones al servicio "otros CPE" (nombre de archivo o "getStatus:ticket"). */
    public final List<String> otrosCpe = new CopyOnWriteArrayList<>();

    /** Guía recibida por la API GRE. */
    public record GreReceived(String name, String xml, boolean signatureValid, boolean hashValid, String token) {
    }

    public final List<GreReceived> greReceived = new CopyOnWriteArrayList<>();
    /** Formularios recibidos en el endpoint de token (para comprobar usuario y scope). */
    public final List<String> greTokenRequests = new CopyOnWriteArrayList<>();
    /** Tokens que el proveedor ya no reconoce ("Token NO existe"). */
    public final java.util.Set<String> revokedTokens = ConcurrentHashMap.newKeySet();
    /** Ticket GRE → [nombre, código de respuesta, consultas pendientes]. */
    public final Map<String, String[]> greTickets = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger tokenSeq = new java.util.concurrent.atomic.AtomicInteger();

    private String[] handleGre(String method, String path, String body, String auth) throws Exception {
        if (method.equals("POST") && path.matches("/clientessol/[^/]+/oauth2/token/")) {
            greTokenRequests.add(java.net.URLDecoder.decode(body, StandardCharsets.UTF_8));
            return new String[] {"200", "{\"access_token\":\"tok-" + tokenSeq.incrementAndGet() + "\",\"token_type\":\"JWT\",\"expires_in\":3600}"};
        }
        String token = auth == null ? "" : auth.replace("Bearer ", "");
        if (!token.startsWith("tok-") || revokedTokens.contains(token)) {
            return new String[] {"401", "{\"msg\":\"Token NO existe\"}"};
        }
        if (method.equals("POST") && path.startsWith("/contribuyente/gem/comprobantes/")) {
            String name = path.substring("/contribuyente/gem/comprobantes/".length());
            Matcher am = Pattern.compile("\"arcGreZip\":\"([^\"]+)\"").matcher(body);
            String arc = am.find() ? am.group(1) : "";
            Matcher hm = Pattern.compile("\"hashZip\":\"([0-9a-f]+)\"").matcher(body);
            byte[] zip = Base64.getDecoder().decode(arc);
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(zip));
            String xml = unzip(zip);
            boolean valid = validSignature(xml);
            greReceived.add(new GreReceived(name, xml, valid, hm.find() && hm.group(1).equals(hash), token));
            String id = tag(xml, "cbc:ID");
            String code = responses.getOrDefault(id, valid ? "0" : "2335");
            if (code.startsWith("422:")) {
                return new String[] {"422", "{\"cod\":\"422\",\"msg\":\"Unprocessable Entity\",\"errors\":[{\"cod\":\"" + code.substring(4)
                        + "\",\"msg\":\"Error simulado " + code.substring(4) + "\"}]}"};
            }
            String ticket = java.util.UUID.randomUUID().toString();
            greTickets.put(ticket, new String[] {name, code, String.valueOf(pendingPolls)});
            return new String[] {"200", "{\"numTicket\":\"" + ticket + "\",\"fecRecepcion\":\"2026-10-24T10:15:30\"}"};
        }
        if (method.equals("GET") && path.startsWith("/contribuyente/gem/comprobantes/envios/")) {
            String[] t = greTickets.get(path.substring("/contribuyente/gem/comprobantes/envios/".length()));
            if (t == null) {
                return new String[] {"422", "{\"cod\":\"422\",\"msg\":\"x\",\"errors\":[{\"cod\":\"1033\",\"msg\":\"Ticket no existe\"}]}"};
            }
            int pending = Integer.parseInt(t[2]);
            if (pending > 0) {
                t[2] = String.valueOf(pending - 1);
                return new String[] {"200", "{\"codRespuesta\":\"98\"}"};
            }
            String id = t[0].substring(t[0].indexOf('-', 12) + 1);
            String cdr = Base64.getEncoder().encodeToString(cdrZip(t[0] + ".zip", id, t[1]));
            if ("0".equals(t[1])) {
                return new String[] {"200", "{\"codRespuesta\":\"0\",\"indCdrGenerado\":\"1\",\"arcCdr\":\"" + cdr + "\"}"};
            }
            return new String[] {"200", "{\"codRespuesta\":\"99\",\"indCdrGenerado\":\"1\",\"arcCdr\":\"" + cdr
                    + "\",\"error\":{\"numError\":\"" + t[1] + "\",\"desError\":\"Rechazo simulado " + t[1] + "\"}}"};
        }
        return new String[] {"404", "{\"msg\":\"no existe\"}"};
    }

    private String envelope(String inner) {
        return "<soap-env:Envelope xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap-env:Body>" + inner
                + "</soap-env:Body></soap-env:Envelope>";
    }

    private String handleSoap(String body) throws Exception {
        if (body.contains("<ns1:sendSummary>")) {
            String fileName = tag(body, "fileName");
            String xml = unzip(Base64.getDecoder().decode(tag(body, "contentFile")));
            boolean valid = validSignature(xml);
            received.add(new Received(fileName, xml, valid, tag(body, "ns2:Username"), null));
            String id = tag(xml, "cbc:ID");
            String ticket = String.valueOf(ticketSeq.incrementAndGet());
            ticketsByNumber.put(ticket, new String[] {fileName, responses.getOrDefault(id, valid ? "0" : "2335"), String.valueOf(pendingPolls)});
            return envelope("<br:sendSummaryResponse xmlns:br=\"http://service.sunat.gob.pe\"><ticket>" + ticket + "</ticket></br:sendSummaryResponse>");
        }
        if (body.contains("<ns1:getStatus>")) {
            String ticket = tag(body, "ticket");
            String[] t = ticketsByNumber.get(ticket);
            if (t == null) {
                return fault("soap-env:Client.0127", "El ticket no existe");
            }
            int pending = Integer.parseInt(t[2]);
            if (pending > 0) {
                t[2] = String.valueOf(pending - 1);
                return envelope("<br:getStatusResponse xmlns:br=\"http://service.sunat.gob.pe\"><status><statusCode>98</statusCode></status></br:getStatusResponse>");
            }
            String id = t[0].replace(".zip", "").substring(12);
            return envelope("<br:getStatusResponse xmlns:br=\"http://service.sunat.gob.pe\"><status><statusCode>"
                    + ("0".equals(t[1]) ? "0" : "99") + "</statusCode><content>" + Base64.getEncoder().encodeToString(cdrZip(t[0], id, t[1]))
                    + "</content></status></br:getStatusResponse>");
        }
        if (body.contains("<ns1:getStatusCdr>")) {
            String id = tag(body, "serieComprobante") + "-" + tag(body, "numeroComprobante");
            boolean known = received.stream().anyMatch(r -> r.xml() != null && id.equals(tag(r.xml(), "cbc:ID")));
            if (!known) {
                return envelope("<br:getStatusCdrResponse xmlns:br=\"http://service.sunat.gob.pe\"><statusCdr><statusCode>0127</statusCode>"
                        + "<statusMessage>El comprobante no existe</statusMessage></statusCdr></br:getStatusCdrResponse>");
            }
            String file = tag(body, "rucComprobante") + "-" + tag(body, "tipoComprobante") + "-" + id + ".zip";
            return envelope("<br:getStatusCdrResponse xmlns:br=\"http://service.sunat.gob.pe\"><statusCdr><statusCode>0004</statusCode>"
                    + "<statusMessage>La constancia existe</statusMessage><content>"
                    + Base64.getEncoder().encodeToString(cdrZip(file, id, responses.getOrDefault(id, "0")))
                    + "</content></statusCdr></br:getStatusCdrResponse>");
        }
        return handleBill(body);
    }

    private static String unzip(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e = in.getNextEntry();
            return e == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] cdrZip(String fileName, String id, String code) throws IOException {
        String description = code.equals("0") ? "El documento " + id + " ha sido aceptado" : "Rechazo simulado " + code;
        String cdr = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ar:ApplicationResponse xmlns:ar=\"urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
                + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">"
                + "<cbc:ID>1</cbc:ID>" + (code.startsWith("4") ? "<cbc:Note>" + code + " - Observación simulada</cbc:Note>" : "")
                + "<cac:DocumentResponse><cac:Response><cbc:ReferenceID>" + id + "</cbc:ReferenceID><cbc:ResponseCode>" + code
                + "</cbc:ResponseCode><cbc:Description>" + description + "</cbc:Description></cac:Response></cac:DocumentResponse>"
                + "</ar:ApplicationResponse>";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("dummy/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("R-" + fileName.replace(".zip", ".xml")));
            zos.write(cdr.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private String handleBill(String body) throws Exception {
        String fileName = tag(body, "fileName");
        String content = tag(body, "contentFile");
        String user = tag(body, "ns2:Username");
        Matcher pm = Pattern.compile("<o:Password[^>]*>([^<]*)</o:Password>").matcher(body);
        String pass = pm.find() ? pm.group(1) : null;
        byte[] zip = Base64.getDecoder().decode(content);
        String xml = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e = in.getNextEntry();
            if (e != null) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        boolean valid = validSignature(xml);
        received.add(new Received(fileName, xml, valid, user, pass));
        String id = tag(xml, "cbc:ID");
        String code = responses.getOrDefault(id, valid ? "0" : "2335");
        if (code.startsWith("FAULT:")) {
            return fault("soap-env:Client." + code.substring(6), "Error simulado " + code.substring(6));
        }
        String description = code.equals("0") ? "La Factura numero " + id + ", ha sido aceptada" : "Rechazo simulado " + code;
        String cdr = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ar:ApplicationResponse xmlns:ar=\"urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
                + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">"
                + "<cbc:ID>1</cbc:ID>" + (code.startsWith("4") ? "<cbc:Note>" + code + " - Observación simulada</cbc:Note>" : "")
                + "<cac:DocumentResponse><cac:Response><cbc:ReferenceID>" + id + "</cbc:ReferenceID><cbc:ResponseCode>" + code
                + "</cbc:ResponseCode><cbc:Description>" + description + "</cbc:Description></cac:Response></cac:DocumentResponse>"
                + "</ar:ApplicationResponse>";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("dummy/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("R-" + fileName.replace(".zip", ".xml")));
            zos.write(cdr.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return "<soap-env:Envelope xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap-env:Body>"
                + "<br:sendBillResponse xmlns:br=\"http://service.sunat.gob.pe\"><applicationResponse>"
                + Base64.getEncoder().encodeToString(bos.toByteArray())
                + "</applicationResponse></br:sendBillResponse></soap-env:Body></soap-env:Envelope>";
    }

    private static String fault(String code, String message) {
        return "<soap-env:Envelope xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap-env:Body><soap-env:Fault>"
                + "<faultcode>" + code + "</faultcode><faultstring>" + message + "</faultstring></soap-env:Fault></soap-env:Body></soap-env:Envelope>";
    }

    /** Valida la firma enveloped con la clave pública del X509Certificate incluido. */
    static boolean validSignature(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList sigs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
            if (sigs.getLength() != 1) {
                return false;
            }
            Element sig = (Element) sigs.item(0);
            String b64 = sig.getElementsByTagNameNS(XMLSignature.XMLNS, "X509Certificate").item(0).getTextContent();
            var cert = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
            DOMValidateContext ctx = new DOMValidateContext(cert.getPublicKey(), sig);
            // RSA-SHA1 está deshabilitado por defecto en la validación segura del JDK; SUNAT lo exige
            ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
            return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    private static String tag(String s, String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + "(?:\\s[^>]*)?>([^<]*)</" + Pattern.quote(name) + ">").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
