package pe.facturacion.api.sunat.xml;

import static pe.facturacion.api.sunat.xml.XmlWriter.nFormat;

import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.sunat.model.SaleModels.Company;
import pe.facturacion.api.sunat.model.TicketModels.Summary;
import pe.facturacion.api.sunat.model.TicketModels.SummaryDetail;
import pe.facturacion.api.sunat.model.TicketModels.Voided;
import pe.facturacion.api.sunat.model.TicketModels.VoidedDetail;

/** {@code summary.xml.twig} (RC, SUNAT 1.1) y {@code voided.xml.twig} (RA, SUNAT 1.0) de Greenter 5.2. */
public final class TicketXmlBuilder {

    private TicketXmlBuilder() {
    }

    public static String summary(Summary doc) {
        XmlWriter x = new XmlWriter();
        String cur = doc.moneda;
        x.raw(InvoiceXmlBuilder.DECLARATION);
        x.raw("<SummaryDocuments xmlns=\"urn:sunat:names:specification:ubl:peru:schema:xsd:SummaryDocuments-1\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
                + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
                + " xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\""
                + " xmlns:ext=\"urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2\""
                + " xmlns:sac=\"urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1\">");
        x.raw("<ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent/></ext:UBLExtension></ext:UBLExtensions>");
        x.el("cbc:UBLVersionID", "2.0");
        x.el("cbc:CustomizationID", "1.1");
        x.el("cbc:ID", doc.xmlId());
        x.el("cbc:ReferenceDate", doc.fecGeneracion.toString());
        x.el("cbc:IssueDate", doc.fecResumen.toString());
        InvoiceXmlBuilder.signature(x, doc.company);
        supplier(x, doc.company);
        int i = 0;
        for (SummaryDetail det : doc.details) {
            x.open("sac:SummaryDocumentsLine");
            x.el("cbc:LineID", ++i);
            x.el("cbc:DocumentTypeCode", det.tipoDoc);
            x.el("cbc:ID", det.serieNro);
            x.open("cac:AccountingCustomerParty")
                    .el("cbc:CustomerAssignedAccountID", det.clienteNro)
                    .el("cbc:AdditionalAccountID", det.clienteTipo)
                    .close("cac:AccountingCustomerParty");
            if (det.docReferencia != null) {
                x.open("cac:BillingReference").open("cac:InvoiceDocumentReference")
                        .el("cbc:ID", det.docReferencia.nroDoc)
                        .el("cbc:DocumentTypeCode", det.docReferencia.tipoDoc)
                        .close("cac:InvoiceDocumentReference").close("cac:BillingReference");
            }
            x.open("cac:Status").el("cbc:ConditionCode", det.estado).close("cac:Status");
            x.amount("sac:TotalAmount", cur, det.total);
            if (det.mtoOperGravadas != null) {
                payment(x, cur, det.mtoOperGravadas, "01");
            }
            if (Php.truthy(det.mtoOperExoneradas)) {
                payment(x, cur, det.mtoOperExoneradas, "02");
            }
            if (Php.truthy(det.mtoOperInafectas)) {
                payment(x, cur, det.mtoOperInafectas, "03");
            }
            if (Php.truthy(det.mtoOperExportacion)) {
                payment(x, cur, det.mtoOperExportacion, "04");
            }
            if (Php.truthy(det.mtoOperGratuitas)) {
                payment(x, cur, det.mtoOperGratuitas, "05");
            }
            if (Php.truthy(det.mtoOtrosCargos)) {
                x.open("cac:AllowanceCharge").el("cbc:ChargeIndicator", "true").amount("cbc:Amount", cur, det.mtoOtrosCargos)
                        .close("cac:AllowanceCharge");
            }
            if (Php.truthy(det.mtoIvap)) {
                tax(x, cur, nFormat(det.mtoIvap, 2), null, "1016", "IVAP", "VAT");
            } else {
                tax(x, cur, nFormat(det.mtoIGV, 2), det.porcentajeIgv == null ? null : nFormat(det.porcentajeIgv, 2), "1000", "IGV", "VAT");
            }
            if (Php.truthy(det.mtoISC)) {
                tax(x, cur, nFormat(det.mtoISC, 2), null, "2000", "ISC", "EXC");
            }
            if (Php.truthy(det.mtoOtrosTributos)) {
                tax(x, cur, nFormat(det.mtoOtrosTributos, 2), null, "9999", "OTROS", "OTH");
            }
            if (Php.truthy(det.mtoIcbper)) {
                tax(x, cur, nFormat(det.mtoIcbper, 2), null, "7152", "ICBPER", "OTH");
            }
            x.close("sac:SummaryDocumentsLine");
        }
        x.close("SummaryDocuments");
        return x.build();
    }

    public static String voided(Voided doc) {
        XmlWriter x = new XmlWriter();
        x.raw(InvoiceXmlBuilder.DECLARATION);
        x.raw("<VoidedDocuments xmlns=\"urn:sunat:names:specification:ubl:peru:schema:xsd:VoidedDocuments-1\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
                + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
                + " xmlns:ext=\"urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2\""
                + " xmlns:sac=\"urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1\""
                + " xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">");
        x.raw("<ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent/></ext:UBLExtension></ext:UBLExtensions>");
        x.el("cbc:UBLVersionID", "2.0");
        x.el("cbc:CustomizationID", "1.0");
        x.el("cbc:ID", doc.xmlId());
        x.el("cbc:ReferenceDate", doc.fecGeneracion.toString());
        x.el("cbc:IssueDate", doc.fecComunicacion.toString());
        InvoiceXmlBuilder.signature(x, doc.company);
        supplier(x, doc.company);
        int i = 0;
        for (VoidedDetail det : doc.details) {
            x.open("sac:VoidedDocumentsLine")
                    .el("cbc:LineID", ++i)
                    .el("cbc:DocumentTypeCode", det.tipoDoc)
                    .el("sac:DocumentSerialID", det.serie)
                    .el("sac:DocumentNumberID", det.correlativo)
                    .cdata("sac:VoidReasonDescription", det.desMotivoBaja)
                    .close("sac:VoidedDocumentsLine");
        }
        x.close("VoidedDocuments");
        return x.build();
    }

    private static void supplier(XmlWriter x, Company emp) {
        x.open("cac:AccountingSupplierParty")
                .el("cbc:CustomerAssignedAccountID", emp.ruc)
                .el("cbc:AdditionalAccountID", "6")
                .open("cac:Party").open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", emp.razonSocial)
                .close("cac:PartyLegalEntity").close("cac:Party")
                .close("cac:AccountingSupplierParty");
    }

    private static void payment(XmlWriter x, String cur, Double amount, String instruction) {
        x.open("sac:BillingPayment").amount("cbc:PaidAmount", cur, amount).el("cbc:InstructionID", instruction).close("sac:BillingPayment");
    }

    private static void tax(XmlWriter x, String cur, String amount, String percent, String id, String name, String code) {
        x.open("cac:TaxTotal");
        x.el("cbc:TaxAmount", "currencyID", cur, amount);
        x.open("cac:TaxSubtotal");
        x.el("cbc:TaxAmount", "currencyID", cur, amount);
        x.open("cac:TaxCategory");
        if (percent != null) {
            x.el("cbc:Percent", percent);
        }
        x.open("cac:TaxScheme").el("cbc:ID", id).el("cbc:Name", name).el("cbc:TaxTypeCode", code).close("cac:TaxScheme");
        x.close("cac:TaxCategory").close("cac:TaxSubtotal").close("cac:TaxTotal");
    }
}
