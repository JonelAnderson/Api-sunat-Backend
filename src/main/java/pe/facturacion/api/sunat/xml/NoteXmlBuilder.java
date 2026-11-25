package pe.facturacion.api.sunat.xml;

import static pe.facturacion.api.sunat.xml.InvoiceXmlBuilder.DATE;
import static pe.facturacion.api.sunat.xml.InvoiceXmlBuilder.DECLARATION;
import static pe.facturacion.api.sunat.xml.InvoiceXmlBuilder.TIME;
import static pe.facturacion.api.sunat.xml.XmlWriter.nFormat;
import static pe.facturacion.api.sunat.xml.XmlWriter.nFormatLimit;

import java.util.regex.Pattern;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.sunat.model.SaleModels.Address;
import pe.facturacion.api.sunat.model.SaleModels.Company;
import pe.facturacion.api.sunat.model.SaleModels.Cuota;
import pe.facturacion.api.sunat.model.SaleModels.Document;
import pe.facturacion.api.sunat.model.SaleModels.Legend;
import pe.facturacion.api.sunat.model.SaleModels.Note;
import pe.facturacion.api.sunat.model.SaleModels.SaleDetail;

/**
 * XML UBL 2.1 de nota de crédito ({@code notacr2.1.xml.twig}) y de débito ({@code notadb2.1.xml.twig})
 * de Greenter 5.2. Diferencias con la factura: {@code DiscrepancyResponse} + {@code BillingReference},
 * {@code PartyName} del emisor siempre, subtotales de cabecera con condición "truthy", sin descuentos
 * ni cargos, y totales en {@code LegalMonetaryTotal} (NC) o {@code RequestedMonetaryTotal} (ND).
 */
public final class NoteXmlBuilder {

    private static final Pattern EMPTY_BILLING_REFERENCE = Pattern.compile("\\s*<cac:BillingReference>.*?</cac:BillingReference>", Pattern.DOTALL);

    private NoteXmlBuilder() {
    }

    public static String build(Note doc) {
        boolean credit = "07".equals(doc.tipoDoc);
        String root = credit ? "CreditNote" : "DebitNote";
        String cur = doc.tipoMoneda;
        Company emp = doc.company;
        XmlWriter x = new XmlWriter();
        x.raw(DECLARATION);
        x.raw("<" + root + " xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:" + root + "-2\""
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
        if (doc.legends != null) {
            for (Legend leg : doc.legends) {
                x.openAttrs("cbc:Note", "languageLocaleID", Php.toStr(leg.code)).cdataText(leg.value).close("cbc:Note");
            }
        }
        x.el("cbc:DocumentCurrencyCode", cur);
        x.open("cac:DiscrepancyResponse")
                .el("cbc:ReferenceID", doc.numDocfectado)
                .el("cbc:ResponseCode", doc.codMotivo)
                .el("cbc:Description", doc.desMotivo)
                .close("cac:DiscrepancyResponse");
        x.open("cac:BillingReference").open("cac:InvoiceDocumentReference")
                .el("cbc:ID", doc.numDocfectado)
                .el("cbc:DocumentTypeCode", doc.tipDocAfectado)
                .close("cac:InvoiceDocumentReference").close("cac:BillingReference");
        if (Php.truthy(doc.guias)) {
            for (Document guia : doc.guias) {
                x.open("cac:DespatchDocumentReference")
                        .el("cbc:ID", guia.nroDoc)
                        .el("cbc:DocumentTypeCode", guia.tipoDoc)
                        .close("cac:DespatchDocumentReference");
            }
        }
        InvoiceXmlBuilder.signature(x, emp);
        supplier(x, emp);
        InvoiceXmlBuilder.customer(x, "cac:AccountingCustomerParty", doc.client);
        if (credit) {
            if (doc.formaPago != null) {
                x.open("cac:PaymentTerms").el("cbc:ID", "FormaPago").el("cbc:PaymentMeansID", doc.formaPago.tipo);
                if (Php.truthy(doc.formaPago.monto)) {
                    x.amount("cbc:Amount", doc.formaPago.moneda == null || doc.formaPago.moneda.isEmpty() ? cur : doc.formaPago.moneda,
                            doc.formaPago.monto);
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
                            .amount("cbc:Amount", cuota.moneda == null || cuota.moneda.isEmpty() ? cur : cuota.moneda, cuota.monto)
                            .el("cbc:PaymentDueDate", cuota.fechaPago == null ? "" : cuota.fechaPago.format(DATE))
                            .close("cac:PaymentTerms");
                }
            }
        }
        taxTotal(x, doc, cur);
        String total = credit ? "cac:LegalMonetaryTotal" : "cac:RequestedMonetaryTotal";
        x.open(total);
        if (!credit) {
            if (doc.valorVenta != null) {
                x.amount("cbc:LineExtensionAmount", cur, doc.valorVenta);
            }
            if (doc.subTotal != null) {
                x.amount("cbc:TaxInclusiveAmount", cur, doc.subTotal);
            }
        }
        if (doc.sumOtrosCargos != null) {
            x.amount("cbc:ChargeTotalAmount", cur, doc.sumOtrosCargos);
        }
        if (doc.redondeo != null) {
            x.amount("cbc:PayableRoundingAmount", cur, doc.redondeo);
        }
        x.amount("cbc:PayableAmount", cur, doc.mtoImpVenta);
        x.close(total);
        int n = 0;
        for (SaleDetail d : doc.details) {
            line(x, d, ++n, cur, credit);
        }
        x.close(root);
        String xml = x.build();
        // stripEmptyNoteReference(): ND por penalidad (13) sin documento afectado
        if ("13".equals(doc.codMotivo) && Php.empty(doc.numDocfectado)) {
            xml = EMPTY_BILLING_REFERENCE.matcher(xml).replaceFirst("");
        }
        return xml;
    }

    private static void supplier(XmlWriter x, Company emp) {
        x.open("cac:AccountingSupplierParty").open("cac:Party");
        x.open("cac:PartyIdentification").el("cbc:ID", "schemeID", "6", emp.ruc).close("cac:PartyIdentification");
        x.open("cac:PartyName").cdata("cbc:Name", emp.nombreComercial).close("cac:PartyName");
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", emp.razonSocial);
        Address addr = emp.address;
        x.open("cac:RegistrationAddress").el("cbc:ID", addr.ubigueo).el("cbc:AddressTypeCode", addr.codLocal);
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
        if (Php.truthy(emp.email) || Php.truthy(emp.telephone)) {
            x.open("cac:Contact");
            if (Php.truthy(emp.telephone)) {
                x.el("cbc:Telephone", emp.telephone);
            }
            if (Php.truthy(emp.email)) {
                x.el("cbc:ElectronicMail", emp.email);
            }
            x.close("cac:Contact");
        }
        x.close("cac:Party").close("cac:AccountingSupplierParty");
    }

    private static void subtotal(XmlWriter x, String cur, Object taxable, String taxAmount, String id, String name, String code) {
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

    private static Object nn(Double v) {
        return v == null ? 0.0 : v;
    }

    private static void taxTotal(XmlWriter x, Note doc, String cur) {
        x.open("cac:TaxTotal");
        x.amount("cbc:TaxAmount", cur, doc.totalImpuestos);
        if (Php.truthy(doc.mtoISC)) {
            subtotal(x, cur, nn(doc.mtoBaseIsc), nFormat(doc.mtoISC, 2), "2000", "ISC", "EXC");
        }
        if (Php.truthy(doc.mtoOperGravadas)) {
            subtotal(x, cur, doc.mtoOperGravadas, nFormat(doc.mtoIGV, 2), "1000", "IGV", "VAT");
        }
        if (Php.truthy(doc.mtoOperInafectas)) {
            subtotal(x, cur, doc.mtoOperInafectas, "0", "9998", "INA", "FRE");
        }
        if (Php.truthy(doc.mtoOperExoneradas)) {
            subtotal(x, cur, doc.mtoOperExoneradas, "0", "9997", "EXO", "VAT");
        }
        if (Php.truthy(doc.mtoOperGratuitas)) {
            subtotal(x, cur, doc.mtoOperGratuitas, nFormat(doc.mtoIGVGratuitas, 2), "9996", "GRA", "FRE");
        }
        if (Php.truthy(doc.mtoOperExportacion)) {
            subtotal(x, cur, doc.mtoOperExportacion, "0", "9995", "EXP", "FRE");
        }
        if (Php.truthy(doc.mtoIvap)) {
            subtotal(x, cur, nn(doc.mtoBaseIvap), nFormat(doc.mtoIvap, 2), "1016", "IVAP", "VAT");
        }
        if (Php.truthy(doc.mtoOtrosTributos)) {
            subtotal(x, cur, nn(doc.mtoBaseOth), nFormat(doc.mtoOtrosTributos, 2), "9999", "OTROS", "OTH");
        }
        if (Php.truthy(doc.icbper)) {
            subtotal(x, cur, null, nFormat(doc.icbper, 2), "7152", "ICBPER", "OTH");
        }
        x.close("cac:TaxTotal");
    }

    private static void line(XmlWriter x, SaleDetail d, int index, String cur, boolean credit) {
        String tag = credit ? "cac:CreditNoteLine" : "cac:DebitNoteLine";
        x.open(tag);
        x.el("cbc:ID", index);
        x.el(credit ? "cbc:CreditedQuantity" : "cbc:DebitedQuantity", "unitCode", d.unidad, d.cantidad);
        x.amount("cbc:LineExtensionAmount", cur, d.mtoValorVenta);
        x.open("cac:PricingReference").open("cac:AlternativeConditionPrice");
        if (Php.truthy(d.mtoValorGratuito)) {
            x.el("cbc:PriceAmount", "currencyID", cur, nFormatLimit(d.mtoValorGratuito, 10)).el("cbc:PriceTypeCode", "02");
        } else {
            x.el("cbc:PriceAmount", "currencyID", cur, nFormatLimit(d.mtoPrecioUnitario, 10)).el("cbc:PriceTypeCode", "01");
        }
        x.close("cac:AlternativeConditionPrice").close("cac:PricingReference");
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
        String[] afect = InvoiceXmlBuilder.tributoAfect(d.tipAfeIgv);
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
                    .el("cbc:BaseUnitMeasure", "unitCode", d.unidad, d.cantidad)
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
        x.close(tag);
    }
}
