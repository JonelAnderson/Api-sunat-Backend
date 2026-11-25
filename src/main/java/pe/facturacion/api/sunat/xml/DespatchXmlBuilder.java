package pe.facturacion.api.sunat.xml;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.sunat.builder.PhpDates;
import pe.facturacion.api.sunat.model.DespatchModels.AdditionalDoc;
import pe.facturacion.api.sunat.model.DespatchModels.Despatch;
import pe.facturacion.api.sunat.model.DespatchModels.DespatchDetail;
import pe.facturacion.api.sunat.model.DespatchModels.Direction;
import pe.facturacion.api.sunat.model.DespatchModels.Driver;
import pe.facturacion.api.sunat.model.DespatchModels.Shipment;
import pe.facturacion.api.sunat.model.DespatchModels.Vehicle;
import pe.facturacion.api.sunat.model.SaleModels.Client;
import pe.facturacion.api.sunat.model.SaleModels.Company;

/**
 * {@code despatch2022.xml.twig} de Greenter más los ajustes de {@code GreenterService::sendDespatch()}:
 * sin {@code cbc:CompanyID} vacío, {@code LoadingTransportEvent} (fecha de entrega al transportista)
 * y, en la GRT, sin motivo/modalidad y con CarrierParty/TransportMeans, Consignment,
 * OriginatorCustomerParty y DespatchParty.
 *
 * <p>Greenter renderiza esta plantilla con autoescape HTML ({@code &#039;}, {@code &quot;}), pero la
 * firma la reserializa con libxml y esas entidades vuelven a ser comillas: el XML firmado es igual
 * al que produce el escape XML normal de {@link XmlWriter}.
 *
 * <p>Corrección: los nombres inyectados dentro de CDATA (remitente, subcontratador, pagador) van
 * literales; API-PRO les aplicaba {@code htmlspecialchars} y SUNAT recibía {@code &amp;amp;}.
 */
public final class DespatchXmlBuilder {

    private static final String CAT06 = " schemeName=\"Documento de Identidad\" schemeAgencyName=\"PE:SUNAT\""
            + " schemeURI=\"urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo06\"";

    private DespatchXmlBuilder() {
    }

    /** XML sin firmar listo para firmar (con las inyecciones según el tipo). */
    public static String build(Despatch doc, Map<String, Object> data) {
        String xml = template(doc);
        String tipo = Php.toStr(Php.coalesce(data, "tipo_documento", "09"));
        if ("09".equals(tipo) && "01".equals(data.get("mod_traslado")) && !Php.empty(data.get("fecha_entrega_transportista"))) {
            xml = injectCarrierDeliveryDate(xml, Php.toStr(data.get("fecha_entrega_transportista")));
        }
        if ("31".equals(tipo)) {
            xml = stripGrtMotivoModalidad(xml);
            xml = injectGrtShipmentStageNodes(xml, data);
            xml = injectGrtSubcontratadorPagador(xml, data);
            xml = injectDespatchPartyGrt(xml, data.get("remitente"));
        }
        return xml;
    }

    static String template(Despatch doc) {
        XmlWriter x = new XmlWriter();
        x.raw(InvoiceXmlBuilder.DECLARATION);
        x.raw("<DespatchAdvice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:DespatchAdvice-2\""
                + " xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\""
                + " xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
                + " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\""
                + " xmlns:ext=\"urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2\">");
        x.raw("<ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent/></ext:UBLExtension></ext:UBLExtensions>");
        x.el("cbc:UBLVersionID", "2.1");
        x.el("cbc:CustomizationID", "2.0");
        x.el("cbc:ID", doc.serie + "-" + doc.correlativo);
        x.el("cbc:IssueDate", doc.fechaEmision.toLocalDate().toString());
        x.el("cbc:IssueTime", doc.fechaEmision.toLocalTime().format(InvoiceXmlBuilder.TIME));
        x.openAttrs("cbc:DespatchAdviceTypeCode", "listAgencyName", "PE:SUNAT", "listName", "Tipo de Documento",
                "listURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo01").text(doc.tipoDoc).close("cbc:DespatchAdviceTypeCode");
        if (Php.truthy(doc.observacion)) {
            x.cdata("cbc:Note", doc.observacion);
        }
        for (AdditionalDoc rel : doc.addDocs) {
            x.open("cac:AdditionalDocumentReference");
            x.el("cbc:ID", rel.nro);
            x.openAttrs("cbc:DocumentTypeCode", "listAgencyName", "PE:SUNAT", "listName", "Documento relacionado al transporte",
                    "listURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo61").text(rel.tipo).close("cbc:DocumentTypeCode");
            x.el("cbc:DocumentType", rel.tipoDesc);
            if (Php.truthy(rel.emisor)) {
                x.open("cac:IssuerParty").open("cac:PartyIdentification");
                x.raw("<cbc:ID schemeID=\"6\"" + CAT06 + ">").text(rel.emisor).close("cbc:ID");
                x.close("cac:PartyIdentification").close("cac:IssuerParty");
            }
            x.close("cac:AdditionalDocumentReference");
        }
        Company emp = doc.company;
        InvoiceXmlBuilder.signature(x, emp);
        x.open("cac:DespatchSupplierParty").open("cac:Party").open("cac:PartyIdentification");
        x.raw("<cbc:ID schemeID=\"6\"" + CAT06 + ">").text(emp.ruc).close("cbc:ID");
        x.close("cac:PartyIdentification");
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", emp.razonSocial).close("cac:PartyLegalEntity");
        x.close("cac:Party").close("cac:DespatchSupplierParty");
        party(x, "cac:DeliveryCustomerParty", doc.destinatario);
        if (doc.comprador != null) {
            party(x, "cac:BuyerCustomerParty", doc.comprador);
        }
        if (doc.tercero != null) {
            party(x, "cac:SellerSupplierParty", doc.tercero);
        }

        Shipment envio = doc.envio;
        x.open("cac:Shipment");
        x.el("cbc:ID", "SUNAT_Envio");
        x.openAttrs("cbc:HandlingCode", "listAgencyName", "PE:SUNAT", "listName", "Motivo de traslado",
                "listURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo20").text(envio.codTraslado).close("cbc:HandlingCode");
        x.el("cbc:GrossWeightMeasure", "unitCode", envio.undPesoTotal, XmlWriter.nFormat(envio.pesoTotal, 3));
        if (envio.numBultos != null && envio.numBultos != 0) {
            x.el("cbc:TotalTransportHandlingUnitQuantity", envio.numBultos);
        }
        for (String ind : envio.indicadores) {
            x.el("cbc:SpecialInstructions", ind);
        }
        x.open("cac:ShipmentStage");
        x.openAttrs("cbc:TransportModeCode", "listName", "Modalidad de traslado", "listAgencyName", "PE:SUNAT",
                "listURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo18").text(envio.modTraslado).close("cbc:TransportModeCode");
        if (envio.fecTraslado != null) {
            x.open("cac:TransitPeriod").el("cbc:StartDate", envio.fecTraslado.toString()).close("cac:TransitPeriod");
        }
        if (envio.transportista != null) {
            x.open("cac:CarrierParty").open("cac:PartyIdentification")
                    .el("cbc:ID", "schemeID", envio.transportista.tipoDoc, envio.transportista.numDoc)
                    .close("cac:PartyIdentification");
            x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", envio.transportista.rznSocial);
            // La plantilla emite <cbc:CompanyID/> vacío y API-PRO lo retira (SUNAT observa 4391)
            if (!Php.toStr(envio.transportista.nroMtc).isBlank()) {
                x.el("cbc:CompanyID", envio.transportista.nroMtc);
            }
            x.close("cac:PartyLegalEntity").close("cac:CarrierParty");
        }
        for (Driver ch : envio.choferes) {
            x.open("cac:DriverPerson");
            x.raw("<cbc:ID schemeID=\"" + XmlWriter.escapeAttr(Php.toStr(ch.tipoDoc)) + "\"" + CAT06 + ">").text(ch.nroDoc).close("cbc:ID");
            x.el("cbc:FirstName", ch.nombres);
            x.el("cbc:FamilyName", ch.apellidos);
            x.el("cbc:JobTitle", ch.tipo);
            x.open("cac:IdentityDocumentReference").el("cbc:ID", ch.licencia).close("cac:IdentityDocumentReference");
            x.close("cac:DriverPerson");
        }
        x.close("cac:ShipmentStage");
        x.open("cac:Delivery");
        address(x, "cac:DeliveryAddress", envio.llegada);
        x.open("cac:Despatch");
        address(x, "cac:DespatchAddress", envio.partida);
        x.close("cac:Despatch");
        x.close("cac:Delivery");
        Vehicle v = envio.vehiculo;
        if (v != null) {
            x.open("cac:TransportHandlingUnit").open("cac:TransportEquipment");
            x.el("cbc:ID", v.placa);
            if (Php.truthy(v.nroCirculacion)) {
                x.open("cac:ApplicableTransportMeans").el("cbc:RegistrationNationalityID", v.nroCirculacion).close("cac:ApplicableTransportMeans");
            }
            for (Vehicle sec : v.secundarios) {
                x.open("cac:AttachedTransportEquipment");
                x.el("cbc:ID", sec.placa);
                if (Php.truthy(sec.nroCirculacion)) {
                    x.open("cac:ApplicableTransportMeans").el("cbc:RegistrationNationalityID", sec.nroCirculacion)
                            .close("cac:ApplicableTransportMeans");
                }
                if (Php.truthy(sec.nroAutorizacion)) {
                    authorization(x, sec);
                }
                x.close("cac:AttachedTransportEquipment");
            }
            if (Php.truthy(v.nroAutorizacion)) {
                authorization(x, v);
            }
            x.close("cac:TransportEquipment").close("cac:TransportHandlingUnit");
        }
        x.close("cac:Shipment");

        int i = 0;
        for (DespatchDetail det : doc.details) {
            i++;
            x.open("cac:DespatchLine");
            x.el("cbc:ID", i);
            x.el("cbc:DeliveredQuantity", "unitCode", det.unidad, XmlWriter.nFormatLimit(det.cantidad, 10));
            x.open("cac:OrderLineReference").el("cbc:LineID", i).close("cac:OrderLineReference");
            x.open("cac:Item");
            x.cdata("cbc:Description", det.descripcion);
            x.open("cac:SellersItemIdentification").el("cbc:ID", det.codigo).close("cac:SellersItemIdentification");
            if (Php.truthy(det.codProdSunat)) {
                x.open("cac:CommodityClassification");
                x.openAttrs("cbc:ItemClassificationCode", "listID", "UNSPSC", "listAgencyName", "GS1 US", "listName", "Item Classification")
                        .text(det.codProdSunat).close("cbc:ItemClassificationCode");
                x.close("cac:CommodityClassification");
            }
            x.close("cac:Item");
            x.close("cac:DespatchLine");
        }
        x.close("DespatchAdvice");
        return x.build();
    }

    private static void party(XmlWriter x, String tag, Client c) {
        x.open(tag).open("cac:Party").open("cac:PartyIdentification");
        x.raw("<cbc:ID schemeID=\"" + XmlWriter.escapeAttr(Php.toStr(c.tipoDoc)) + "\"" + CAT06 + ">").text(c.numDoc).close("cbc:ID");
        x.close("cac:PartyIdentification");
        x.open("cac:PartyLegalEntity").cdata("cbc:RegistrationName", c.rznSocial).close("cac:PartyLegalEntity");
        x.close("cac:Party").close(tag);
    }

    private static void address(XmlWriter x, String tag, Direction d) {
        x.open(tag);
        x.openAttrs("cbc:ID", "schemeAgencyName", "PE:INEI", "schemeName", "Ubigeos").text(d.ubigueo).close("cbc:ID");
        if (Php.truthy(d.codLocal)) {
            x.el("cbc:AddressTypeCode", "listID", d.ruc, d.codLocal);
        }
        x.open("cac:AddressLine").el("cbc:Line", d.direccion).close("cac:AddressLine");
        x.close(tag);
    }

    private static void authorization(XmlWriter x, Vehicle v) {
        x.open("cac:ShipmentDocumentReference");
        x.openAttrs("cbc:ID", "schemeID", Php.toStr(v.codEmisor), "schemeName", "Entidad Autorizadora", "schemeAgencyName", "PE:SUNAT")
                .text(v.nroAutorizacion).close("cbc:ID");
        x.close("cac:ShipmentDocumentReference");
    }

    // ------------------------------------------------------------ inyecciones

    /** {@code injectCarrierDeliveryDate()}: tras el primer {@code </cac:CarrierParty>}. */
    static String injectCarrierDeliveryDate(String xml, String date) {
        String block = "<cac:LoadingTransportEvent><cbc:OccurrenceDate>" + PhpDates.parseDate(date)
                + "</cbc:OccurrenceDate></cac:LoadingTransportEvent>";
        int at = xml.indexOf("</cac:CarrierParty>");
        if (at < 0) {
            throw new IllegalStateException("No se encontró cac:CarrierParty para inyectar fecha_entrega_transportista.");
        }
        at += "</cac:CarrierParty>".length();
        return xml.substring(0, at) + block + xml.substring(at);
    }

    private static final Pattern HANDLING = Pattern.compile("\\s*<cbc:HandlingCode\\b[^>]*>.*?</cbc:HandlingCode>", Pattern.DOTALL);
    private static final Pattern MODE = Pattern.compile("\\s*<cbc:TransportModeCode\\b[^>]*>.*?</cbc:TransportModeCode>", Pattern.DOTALL);
    private static final Pattern TRANSIT = Pattern.compile("(<cac:ShipmentStage>\\s*<cac:TransitPeriod>.*?</cac:TransitPeriod>)", Pattern.DOTALL);
    private static final Pattern DESPATCH_ADDRESS = Pattern.compile("(<cac:Despatch>\\s*<cac:DespatchAddress>.*?</cac:DespatchAddress>)",
            Pattern.DOTALL);

    /** La GRT no lleva motivo (cat. 20) ni modalidad (cat. 18). */
    static String stripGrtMotivoModalidad(String xml) {
        xml = HANDLING.matcher(xml).replaceFirst("");
        return MODE.matcher(xml).replaceFirst("");
    }

    @SuppressWarnings("unchecked")
    static String injectGrtShipmentStageNodes(String xml, Map<String, Object> data) {
        Object transportista = data.get("transportista");
        Object mtc = transportista instanceof Map<?, ?> t ? ((Map<String, Object>) t).get("nro_mtc") : null;
        if (mtc == null) {
            mtc = data.get("nro_mtc");
        }
        Object vehiculo = data.get("vehiculo");
        Object placa = vehiculo instanceof Map<?, ?> v ? v.get("placa") : null;
        String carrier = "<cac:CarrierParty><cac:PartyIdentification><cbc:ID schemeID=\"\"/></cac:PartyIdentification>"
                + "<cac:PartyLegalEntity><cbc:RegistrationName/>"
                + (Php.truthy(mtc) ? "<cbc:CompanyID>" + XmlWriter.escapeText(Php.toStr(mtc)) + "</cbc:CompanyID>" : "")
                + "</cac:PartyLegalEntity></cac:CarrierParty>";
        String means = Php.truthy(placa)
                ? "<cac:TransportMeans><cac:RoadTransport><cbc:LicensePlateID>" + XmlWriter.escapeText(Php.toStr(placa))
                        + "</cbc:LicensePlateID></cac:RoadTransport></cac:TransportMeans>"
                : "";
        Matcher m = TRANSIT.matcher(xml);
        if (!m.find()) {
            throw new IllegalStateException("No se encontró cac:ShipmentStage>cac:TransitPeriod para inyectar CarrierParty/TransportMeans en GRT.");
        }
        return xml.substring(0, m.end()) + carrier + means + xml.substring(m.end());
    }

    static String injectGrtSubcontratadorPagador(String xml, Map<String, Object> data) {
        if (data.get("datos_subcontratador") instanceof Map<?, ?> sub && !Php.empty(sub.get("num_doc")) && !Php.empty(sub.get("razon_social"))) {
            String consignment = "<cac:Consignment><cbc:ID>SUNAT_Envio</cbc:ID><cac:LogisticsOperatorParty><cac:PartyIdentification>"
                    + "<cbc:ID schemeID=\"6\"" + CAT06 + ">" + XmlWriter.escapeText(Php.toStr(sub.get("num_doc"))) + "</cbc:ID>"
                    + "</cac:PartyIdentification><cac:PartyLegalEntity><cbc:RegistrationName>" + cdata(sub.get("razon_social"))
                    + "</cbc:RegistrationName></cac:PartyLegalEntity></cac:LogisticsOperatorParty></cac:Consignment>";
            int at = xml.indexOf("<cac:ShipmentStage>");
            if (at < 0) {
                throw new IllegalStateException("No se encontró cac:ShipmentStage para inyectar el subcontratador (Consignment) en GRT.");
            }
            xml = xml.substring(0, at) + consignment + xml.substring(at);
        }
        if (data.get("datos_pagador_flete") instanceof Map<?, ?> pag && "tercero".equals(pag.get("tipo"))
                && !Php.empty(pag.get("num_doc")) && !Php.empty(pag.get("razon_social"))) {
            Object tipoDoc = pag.get("tipo_doc") == null ? "6" : pag.get("tipo_doc");
            String originator = "<cac:OriginatorCustomerParty><cac:Party><cac:PartyIdentification>"
                    + "<cbc:ID schemeID=\"" + Php.toStr(tipoDoc) + "\"" + CAT06 + ">" + XmlWriter.escapeText(Php.toStr(pag.get("num_doc"))) + "</cbc:ID>"
                    + "</cac:PartyIdentification><cac:PartyLegalEntity><cbc:RegistrationName>" + cdata(pag.get("razon_social"))
                    + "</cbc:RegistrationName></cac:PartyLegalEntity></cac:Party></cac:OriginatorCustomerParty>";
            int at = xml.indexOf("<cac:Shipment>");
            if (at < 0) {
                throw new IllegalStateException("No se encontró cac:Shipment para inyectar el pagador del flete (OriginatorCustomerParty) en GRT.");
            }
            xml = xml.substring(0, at) + originator + xml.substring(at);
        }
        return xml;
    }

    static String injectDespatchPartyGrt(String xml, Object remitente) {
        if (!(remitente instanceof Map<?, ?> r) || Php.empty(r.get("num_doc")) || Php.empty(r.get("razon_social"))) {
            throw new IllegalStateException("GRT requiere los datos del remitente (tipo_doc, num_doc, razon_social).");
        }
        Object tipoDoc = r.get("tipo_doc") == null ? "6" : r.get("tipo_doc");
        String block = "<cac:DespatchParty><cac:PartyIdentification>"
                + "<cbc:ID schemeID=\"" + Php.toStr(tipoDoc) + "\"" + CAT06 + ">" + XmlWriter.escapeText(Php.toStr(r.get("num_doc"))) + "</cbc:ID>"
                + "</cac:PartyIdentification><cac:PartyLegalEntity><cbc:RegistrationName>" + cdata(r.get("razon_social"))
                + "</cbc:RegistrationName></cac:PartyLegalEntity></cac:DespatchParty>";
        Matcher m = DESPATCH_ADDRESS.matcher(xml);
        if (!m.find()) {
            throw new IllegalStateException("No se encontró el nodo cac:Despatch > cac:DespatchAddress en el XML para inyectar DespatchParty.");
        }
        return xml.substring(0, m.end()) + block + xml.substring(m.end());
    }

    private static String cdata(Object v) {
        return "<![CDATA[" + Php.toStr(v).replace("]]>", "]]]]><![CDATA[>") + "]]>";
    }
}
