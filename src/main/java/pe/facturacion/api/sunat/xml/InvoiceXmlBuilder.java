package pe.facturacion.api.sunat.xml;

import static pe.facturacion.api.sunat.xml.XmlWriter.nFormat;
import static pe.facturacion.api.sunat.xml.XmlWriter.nFormatLimit;

import java.time.format.DateTimeFormatter;
import java.util.List;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.sunat.model.SaleModels.Address;
import pe.facturacion.api.sunat.model.SaleModels.Charge;
import pe.facturacion.api.sunat.model.SaleModels.Client;
import pe.facturacion.api.sunat.model.SaleModels.Company;
import pe.facturacion.api.sunat.model.SaleModels.Cuota;
import pe.facturacion.api.sunat.model.SaleModels.Document;
import pe.facturacion.api.sunat.model.SaleModels.Invoice;
import pe.facturacion.api.sunat.model.SaleModels.Legend;
import pe.facturacion.api.sunat.model.SaleModels.Prepayment;
import pe.facturacion.api.sunat.model.SaleModels.SaleDetail;

/**
 * XML UBL 2.1 de factura y boleta: port literal de {@code invoice2.1.xml.twig} de Greenter 5.2
 * (mismo orden, mismas condiciones {@code if} / {@code is not null}, mismos formatos).
 */
public final class InvoiceXmlBuilder {

    static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private InvoiceXmlBuilder() {
    }

    public static String build(Invoice doc) {
        XmlWriter x = new XmlWriter();
        String cur = doc.tipoMoneda;
        Company emp = doc.company;
        x.raw(DECLARATION);
        x.raw("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
                + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
                + " xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\""
                + " xmlns:ext=\"urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2\">");
        x.raw("<ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent/></ext:UBLExtension></ext:UBLExtensions>");
        x.el("cbc:UBLVersionID", "2.1");
        x.el("cbc:CustomizationID", "2.0");
        x.el("cbc:ID", doc.serie + "-" + doc.correlativo);
        x.el("cbc:IssueDate", doc.fechaEmision.format(DATE));
        x.el("cbc:IssueTime", doc.fechaEmision.format(TIME));
        if (doc.fecVencimiento != null) {
            x.el("cbc:DueDate", doc.fecVencimiento.format(DATE));
        }
        x.el("cbc:InvoiceTypeCode", "listID", doc.tipoOperacion, doc.tipoDoc);
        if (doc.legends != null) {
            for (Legend leg : doc.legends) {
                x.openAttrs("cbc:Note", "languageLocaleID", Php.toStr(leg.code)).cdataText(leg.value).close("cbc:Note");
            }
        }
        x.el("cbc:DocumentCurrencyCode", cur);
        if (Php.truthy(doc.guias)) {
            for (Document guia : doc.guias) {
                x.open("cac:DespatchDocumentReference")
                        .el("cbc:ID", guia.nroDoc)
                        .el("cbc:DocumentTypeCode", guia.tipoDoc)
                        .close("cac:DespatchDocumentReference");
            }
        }
        if (Php.truthy(doc.anticipos)) {
            int i = 0;
            for (Prepayment ant : doc.anticipos) {
                i++;
                x.open("cac:AdditionalDocumentReference")
                        .el("cbc:ID", ant.nroDocRel)
                        .el("cbc:DocumentTypeCode", ant.tipoDocRel)
                        .el("cbc:DocumentStatusCode", i)
                        .open("cac:IssuerParty").open("cac:PartyIdentification")
                        .el("cbc:ID", "schemeID", "6", emp.ruc)
                        .close("cac:PartyIdentification").close("cac:IssuerParty")
                        .close("cac:AdditionalDocumentReference");
            }
        }
        signature(x, emp);
        supplier(x, emp);
        customer(x, "cac:AccountingCustomerParty", doc.client);
        if (doc.detraccion != null) {
            var detr = doc.detraccion;
            x.open("cac:PaymentMeans")
                    .el("cbc:ID", "Detraccion")
                    .el("cbc:PaymentMeansCode", detr.codMedioPago)
                    .open("cac:PayeeFinancialAccount").el("cbc:ID", detr.ctaBanco).close("cac:PayeeFinancialAccount")
                    .close("cac:PaymentMeans");
            x.open("cac:PaymentTerms")
                    .el("cbc:ID", "Detraccion")
                    .el("cbc:PaymentMeansID", detr.codBienDetraccion)
                    .el("cbc:PaymentPercent", detr.percent)
                    .amount("cbc:Amount", "PEN", detr.mount)
                    .close("cac:PaymentTerms");
        }
        if (doc.perception != null) {
            x.open("cac:PaymentTerms")
                    .el("cbc:ID", "Percepcion")
                    .amount("cbc:Amount", "PEN", doc.perception.mtoTotal)
                    .close("cac:PaymentTerms");
        }
        if (doc.formaPago != null) {
            x.open("cac:PaymentTerms").el("cbc:ID", "FormaPago").el("cbc:PaymentMeansID", doc.formaPago.tipo);
            if (Php.truthy(doc.formaPago.monto)) {
                x.amount("cbc:Amount", defaultIfEmpty(doc.formaPago.moneda, cur), doc.formaPago.monto);
            }
            x.close("cac:PaymentTerms");
        }
        if (Php.truthy(doc.cuotas)) {
            int i = 0;
            for (Cuota cuota : doc.cuotas) {
                i++;
                x.open("cac:PaymentTerms")
                        .el("cbc:ID", "FormaPago")
                        .el("cbc:PaymentMeansID", String.format("Cuota%03d", i))
                        .amount("cbc:Amount", defaultIfEmpty(cuota.moneda, cur), cuota.monto)
                        .el("cbc:PaymentDueDate", cuota.fechaPago == null ? "" : cuota.fechaPago.format(DATE))
                        .close("cac:PaymentTerms");
            }
        }
        if (Php.truthy(doc.anticipos)) {
            int i = 0;
            for (Prepayment ant : doc.anticipos) {
                i++;
                x.open("cac:PrepaidPayment").el("cbc:ID", i).amount("cbc:PaidAmount", cur, ant.total).close("cac:PrepaidPayment");
            }
        }
        if (Php.truthy(doc.cargos)) {
            for (Charge cargo : doc.cargos) {
                x.open("cac:AllowanceCharge")
                        .el("cbc:ChargeIndicator", "true")
                        .el("cbc:AllowanceChargeReasonCode", cargo.codTipo)
                        .el("cbc:MultiplierFactorNumeric", nFormatLimit(cargo.factor, 5))
                        .amount("cbc:Amount", cur, cargo.monto)
                        .amount("cbc:BaseAmount", cur, cargo.montoBase)
                        .close("cac:AllowanceCharge");
            }
        }
        if (Php.truthy(doc.descuentos)) {
            for (Charge desc : doc.descuentos) {
                x.open("cac:AllowanceCharge")
                        .el("cbc:ChargeIndicator", "false")
                        .el("cbc:AllowanceChargeReasonCode", desc.codTipo);
                if (desc.factor != null) {
                    x.el("cbc:MultiplierFactorNumeric", nFormatLimit(desc.factor, 5));
                }
                x.amount("cbc:Amount", cur, desc.monto)
                        .amount("cbc:BaseAmount", cur, desc.montoBase)
                        .close("cac:AllowanceCharge");
            }
        }
        if (doc.perception != null) {
            var perc = doc.perception;
            x.open("cac:AllowanceCharge")
                    .el("cbc:ChargeIndicator", "true")
                    .el("cbc:AllowanceChargeReasonCode", perc.codReg)
                    .el("cbc:MultiplierFactorNumeric", nFormatLimit(perc.porcentaje, 5))
                    .amount("cbc:Amount", "PEN", perc.mto)
                    .amount("cbc:BaseAmount", "PEN", perc.mtoBase)
                    .close("cac:AllowanceCharge");
        }
        taxTotal(x, doc, cur);
        x.open("cac:LegalMonetaryTotal");
        x.amount("cbc:LineExtensionAmount", cur, doc.valorVenta);
        if (doc.subTotal != null) {
            x.amount("cbc:TaxInclusiveAmount", cur, doc.subTotal);
        }
        if (doc.sumOtrosDescuentos != null) {
            x.amount("cbc:AllowanceTotalAmount", cur, doc.sumOtrosDescuentos);
        }
        if (doc.sumOtrosCargos != null) {
            x.amount("cbc:ChargeTotalAmount", cur, doc.sumOtrosCargos);
        }
        if (doc.totalAnticipos != null) {
            x.amount("cbc:PrepaidAmount", cur, doc.totalAnticipos);
        }
        if (doc.redondeo != null) {
            x.amount("cbc:PayableRoundingAmount", cur, doc.redondeo);
        }
        x.amount("cbc:PayableAmount", cur, doc.mtoImpVenta);
        x.close("cac:LegalMonetaryTotal");
        int line = 0;
        for (SaleDetail d : doc.details) {
            line(x, d, ++line, cur);
        }
        x.close("Invoice");
        return x.build();
    }

    static void signature(XmlWriter x, Company emp) {
        x.open("cac:Signature")
                .el("cbc:ID", "SIGN" + Php.toStr(emp.ruc))
                .open("cac:SignatoryParty")
                .open("cac:PartyIdentification").el("cbc:ID", emp.ruc).close("cac:PartyIdentification")
                .open("cac:PartyName").cdata("cbc:Name", emp.razonSocial).close("cac:PartyName")
                .close("cac:SignatoryParty")
                .open("cac:DigitalSignatureAttachment")
                .open("cac:ExternalReference").el("cbc:URI", "#GREENTER-SIGN").close("cac:ExternalReference")
                .close("cac:DigitalSignatureAttachment")
                .close("cac:Signature");
    }

    static void supplier(XmlWriter x, Company emp) {
        x.open("cac:AccountingSupplierParty").open("cac:Party");
        x.open("cac:PartyIdentification").el("cbc:ID", "schemeID", "6", emp.ruc).close("cac:PartyIdentification");
        if (Php.truthy(emp.nombreComercial)) {
            x.open("cac:PartyName").cdata("cbc:Name", emp.nombreComercial).close("cac:PartyName");
        }
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", emp.razonSocial);
        Address addr = emp.address;
        x.open("cac:RegistrationAddress")
                .el("cbc:ID", addr.ubigueo)
                .el("cbc:AddressTypeCode", addr.codLocal);
        if (Php.truthy(addr.urbanizacion)) {
            x.el("cbc:CitySubdivisionName", addr.urbanizacion);
        }
        x.el("cbc:CityName", addr.provincia)
                .el("cbc:CountrySubentity", addr.departamento)
                .el("cbc:District", addr.distrito)
                .open("cac:AddressLine").cdata("cbc:Line", addr.direccion).close("cac:AddressLine")
                .open("cac:Country").el("cbc:IdentificationCode", addr.codigoPais).close("cac:Country")
                .close("cac:RegistrationAddress")
                .close("cac:PartyLegalEntity");
        contact(x, emp.email, emp.telephone);
        x.close("cac:Party").close("cac:AccountingSupplierParty");
    }

    static void customer(XmlWriter x, String wrapper, Client client) {
        x.open(wrapper).open("cac:Party");
        x.open("cac:PartyIdentification").el("cbc:ID", "schemeID", client.tipoDoc, client.numDoc).close("cac:PartyIdentification");
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", client.rznSocial);
        if (client.address != null) {
            Address addr = client.address;
            x.open("cac:RegistrationAddress");
            if (Php.truthy(addr.ubigueo)) {
                x.el("cbc:ID", addr.ubigueo);
            }
            x.open("cac:AddressLine").cdata("cbc:Line", addr.direccion).close("cac:AddressLine")
                    .open("cac:Country").el("cbc:IdentificationCode", addr.codigoPais).close("cac:Country")
                    .close("cac:RegistrationAddress");
        }
        x.close("cac:PartyLegalEntity");
        contact(x, client.email, client.telephone);
        x.close("cac:Party").close(wrapper);
    }

    private static void contact(XmlWriter x, String email, String telephone) {
        if (Php.truthy(email) || Php.truthy(telephone)) {
            x.open("cac:Contact");
            if (Php.truthy(telephone)) {
                x.el("cbc:Telephone", telephone);
            }
            if (Php.truthy(email)) {
                x.el("cbc:ElectronicMail", email);
            }
            x.close("cac:Contact");
        }
    }

    private static void taxSubtotal(XmlWriter x, String cur, Object taxable, String taxAmount,
            String id, String name, String code) {
        x.open("cac:TaxSubtotal");
        if (taxable != null) {
            x.amount("cbc:TaxableAmount", cur, taxable);
        }
        x.el("cbc:TaxAmount", "currencyID", cur, taxAmount);
        x.open("cac:TaxCategory").open("cac:TaxScheme")
                .el("cbc:ID", id).el("cbc:Name", name).el("cbc:TaxTypeCode", code)
                .close("cac:TaxScheme").close("cac:TaxCategory");
        x.close("cac:TaxSubtotal");
    }

    static void taxTotal(XmlWriter x, Invoice doc, String cur) {
        x.open("cac:TaxTotal");
        x.amount("cbc:TaxAmount", cur, doc.totalImpuestos);
        if (Php.truthy(doc.mtoISC)) {
            taxSubtotal(x, cur, nn(doc.mtoBaseIsc), nFormat(doc.mtoISC, 2), "2000", "ISC", "EXC");
        }
        if (doc.mtoOperGravadas != null) {
            taxSubtotal(x, cur, doc.mtoOperGravadas, nFormat(doc.mtoIGV, 2), "1000", "IGV", "VAT");
        }
        if (doc.mtoOperInafectas != null) {
            taxSubtotal(x, cur, doc.mtoOperInafectas, "0", "9998", "INA", "FRE");
        }
        if (doc.mtoOperExoneradas != null) {
            taxSubtotal(x, cur, doc.mtoOperExoneradas, "0", "9997", "EXO", "VAT");
        }
        if (doc.mtoOperGratuitas != null) {
            taxSubtotal(x, cur, doc.mtoOperGratuitas, nFormat(doc.mtoIGVGratuitas, 2), "9996", "GRA", "FRE");
        }
        if (doc.mtoOperExportacion != null) {
            taxSubtotal(x, cur, doc.mtoOperExportacion, "0", "9995", "EXP", "FRE");
        }
        if (Php.truthy(doc.mtoIvap)) {
            taxSubtotal(x, cur, nn(doc.mtoBaseIvap), nFormat(doc.mtoIvap, 2), "1016", "IVAP", "VAT");
        }
        if (Php.truthy(doc.mtoOtrosTributos)) {
            taxSubtotal(x, cur, nn(doc.mtoBaseOth), nFormat(doc.mtoOtrosTributos, 2), "9999", "OTROS", "OTH");
        }
        if (Php.truthy(doc.icbper)) {
            taxSubtotal(x, cur, null, nFormat(doc.icbper, 2), "7152", "ICBPER", "OTH");
        }
        x.close("cac:TaxTotal");
    }

    /** Un importe {@code null} con filtro {@code n_format} imprime {@code 0.00} (no se omite). */
    private static Object nn(Double v) {
        return v == null ? 0.0 : v;
    }

    /** {@code getTributoAfect(tipAfeIgv)}: [id, name, code]. */
    static String[] tributoAfect(String tipAfeIgv) {
        return switch ((int) Php.toInt(tipAfeIgv)) {
            case 10 -> new String[] {"1000", "IGV", "VAT"};
            case 17 -> new String[] {"1016", "IVAP", "VAT"};
            case 20 -> new String[] {"9997", "EXO", "VAT"};
            case 30 -> new String[] {"9998", "INA", "FRE"};
            case 40 -> new String[] {"9995", "EXP", "FRE"};
            default -> new String[] {"9996", "GRA", "FRE"};
        };
    }

    private static void line(XmlWriter x, SaleDetail d, int index, String cur) {
        x.open("cac:InvoiceLine");
        x.el("cbc:ID", index);
        x.el("cbc:InvoicedQuantity", "unitCode", d.unidad, d.cantidad);
        x.amount("cbc:LineExtensionAmount", cur, d.mtoValorVenta);
        x.open("cac:PricingReference").open("cac:AlternativeConditionPrice");
        if (Php.truthy(d.mtoValorGratuito)) {
            x.el("cbc:PriceAmount", "currencyID", cur, nFormatLimit(d.mtoValorGratuito, 10)).el("cbc:PriceTypeCode", "02");
        } else {
            x.el("cbc:PriceAmount", "currencyID", cur, nFormatLimit(d.mtoPrecioUnitario, 10)).el("cbc:PriceTypeCode", "01");
        }
        x.close("cac:AlternativeConditionPrice").close("cac:PricingReference");
        allowances(x, d.cargos, "true", cur);
        allowances(x, d.descuentos, "false", cur);
        x.open("cac:TaxTotal");
        x.amount("cbc:TaxAmount", cur, d.totalImpuestos);
        if (Php.truthy(d.isc)) {
            x.open("cac:TaxSubtotal")
                    .amount("cbc:TaxableAmount", cur, d.mtoBaseIsc)
                    .amount("cbc:TaxAmount", cur, d.isc)
                    .open("cac:TaxCategory")
                    .el("cbc:Percent", d.porcentajeIsc)
                    .el("cbc:TierRange", d.tipSisIsc)
                    .open("cac:TaxScheme").el("cbc:ID", "2000").el("cbc:Name", "ISC").el("cbc:TaxTypeCode", "EXC").close("cac:TaxScheme")
                    .close("cac:TaxCategory")
                    .close("cac:TaxSubtotal");
        }
        String[] afect = tributoAfect(d.tipAfeIgv);
        x.open("cac:TaxSubtotal")
                .amount("cbc:TaxableAmount", cur, d.mtoBaseIgv)
                .amount("cbc:TaxAmount", cur, d.igv)
                .open("cac:TaxCategory")
                .el("cbc:Percent", d.porcentajeIgv)
                .el("cbc:TaxExemptionReasonCode", d.tipAfeIgv)
                .open("cac:TaxScheme").el("cbc:ID", afect[0]).el("cbc:Name", afect[1]).el("cbc:TaxTypeCode", afect[2]).close("cac:TaxScheme")
                .close("cac:TaxCategory")
                .close("cac:TaxSubtotal");
        if (Php.truthy(d.otroTributo)) {
            x.open("cac:TaxSubtotal")
                    .amount("cbc:TaxableAmount", cur, d.mtoBaseOth)
                    .amount("cbc:TaxAmount", cur, d.otroTributo)
                    .open("cac:TaxCategory")
                    .el("cbc:Percent", d.porcentajeOth)
                    .open("cac:TaxScheme").el("cbc:ID", "9999").el("cbc:Name", "OTROS").el("cbc:TaxTypeCode", "OTH").close("cac:TaxScheme")
                    .close("cac:TaxCategory")
                    .close("cac:TaxSubtotal");
        }
        if (Php.truthy(d.icbper)) {
            x.open("cac:TaxSubtotal")
                    .amount("cbc:TaxAmount", cur, d.icbper)
                    .el("cbc:BaseUnitMeasure", "unitCode", "NIU", d.cantidad)
                    .open("cac:TaxCategory")
                    .amount("cbc:PerUnitAmount", cur, d.factorIcbper)
                    .open("cac:TaxScheme").el("cbc:ID", "7152").el("cbc:Name", "ICBPER").el("cbc:TaxTypeCode", "OTH").close("cac:TaxScheme")
                    .close("cac:TaxCategory")
                    .close("cac:TaxSubtotal");
        }
        x.close("cac:TaxTotal");
        x.open("cac:Item");
        x.cdata("cbc:Description", d.descripcion);
        if (Php.truthy(d.codProducto)) {
            x.open("cac:SellersItemIdentification").el("cbc:ID", d.codProducto).close("cac:SellersItemIdentification");
        }
        if (Php.truthy(d.codProdGS1)) {
            x.open("cac:StandardItemIdentification").el("cbc:ID", d.codProdGS1).close("cac:StandardItemIdentification");
        }
        if (Php.truthy(d.codProdSunat)) {
            x.open("cac:CommodityClassification").el("cbc:ItemClassificationCode", d.codProdSunat).close("cac:CommodityClassification");
        }
        x.close("cac:Item");
        x.open("cac:Price").el("cbc:PriceAmount", "currencyID", cur, nFormatLimit(d.mtoValorUnitario, 10)).close("cac:Price");
        x.close("cac:InvoiceLine");
    }

    private static void allowances(XmlWriter x, List<Charge> charges, String indicator, String cur) {
        if (!Php.truthy(charges)) {
            return;
        }
        for (Charge c : charges) {
            // En las líneas, Amount y BaseAmount van sin formato (float nativo de PHP)
            x.open("cac:AllowanceCharge")
                    .el("cbc:ChargeIndicator", indicator)
                    .el("cbc:AllowanceChargeReasonCode", c.codTipo)
                    .el("cbc:MultiplierFactorNumeric", nFormatLimit(c.factor, 5))
                    .el("cbc:Amount", "currencyID", cur, c.monto)
                    .el("cbc:BaseAmount", "currencyID", cur, c.montoBase)
                    .close("cac:AllowanceCharge");
        }
    }

    private static String defaultIfEmpty(String v, String def) {
        return v == null || v.isEmpty() ? def : v;
    }
}
