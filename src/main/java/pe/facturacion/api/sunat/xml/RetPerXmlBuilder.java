package pe.facturacion.api.sunat.xml;

import static pe.facturacion.api.sunat.xml.XmlWriter.nFormat;

import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.sunat.model.RetPerModels.Detail;
import pe.facturacion.api.sunat.model.RetPerModels.Doc;
import pe.facturacion.api.sunat.model.RetPerModels.Payment;
import pe.facturacion.api.sunat.model.SaleModels.Address;
import pe.facturacion.api.sunat.model.SaleModels.Company;

/** {@code retention.xml.twig} y {@code perception.xml.twig} de Greenter 5.2 (UBL 2.0, autoescape desactivado). */
public final class RetPerXmlBuilder {

    private RetPerXmlBuilder() {
    }

    public static String build(Doc doc) {
        String root = doc.perception ? "Perception" : "Retention";
        String sys = doc.perception ? "Perception" : "Retention";
        XmlWriter x = new XmlWriter();
        x.raw(InvoiceXmlBuilder.DECLARATION);
        x.raw("<" + root + " xmlns=\"urn:sunat:names:specification:ubl:peru:schema:xsd:" + root + "-1\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
                + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
                + " xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\""
                + " xmlns:ext=\"urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2\""
                + " xmlns:sac=\"urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1\">");
        x.raw("<ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent/></ext:UBLExtension></ext:UBLExtensions>");
        x.el("cbc:UBLVersionID", "2.0");
        x.el("cbc:CustomizationID", "1.0");
        Company emp = doc.company;
        InvoiceXmlBuilder.signature(x, emp);
        x.el("cbc:ID", doc.serie + "-" + doc.correlativo);
        x.el("cbc:IssueDate", doc.fechaEmision.toLocalDate().toString());
        x.el("cbc:IssueTime", doc.fechaEmision.toLocalTime().format(InvoiceXmlBuilder.TIME));

        x.open("cac:AgentParty");
        x.open("cac:PartyIdentification").el("cbc:ID", "schemeID", "6", emp.ruc).close("cac:PartyIdentification");
        x.open("cac:PartyName").cdata("cbc:Name", emp.nombreComercial).close("cac:PartyName");
        Address addr = emp.address;
        x.open("cac:PostalAddress")
                .el("cbc:ID", addr.ubigueo)
                .cdata("cbc:StreetName", addr.direccion)
                .el("cbc:CityName", addr.departamento)
                .el("cbc:CountrySubentity", addr.provincia)
                .el("cbc:District", addr.distrito)
                .open("cac:Country").el("cbc:IdentificationCode", addr.codigoPais).close("cac:Country")
                .close("cac:PostalAddress");
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", emp.razonSocial).close("cac:PartyLegalEntity");
        x.close("cac:AgentParty");

        x.open("cac:ReceiverParty");
        x.open("cac:PartyIdentification").el("cbc:ID", "schemeID", doc.tercero.tipoDoc, doc.tercero.numDoc).close("cac:PartyIdentification");
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", doc.tercero.rznSocial).close("cac:PartyLegalEntity");
        x.close("cac:ReceiverParty");

        x.el("sac:SUNAT" + sys + "SystemCode", doc.regimen);
        x.el("sac:SUNAT" + sys + "Percent", nFormat(doc.tasa, 2));
        if (Php.truthy(doc.observacion)) {
            x.cdata("cbc:Note", doc.observacion);
        }
        x.amount("cbc:TotalInvoiceAmount", "PEN", doc.impMonto);
        x.amount(doc.perception ? "sac:SUNATTotalCashed" : "sac:SUNATTotalPaid", "PEN", doc.impNeto);
        for (Detail det : doc.details) {
            x.open("sac:SUNAT" + sys + "DocumentReference");
            x.el("cbc:ID", "schemeID", det.tipoDoc, det.numDoc);
            x.el("cbc:IssueDate", det.fechaEmision.toString());
            x.amount("cbc:TotalInvoiceAmount", det.moneda, det.impTotal);
            int i = 0;
            for (Payment pay : det.pagos) {
                x.open("cac:Payment")
                        .el("cbc:ID", ++i)
                        .amount("cbc:PaidAmount", pay.moneda, pay.importe)
                        .el("cbc:PaidDate", pay.fecha.toString())
                        .close("cac:Payment");
            }
            if (det.impMonto != 0 && det.impNeto != 0 && det.fecha != null) {
                x.open("sac:SUNAT" + sys + "Information");
                x.amount("sac:SUNAT" + sys + "Amount", "PEN", det.impMonto);
                x.el("sac:SUNAT" + sys + "Date", det.fecha.toString());
                x.amount(doc.perception ? "sac:SUNATNetTotalCashed" : "sac:SUNATNetTotalPaid", "PEN", det.impNeto);
                if (det.tipoCambio != null) {
                    x.open("cac:ExchangeRate")
                            .el("cbc:SourceCurrencyCode", det.tipoCambio.monedaRef)
                            .el("cbc:TargetCurrencyCode", det.tipoCambio.monedaObj)
                            .el("cbc:CalculationRate", nFormat(det.tipoCambio.factor, 6))
                            .el("cbc:Date", det.tipoCambio.fecha.toString())
                            .close("cac:ExchangeRate");
                }
                x.close("sac:SUNAT" + sys + "Information");
            }
            x.close("sac:SUNAT" + sys + "DocumentReference");
        }
        x.raw("</" + root + ">");
        return x.build();
    }
}
