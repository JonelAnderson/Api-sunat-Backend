package pe.facturacion.api.sunat.xml;

import java.math.BigDecimal;
import java.math.RoundingMode;
import pe.facturacion.api.sunat.model.SaleModels.Address;
import pe.facturacion.api.sunat.model.SaleModels.Company;
import pe.facturacion.api.sunat.model.SettlementModels.Line;
import pe.facturacion.api.sunat.model.SettlementModels.Place;
import pe.facturacion.api.sunat.model.SettlementModels.Property;
import pe.facturacion.api.sunat.model.SettlementModels.Settlement;

/**
 * XML de la liquidación de compra electrónica: {@code SelfBilledInvoice} UBL 2.1, versión 2.0.
 *
 * <p>Fuentes: rutas y atributos del Anexo N.° 9-A, literal e) de la RS 097-2012/SUNAT (incorporado
 * por la RS 244-2019/SUNAT); orden de elementos del esquema OASIS {@code UBL-SelfBilledInvoice-2.1.xsd}
 * (el comprador, {@code AccountingCustomerParty}, va antes que el vendedor); tributos y totales de
 * las reglas de validación SUNAT del 24/04/2026 (hoja {@code LiquidacionCompra2_0}): IGV 1000 /
 * 9997 / 9998 y retención de renta 3000 ({@code IR}, {@code TOX}), que no suma al {@code TaxTotal}.
 * Greenter no modela este documento.
 */
public final class SettlementXmlBuilder {

    static final String CAT = "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo";
    static final String UNECE = "United Nations Economic Commission for Europe";

    private SettlementXmlBuilder() {
    }

    public static String build(Settlement doc) {
        XmlWriter x = new XmlWriter();
        String cur = doc.moneda;
        x.raw(InvoiceXmlBuilder.DECLARATION);
        x.raw("<SelfBilledInvoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:SelfBilledInvoice-2\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
                + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
                + " xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\""
                + " xmlns:ext=\"urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2\">");
        x.raw("<ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent/></ext:UBLExtension></ext:UBLExtensions>");
        x.el("cbc:UBLVersionID", "2.1");
        x.el("cbc:CustomizationID", "schemeAgencyName", "PE:SUNAT", "2.0");
        x.el("cbc:ID", doc.serie + "-" + doc.correlativo);
        x.el("cbc:IssueDate", doc.fechaEmision.format(InvoiceXmlBuilder.DATE));
        x.el("cbc:IssueTime", doc.fechaEmision.format(InvoiceXmlBuilder.TIME));
        x.openAttrs("cbc:InvoiceTypeCode", "listAgencyName", "PE:SUNAT", "listName", "Tipo de Documento", "listURI", CAT + "01",
                "listID", doc.tipoOperacion, "name", "Tipo de Operacion", "listSchemeURI", CAT + "51").text("04").close("cbc:InvoiceTypeCode");
        if (doc.leyenda != null) {
            x.openAttrs("cbc:Note", "languageLocaleID", "1000").cdataText(doc.leyenda).close("cbc:Note");
        }
        x.openAttrs("cbc:DocumentCurrencyCode", "listID", "ISO 4217 Alpha", "listName", "Currency", "listAgencyName", UNECE)
                .text(cur).close("cbc:DocumentCurrencyCode");
        for (String[] g : doc.guias) {
            x.open("cac:DespatchDocumentReference").el("cbc:ID", g[1]);
            x.openAttrs("cbc:DocumentTypeCode", "listAgencyName", "PE:SUNAT", "listName", "Tipo de Documento", "listURI", CAT + "01")
                    .text(g[0]).close("cbc:DocumentTypeCode");
            x.close("cac:DespatchDocumentReference");
        }
        InvoiceXmlBuilder.signature(x, doc.comprador);
        comprador(x, doc.comprador);
        vendedor(x, doc);
        if (doc.placa != null) {
            // Traslado sustentado con la liquidación (art. 29-D): motivo 02 (compra) y placa
            x.open("cac:Delivery").open("cac:Shipment").el("cbc:ID", "02")
                    .open("cac:ShipmentStage").open("cac:TransportMeans").open("cac:RoadTransport")
                    .el("cbc:LicensePlateID", doc.placa)
                    .close("cac:RoadTransport").close("cac:TransportMeans").close("cac:ShipmentStage")
                    .close("cac:Shipment").close("cac:Delivery");
        }
        x.open("cac:DeliveryTerms").open("cac:DeliveryLocation");
        x.el("cbc:LocationTypeCode", doc.lugar.tipo);
        x.open("cac:Address");
        ubigeo(x, doc.lugar.ubigeo);
        x.open("cac:AddressLine").cdata("cbc:Line", doc.lugar.direccion).close("cac:AddressLine");
        country(x);
        x.close("cac:Address").close("cac:DeliveryLocation").close("cac:DeliveryTerms");

        x.open("cac:TaxTotal");
        x.amount("cbc:TaxAmount", cur, doc.igv);
        boolean conGravadas = doc.gravadas.signum() > 0 || (doc.exoneradas.signum() == 0 && doc.inafectas.signum() == 0);
        if (conGravadas) {
            subtotal(x, cur, doc.gravadas, doc.igv, "1000", "IGV", "VAT");
        }
        if (doc.exoneradas.signum() > 0) {
            subtotal(x, cur, doc.exoneradas, BigDecimal.ZERO, "9997", "EXO", "VAT");
        }
        if (doc.inafectas.signum() > 0) {
            subtotal(x, cur, doc.inafectas, BigDecimal.ZERO, "9998", "INA", "FRE");
        }
        subtotal(x, cur, doc.baseRenta, doc.renta, "3000", "IR", "TOX");
        x.close("cac:TaxTotal");

        x.open("cac:LegalMonetaryTotal");
        x.amount("cbc:LineExtensionAmount", cur, doc.valorVenta);
        x.amount("cbc:TaxInclusiveAmount", cur, doc.subTotal);
        x.amount("cbc:PayableAmount", cur, doc.neto);
        x.close("cac:LegalMonetaryTotal");

        int i = 0;
        for (Line l : doc.lines) {
            line(x, l, ++i, cur);
        }
        x.raw("</SelfBilledInvoice>");
        return x.build();
    }

    private static void comprador(XmlWriter x, Company emp) {
        x.open("cac:AccountingCustomerParty").open("cac:Party");
        x.open("cac:PartyIdentification");
        x.openAttrs("cbc:ID", "schemeID", "6", "schemeName", "Documento de Identidad", "schemeAgencyName", "PE:SUNAT",
                "schemeURI", CAT + "06").text(emp.ruc).close("cbc:ID");
        x.close("cac:PartyIdentification");
        if (emp.nombreComercial != null && !emp.nombreComercial.isEmpty()) {
            x.open("cac:PartyName").cdata("cbc:Name", emp.nombreComercial).close("cac:PartyName");
        }
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", emp.razonSocial);
        Address a = emp.address;
        if (a != null) {
            x.open("cac:RegistrationAddress");
            if (a.ubigueo != null && !a.ubigueo.isEmpty()) {
                ubigeo(x, a.ubigueo);
            }
            if (a.urbanizacion != null && !a.urbanizacion.isEmpty()) {
                x.el("cbc:CitySubdivisionName", a.urbanizacion);
            }
            if (a.provincia != null && !a.provincia.isEmpty()) {
                x.el("cbc:CityName", a.provincia);
            }
            if (a.departamento != null && !a.departamento.isEmpty()) {
                x.el("cbc:CountrySubentity", a.departamento);
            }
            if (a.distrito != null && !a.distrito.isEmpty()) {
                x.el("cbc:District", a.distrito);
            }
            x.open("cac:AddressLine").cdata("cbc:Line", a.direccion).close("cac:AddressLine");
            country(x);
            x.close("cac:RegistrationAddress");
        }
        x.close("cac:PartyLegalEntity");
        x.close("cac:Party").close("cac:AccountingCustomerParty");
    }

    private static void vendedor(XmlWriter x, Settlement doc) {
        Place d = doc.vendedorDomicilio;
        x.open("cac:AccountingSupplierParty").open("cac:Party");
        x.open("cac:PartyIdentification");
        x.openAttrs("cbc:ID", "schemeID", doc.vendedorTipoDoc, "schemeName", "Documento de Identidad", "schemeAgencyName", "PE:SUNAT",
                "schemeURI", CAT + "06").text(doc.vendedorNumDoc).close("cbc:ID");
        x.close("cac:PartyIdentification");
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", doc.vendedorNombre);
        x.open("cac:RegistrationAddress");
        ubigeo(x, d.ubigeo);
        x.el("cbc:AddressTypeCode", d.tipo);
        x.open("cac:AddressLine").cdata("cbc:Line", d.direccion).close("cac:AddressLine");
        country(x);
        x.close("cac:RegistrationAddress");
        x.close("cac:PartyLegalEntity");
        x.close("cac:Party").close("cac:AccountingSupplierParty");
    }

    private static void ubigeo(XmlWriter x, String ubigeo) {
        x.openAttrs("cbc:ID", "schemeAgencyName", "PE:INEI", "schemeName", "Ubigeos").text(ubigeo).close("cbc:ID");
    }

    private static void country(XmlWriter x) {
        x.open("cac:Country");
        x.openAttrs("cbc:IdentificationCode", "listID", "ISO 3166-1", "listAgencyName", UNECE, "listName", "Country")
                .text("PE").close("cbc:IdentificationCode");
        x.close("cac:Country");
    }

    private static void subtotal(XmlWriter x, String cur, BigDecimal base, BigDecimal monto, String code, String name, String type) {
        x.open("cac:TaxSubtotal");
        x.amount("cbc:TaxableAmount", cur, base);
        x.amount("cbc:TaxAmount", cur, monto);
        x.open("cac:TaxCategory");
        scheme(x, code, name, type);
        x.close("cac:TaxCategory");
        x.close("cac:TaxSubtotal");
    }

    private static void scheme(XmlWriter x, String code, String name, String type) {
        x.open("cac:TaxScheme");
        x.openAttrs("cbc:ID", "schemeName", "Codigo de tributos", "schemeAgencyName", "PE:SUNAT", "schemeURI", CAT + "05")
                .text(code).close("cbc:ID");
        x.el("cbc:Name", name);
        x.el("cbc:TaxTypeCode", type);
        x.close("cac:TaxScheme");
    }

    private static void line(XmlWriter x, Line l, int n, String cur) {
        x.open("cac:InvoiceLine");
        x.el("cbc:ID", n);
        x.openAttrs("cbc:InvoicedQuantity", "unitCode", l.unidad, "unitCodeListID", "UN/ECE rec 20", "unitCodeListAgencyName", UNECE)
                .text(dec(l.cantidad)).close("cbc:InvoicedQuantity");
        x.amount("cbc:LineExtensionAmount", cur, l.valorVenta);
        x.open("cac:PricingReference").open("cac:AlternativeConditionPrice");
        x.el("cbc:PriceAmount", "currencyID", cur, dec(l.precioUnitario));
        x.openAttrs("cbc:PriceTypeCode", "listName", "Tipo de Precio", "listAgencyName", "PE:SUNAT", "listURI", CAT + "16")
                .text("01").close("cbc:PriceTypeCode");
        x.close("cac:AlternativeConditionPrice").close("cac:PricingReference");

        x.open("cac:TaxTotal");
        x.amount("cbc:TaxAmount", cur, l.igv);
        String[] t = switch (l.tipAfeIgv) {
            case "20" -> new String[] {"9997", "EXO", "VAT"};
            case "30" -> new String[] {"9998", "INA", "FRE"};
            default -> new String[] {"1000", "IGV", "VAT"};
        };
        x.open("cac:TaxSubtotal");
        x.amount("cbc:TaxableAmount", cur, l.valorVenta);
        x.amount("cbc:TaxAmount", cur, l.igv);
        x.open("cac:TaxCategory");
        x.el("cbc:Percent", dec(l.porcentajeIgv));
        x.openAttrs("cbc:TaxExemptionReasonCode", "listAgencyName", "PE:SUNAT", "listName", "Afectacion del IGV", "listURI", CAT + "07")
                .text(l.tipAfeIgv).close("cbc:TaxExemptionReasonCode");
        scheme(x, t[0], t[1], t[2]);
        x.close("cac:TaxCategory");
        x.close("cac:TaxSubtotal");
        // Retención del Impuesto a la Renta (tributo 3000): no lleva código de afectación (ERR-3050)
        x.open("cac:TaxSubtotal");
        x.amount("cbc:TaxableAmount", cur, l.valorVenta);
        x.amount("cbc:TaxAmount", cur, l.renta);
        x.open("cac:TaxCategory");
        x.el("cbc:Percent", dec(l.porcentajeRenta));
        scheme(x, "3000", "IR", "TOX");
        x.close("cac:TaxCategory");
        x.close("cac:TaxSubtotal");
        x.close("cac:TaxTotal");

        x.open("cac:Item");
        x.cdata("cbc:Description", l.descripcion);
        if (l.codigo != null && !l.codigo.isEmpty()) {
            x.open("cac:SellersItemIdentification").el("cbc:ID", l.codigo).close("cac:SellersItemIdentification");
        }
        x.open("cac:CommodityClassification");
        x.openAttrs("cbc:ItemClassificationCode", "listID", "UNSPSC", "listAgencyName", "GS1 US", "listName", "Item Classification")
                .text(l.codProductoSunat).close("cbc:ItemClassificationCode");
        x.close("cac:CommodityClassification");
        for (Property p : l.propiedades) {
            x.open("cac:AdditionalItemProperty");
            x.el("cbc:Name", p.name());
            x.openAttrs("cbc:NameCode", "listName", "Propiedad del item", "listAgencyName", "PE:SUNAT", "listURI", CAT + "55")
                    .text(p.code()).close("cbc:NameCode");
            x.el("cbc:Value", p.value());
            x.close("cac:AdditionalItemProperty");
        }
        x.close("cac:Item");
        x.open("cac:Price").el("cbc:PriceAmount", "currencyID", cur, dec(l.valorUnitario)).close("cac:Price");
        x.close("cac:InvoiceLine");
    }

    /** Decimal sin ceros sobrantes ni notación científica (n(12,10)). */
    static String dec(BigDecimal v) {
        BigDecimal s = v.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }
}
