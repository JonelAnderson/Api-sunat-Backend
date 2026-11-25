package pe.facturacion.api.sunat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.certificate.CertificateService;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.model.SaleModels.Invoice;
import pe.facturacion.api.sunat.sign.XmlSigner;
import pe.facturacion.api.sunat.ws.CdrReader;
import pe.facturacion.api.sunat.ws.SunatSoapClient;
import pe.facturacion.api.sunat.ws.SunatZip;
import pe.facturacion.api.sunat.xml.InvoiceXmlBuilder;

/**
 * {@code GreenterService::send()} para comprobantes con CDR síncrono: genera el XML, inyecta el
 * contrato de colaboración, firma, comprime, llama a {@code sendBill} y clasifica la respuesta
 * igual que API-PRO (0 = aceptado, 1000–3999 = rechazo, ≥ 4000 = aceptado con observación).
 */
@Service
public class SunatSender {

    private static final Logger log = LoggerFactory.getLogger(SunatSender.class);

    /** Error de configuración de la empresa (certificado): no es un rechazo de SUNAT. */
    public static class ConfigurationException extends RuntimeException {
        public ConfigurationException(String message) {
            super(message);
        }
    }

    /** Resultado con las mismas claves que el arreglo de {@code GreenterService::send}. */
    public record Result(boolean success, String xml, byte[] cdrZip, String hash, String code, String description,
            List<String> notes, boolean accepted, String errorCode, String errorMessage) {

        static Result ok(String xml, byte[] cdr, String hash, String code, String description, List<String> notes) {
            return new Result(true, xml, cdr, hash, code, description, notes, true, null, null);
        }

        static Result fail(String xml, String errorCode, String errorMessage) {
            return new Result(false, xml, null, null, null, null, null, false, errorCode, errorMessage);
        }
    }

    private final AppProperties props;
    private final CertificateStore certificates;
    private final CertificateService certificateService;
    private final SunatSoapClient soap;

    public SunatSender(AppProperties props, CertificateStore certificates, CertificateService certificateService, SunatSoapClient soap) {
        this.props = props;
        this.certificates = certificates;
        this.certificateService = certificateService;
        this.soap = soap;
    }

    /** Endpoint {@code billService} del entorno de la empresa. */
    public String endpoint(Tenant tenant) {
        return props.sunat().forEnvironment(tenant.getEnvironment()).fe();
    }

    /** Endpoint "otros CPE" (retenciones, percepciones y reversiones). */
    public String retentionEndpoint(Tenant tenant) {
        return props.sunat().forEnvironment(tenant.getEnvironment()).retention();
    }

    /** XML firmado (sin enviar): para pruebas y para {@code GET .../xml} antes del envío. */
    public XmlSigner.Signed sign(Tenant tenant, Invoice invoice, Map<String, Object> contrato) {
        return signXml(tenant, unsignedXml(invoice, contrato));
    }

    /** XML sin firmar de factura, boleta o nota (con el contrato de colaboración inyectado). */
    public static String unsignedXml(Invoice invoice, Map<String, Object> contrato) {
        String xml = invoice instanceof pe.facturacion.api.sunat.model.SaleModels.Note note
                ? pe.facturacion.api.sunat.xml.NoteXmlBuilder.build(note)
                : InvoiceXmlBuilder.build(invoice);
        return injectContratoColaboracion(xml, contrato);
    }

    /** Firma un XML con el certificado de la empresa. */
    public XmlSigner.Signed signXml(Tenant tenant, String unsigned) {
        CertificateService.Material material = material(tenant);
        return XmlSigner.sign(unsigned, material.certificate(), material.privateKey());
    }

    public Result send(Tenant tenant, Invoice invoice, Map<String, Object> contrato) {
        return sendBill(tenant, invoice.name(), signXml(tenant, unsignedXml(invoice, contrato)));
    }

    /** Resultado de {@code sendSummary}: ticket o error. */
    public record TicketResult(boolean success, String xml, String ticket, String errorCode, String errorMessage) {
    }

    /** {@code sendSummary} (resumen diario y comunicación de baja). */
    public TicketResult sendSummary(Tenant tenant, String name, String unsignedXml) {
        return sendSummary(tenant, endpoint(tenant), name, unsignedXml);
    }

    /** {@code sendSummary} contra un endpoint concreto (la reversión va al de retenciones). */
    public TicketResult sendSummary(Tenant tenant, String endpoint, String name, String unsignedXml) {
        String xml = signXml(tenant, unsignedXml).xml();
        byte[] zip = SunatZip.compress(name + ".xml", xml.getBytes(StandardCharsets.UTF_8), Clock.nowLima());
        SunatSoapClient.Response res = soap.sendSummary(endpoint, tenant.getRuc() + reveal(tenant.getSolUser()),
                reveal(tenant.getSolPass()), name + ".zip", zip);
        if (res.isFault()) {
            return new TicketResult(false, xml, null, res.fault().code(), res.fault().message());
        }
        String ticket = res.text("ticket");
        if (ticket == null || ticket.isBlank()) {
            return new TicketResult(false, xml, null, "", "Tipo de respuesta no soportado");
        }
        return new TicketResult(true, xml, ticket.trim(), null, null);
    }

    /** Resultado de {@code getStatus(ticket)}, con las claves de {@code GreenterService::getStatus}. */
    public record StatusResult(boolean success, byte[] cdrZip, String code, String description, List<String> notes,
            boolean accepted, String errorCode, String errorMessage) {
    }

    /**
     * {@code ExtService::getStatus}: 98 = en proceso (error con ese código); 0/99 = procesado
     * (requiere el CDR); un ResponseCode 100–1999 es excepción. Los fallos de red salen como
     * {@code NETWORK_ERROR}.
     */
    public StatusResult getStatus(Tenant tenant, String ticket) {
        return getStatus(tenant, endpoint(tenant), ticket);
    }

    public StatusResult getStatus(Tenant tenant, String endpoint, String ticket) {
        SunatSoapClient.Response res = soap.getStatus(endpoint, tenant.getRuc() + reveal(tenant.getSolUser()),
                reveal(tenant.getSolPass()), ticket);
        if (res.isFault()) {
            String code = res.fault().code();
            String msg = res.fault().message();
            String lower = msg == null ? "" : msg.toLowerCase(java.util.Locale.ROOT);
            boolean network = code == null || !code.matches("\\d+") || lower.contains("failed to process")
                    || lower.contains("could not connect") || lower.contains("connection refused") || lower.contains("timed out");
            return new StatusResult(false, null, null, null, List.of(), false, network ? "NETWORK_ERROR" : code, msg);
        }
        String originCode = res.text("statusCode");
        int code;
        try {
            code = Integer.parseInt(originCode == null ? "" : originCode.trim());
        } catch (NumberFormatException e) {
            code = 0;
        }
        if (code == 98) {
            return new StatusResult(false, null, null, null, List.of(), false, originCode.trim(),
                    "El procesamiento del comprobante aún no ha terminado");
        }
        if (code == 0 || code == 99) {
            String content = res.text("content");
            if (content == null || content.isBlank()) {
                return new StatusResult(false, null, null, null, List.of(), false, "CDR",
                        "CDR no encontrado en código estado: 98 (Procesado).");
            }
            byte[] zip = java.util.Base64.getMimeDecoder().decode(content.trim());
            CdrReader.Cdr cdr = CdrReader.fromZip(zip);
            int cdrCode = cdr.intCode();
            if (cdrCode >= 100 && cdrCode <= 1999) {
                return new StatusResult(false, zip, null, null, List.of(), false, originCode.trim(), cdr.description());
            }
            return new StatusResult(true, zip, cdr.code() == null ? null : cdr.code().trim(), cdr.description(), cdr.notes(),
                    cdr.accepted(), null, null);
        }
        if (code >= 100 && code <= 1999) {
            return new StatusResult(false, null, null, null, List.of(), false, originCode.trim(), res.text("statusMessage"));
        }
        return new StatusResult(false, null, null, null, List.of(), false, originCode == null ? "" : originCode.trim(), res.text("statusMessage"));
    }

    /** {@code sendBill} de un XML ya firmado. */
    public Result sendBill(Tenant tenant, String name, XmlSigner.Signed signed) {
        return sendBill(tenant, endpoint(tenant), name, signed);
    }

    /** {@code sendBill} contra un endpoint concreto (retenciones y percepciones usan el de "otros CPE"). */
    public Result sendBill(Tenant tenant, String endpoint, String name, XmlSigner.Signed signed) {
        String xml = signed.xml();
        byte[] zip = SunatZip.compress(name + ".xml", xml.getBytes(StandardCharsets.UTF_8), Clock.nowLima());
        String user = tenant.getRuc() + reveal(tenant.getSolUser());
        SunatSoapClient.Response res = soap.sendBill(endpoint, user, reveal(tenant.getSolPass()), name + ".zip", zip);

        if (!res.isFault()) {
            String content = res.text("applicationResponse");
            if (content == null || content.isBlank()) {
                return logFailure(tenant, name, Result.fail(xml, "CDR", "CDR no encontrado. La respuesta del servicio de SUNAT no incluyó el CDR."));
            }
            byte[] cdrZip = java.util.Base64.getMimeDecoder().decode(content.trim());
            CdrReader.Cdr cdr = CdrReader.fromZip(cdrZip);
            String code = cdr.code() == null ? null : cdr.code().trim();
            boolean digits = code != null && code.matches("\\d+");
            boolean observation = digits && cdr.intCode() >= 4000;
            boolean rechazo = digits && cdr.intCode() >= 1000 && cdr.intCode() < 4000;
            if (rechazo) {
                return Result.fail(xml, code, cdr.description() == null ? "Rechazado por SUNAT" : cdr.description());
            }
            // BillSender: éxito cuando hay CDR; la aceptación la decide el código
            return Result.ok(xml, cdrZip, signed.digestValue(), code, cdr.description(), cdr.notes());
        }

        String errorCode = res.fault().code() == null ? "" : res.fault().code();
        String message = res.fault().message();
        Result failure = Result.fail(xml, errorCode, message);
        logFailure(tenant, name, failure);
        if (errorCode.matches("\\d+") && Integer.parseInt(errorCode.length() > 9 ? errorCode.substring(0, 9) : errorCode) >= 4000) {
            return Result.ok(xml, null, signed.digestValue(), errorCode, "Aceptado con observación", List.of(message));
        }
        return failure;
    }

    /** Respuesta de {@code getStatusCdr} (consulta de CDR de un comprobante ya enviado). */
    public record CdrStatus(boolean success, String code, String message, byte[] cdrZip, String description,
            List<String> notes, boolean accepted) {
    }

    /**
     * {@code ConsultCdrService::getStatusCdr()}: con CDR el código pasa a ser su ResponseCode;
     * los códigos 100–1999 son excepciones (success = false).
     */
    public CdrStatus consultCdr(Tenant tenant, String tipo, String serie, long numero) {
        String endpoint = props.sunat().forEnvironment(tenant.getEnvironment()).consultaCdr();
        SunatSoapClient.Response res = soap.getStatusCdr(endpoint, tenant.getRuc() + reveal(tenant.getSolUser()),
                reveal(tenant.getSolPass()), tenant.getRuc(), tipo, serie, numero);
        if (res.isFault()) {
            return new CdrStatus(false, res.fault().code(), res.fault().message(), null, null, List.of(), false);
        }
        String code = res.text("statusCode");
        String message = res.text("statusMessage");
        String content = res.text("content");
        byte[] zip = null;
        String description = null;
        List<String> notes = List.of();
        boolean accepted = false;
        if (content != null && !content.isBlank()) {
            zip = java.util.Base64.getMimeDecoder().decode(content.trim());
            CdrReader.Cdr cdr = CdrReader.fromZip(zip);
            code = cdr.code();
            description = cdr.description();
            notes = cdr.notes();
            accepted = cdr.accepted();
        }
        int n;
        try {
            n = Integer.parseInt(code == null ? "" : code.trim());
        } catch (NumberFormatException e) {
            n = 0;
        }
        boolean success = !(n >= 100 && n <= 1999);
        return new CdrStatus(success, code == null ? null : code.trim(), message, zip,
                description != null ? description : message, notes, accepted);
    }

    private Result logFailure(Tenant tenant, String documento, Result r) {
        // Sin XML ni credenciales en el log: solo documento, RUC, entorno y el error de SUNAT
        log.warn("sunat.send.failed documento={} ruc={} entorno={} error_code={} error_message={} transporte={}",
                documento, tenant.getRuc(), tenant.getEnvironment(), r.errorCode(), r.errorMessage(),
                r.errorCode() == null || !r.errorCode().matches("\\d+"));
        return r;
    }

    private CertificateService.Material material(Tenant tenant) {
        String pem = certificates.load(tenant);
        if (pem == null) {
            throw new ConfigurationException("Certificado digital no encontrado. "
                    + "Cargue el certificado .p12/.pfx de esta empresa desde su configuración.");
        }
        if (!pem.contains("PRIVATE KEY")) {
            throw new ConfigurationException("El certificado digital cargado no incluye la clave privada, así que no puede firmar "
                    + "comprobantes. Hay que volver a subir el archivo .pfx/.p12 entregado por la certificadora "
                    + "(el .cer que se descarga del portal de SUNAT solo tiene la parte pública).");
        }
        try {
            return certificateService.parse(pem);
        } catch (RuntimeException e) {
            throw new ConfigurationException("Error de certificado: " + e.getMessage());
        }
    }

    /**
     * {@code injectContratoColaboracion()}: {@code cac:ContractDocumentReference} antes de
     * {@code cac:AdditionalDocumentReference} o {@code cac:Signature} (Greenter no lo modela).
     */
    public static String injectContratoColaboracion(String xml, Map<String, Object> c) {
        if (c == null || c.isEmpty() || pe.facturacion.api.shared.php.Php.empty(c.get("numero"))) {
            return xml;
        }
        String porcentaje = pe.facturacion.api.shared.php.PhpMath.numberFormat(
                pe.facturacion.api.shared.php.Php.toFloat(pe.facturacion.api.shared.php.Php.coalesce(c, "porcentaje", 0L)), 2);
        String node = "<cac:ContractDocumentReference>"
                + "<cbc:ID>" + esc(c.get("numero")) + "</cbc:ID>"
                + "<cbc:DocumentTypeCode>" + esc(pe.facturacion.api.shared.php.Php.coalesce(c, "tipo", "")) + "</cbc:DocumentTypeCode>"
                + "<cbc:DocumentDescription>" + esc(pe.facturacion.api.shared.php.Php.coalesce(c, "descripcion", "")) + "</cbc:DocumentDescription>"
                + "<cac:IssuerParty><cac:PartyLegalEntity><cac:ShareholderParty>"
                + "<cbc:PartecipationPercent>" + porcentaje + "</cbc:PartecipationPercent>"
                + "</cac:ShareholderParty></cac:PartyLegalEntity></cac:IssuerParty>"
                + "</cac:ContractDocumentReference>";
        for (String anchor : List.of("<cac:AdditionalDocumentReference", "<cac:Signature")) {
            int pos = xml.indexOf(anchor);
            if (pos >= 0) {
                return xml.substring(0, pos) + node + xml.substring(pos);
            }
        }
        return xml;
    }

    /** {@code htmlspecialchars(ENT_XML1 | ENT_QUOTES)}. */
    private static String esc(Object v) {
        return pe.facturacion.api.shared.php.Php.toStr(v).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String reveal(pe.facturacion.api.shared.crypto.EncryptedString s) {
        return s == null || s.reveal() == null ? "" : s.reveal();
    }
}
