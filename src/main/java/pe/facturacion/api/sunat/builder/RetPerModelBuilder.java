package pe.facturacion.api.sunat.builder;

import java.util.List;
import java.util.Map;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.sunat.model.RetPerModels.Detail;
import pe.facturacion.api.sunat.model.RetPerModels.Doc;
import pe.facturacion.api.sunat.model.RetPerModels.Exchange;
import pe.facturacion.api.sunat.model.RetPerModels.Payment;
import pe.facturacion.api.sunat.model.SaleModels.Address;
import pe.facturacion.api.sunat.model.SaleModels.Client;
import pe.facturacion.api.sunat.model.SaleModels.Company;

/**
 * {@code RetentionBuilder} / {@code PerceptionBuilder} de API-PRO: arma el modelo desde el
 * arreglo del trabajo de envío ({@code retentionToArray} / {@code perceptionToArray}).
 */
public final class RetPerModelBuilder {

    private RetPerModelBuilder() {
    }

    @SuppressWarnings("unchecked")
    public static Doc build(Tenant tenant, Map<String, Object> data, boolean perception) {
        String monto = perception ? "imp_percibido" : "imp_retenido";
        String neto = perception ? "imp_cobrado" : "imp_pagado";
        Doc doc = new Doc();
        doc.perception = perception;
        doc.serie = Php.toStr(data.get("serie"));
        doc.correlativo = Php.toStr(data.get("correlativo"));
        doc.fechaEmision = PhpDates.parseDateTime(Php.toStr(data.get("fecha_emision")));
        doc.company = company(tenant, Php.toStr(Php.coalesce(data, "cod_local", "0000")));
        doc.tercero = tercero((Map<String, Object>) data.get(perception ? "cliente" : "proveedor"));
        doc.regimen = Php.toStr(data.get("regimen"));
        doc.tasa = Php.toFloat(data.get("tasa"));
        doc.impMonto = Php.toFloat(Php.coalesce(data, monto, 0L));
        doc.impNeto = Php.toFloat(Php.coalesce(data, neto, 0L));
        if (!Php.empty(data.get("observacion"))) {
            doc.observacion = Php.toStr(data.get("observacion"));
        }
        double tasa = Php.toFloat(data.get("tasa"));
        for (Object o : (List<Object>) data.get("documentos")) {
            doc.details.add(detail((Map<String, Object>) o, tasa, perception));
        }
        if (Php.empty(data.get(monto)) || Php.empty(data.get(neto))) {
            double totalMonto = 0;
            double totalNeto = 0;
            for (Detail d : doc.details) {
                totalMonto += d.impMonto;
                totalNeto += d.impNeto;
            }
            doc.impMonto = PhpMath.round(totalMonto, 2);
            doc.impNeto = PhpMath.round(totalNeto, 2);
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static Detail detail(Map<String, Object> doc, double tasa, boolean perception) {
        Detail d = new Detail();
        String moneda = Php.toStr(Php.coalesce(doc, "moneda", "PEN"));
        double impTotal = Php.toFloat(doc.get("imp_total"));
        Object monto = doc.get(perception ? "imp_percibido" : "imp_retenido");
        d.impMonto = monto != null ? Php.toFloat(monto) : PhpMath.round(impTotal * tasa / 100, 2);
        Object neto = doc.get(perception ? "imp_cobrar" : "imp_pagar");
        d.impNeto = neto != null ? Php.toFloat(neto)
                : PhpMath.round(perception ? impTotal + d.impMonto : impTotal - d.impMonto, 2);
        d.tipoDoc = Php.toStr(doc.get("tipo_doc"));
        d.numDoc = Php.toStr(doc.get("num_doc"));
        d.fechaEmision = PhpDates.parseDate(Php.toStr(doc.get("fecha_emision")));
        d.impTotal = impTotal;
        d.moneda = moneda;
        d.fecha = PhpDates.parseDate(Php.toStr(doc.get(perception ? "fecha_percepcion" : "fecha_retencion")));
        for (Object o : (List<Object>) doc.get(perception ? "cobros" : "pagos")) {
            Map<String, Object> pago = (Map<String, Object>) o;
            Payment p = new Payment();
            p.moneda = Php.toStr(Php.coalesce(pago, "moneda", moneda));
            p.importe = Php.toFloat(pago.get("importe"));
            p.fecha = PhpDates.parseDate(Php.toStr(pago.get("fecha")));
            d.pagos.add(p);
        }
        if (!Php.empty(doc.get("tipo_cambio"))) {
            Map<String, Object> tc = (Map<String, Object>) doc.get("tipo_cambio");
            Exchange e = new Exchange();
            e.monedaRef = Php.toStr(tc.get("moneda_ref"));
            e.monedaObj = Php.toStr(tc.get("moneda_obj"));
            e.factor = Php.toFloat(tc.get("factor"));
            e.fecha = PhpDates.parseDate(Php.toStr(tc.get("fecha")));
            d.tipoCambio = e;
        }
        return d;
    }

    /** Emisor: sin departamento/provincia/distrito (API-PRO no los asigna en estos documentos). */
    private static Company company(Tenant tenant, String codLocal) {
        Company c = new Company();
        c.ruc = tenant.getRuc();
        c.razonSocial = tenant.getRazonSocial();
        if (Php.truthy(tenant.getNombreComercial())) {
            c.nombreComercial = tenant.getNombreComercial();
        }
        Address a = new Address();
        a.codLocal = codLocal;
        a.direccion = tenant.getDireccion() == null ? "-" : tenant.getDireccion();
        if (Php.truthy(tenant.getUbigeo())) {
            a.ubigueo = tenant.getUbigeo();
        }
        c.address = a;
        return c;
    }

    private static Client tercero(Map<String, Object> data) {
        Client c = new Client();
        c.tipoDoc = Php.toStr(data.get("tipo_doc"));
        c.numDoc = Php.toStr(data.get("num_doc"));
        c.rznSocial = Php.toStr(data.get("razon_social"));
        if (!Php.empty(data.get("direccion"))) {
            Address a = new Address();
            a.direccion = Php.toStr(data.get("direccion"));
            c.address = a;
        }
        return c;
    }
}
