package pe.facturacion.api.sunat.builder;

import static pe.facturacion.api.shared.php.Php.coalesce;
import static pe.facturacion.api.shared.php.Php.empty;
import static pe.facturacion.api.shared.php.Php.toFloat;
import static pe.facturacion.api.shared.php.Php.toStr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.sunat.model.SaleModels.Company;
import pe.facturacion.api.sunat.model.SaleModels.Document;
import pe.facturacion.api.sunat.model.TicketModels.Summary;
import pe.facturacion.api.sunat.model.TicketModels.SummaryDetail;
import pe.facturacion.api.sunat.model.TicketModels.Voided;
import pe.facturacion.api.sunat.model.TicketModels.VoidedDetail;

/** Port de {@code SummaryBuilder} y {@code VoidedBuilder} de API-PRO. */
public final class TicketModelBuilder {

    private TicketModelBuilder() {
    }

    private static Company company(Tenant tenant) {
        Company c = new Company();
        c.ruc = tenant.getRuc();
        c.razonSocial = tenant.getRazonSocial();
        return c;
    }

    /** {@code fecha_referencia} → ReferenceDate; {@code fecha_envio} → IssueDate (e identificador). */
    @SuppressWarnings("unchecked")
    public static Summary summary(Tenant tenant, Map<String, Object> data) {
        Summary s = new Summary();
        s.fecGeneracion = PhpDates.parseDate(toStr(data.get("fecha_referencia")));
        s.fecResumen = PhpDates.parseDate(toStr(data.get("fecha_envio")));
        s.correlativo = toStr(data.get("correlativo"));
        s.company = company(tenant);
        List<SummaryDetail> details = new ArrayList<>();
        for (Object o : (List<Object>) data.get("detalles")) {
            Map<String, Object> det = (Map<String, Object>) o;
            SummaryDetail d = new SummaryDetail();
            d.tipoDoc = toStr(det.get("tipo_documento"));
            d.serieNro = toStr(det.get("serie_nro"));
            d.estado = toStr(det.get("estado"));
            d.clienteTipo = toStr(coalesce(det, "cliente_tipo_doc", coalesce(det, "cliente_tipo", "0")));
            d.clienteNro = toStr(coalesce(det, "cliente_num_doc", coalesce(det, "cliente_nro", "00000000")));
            d.total = toFloat(coalesce(det, "total", 0L));
            d.mtoOperGravadas = toFloat(coalesce(det, "mto_oper_gravadas", 0L));
            d.mtoOperInafectas = toFloat(coalesce(det, "mto_oper_inafectas", 0L));
            d.mtoOperExoneradas = toFloat(coalesce(det, "mto_oper_exoneradas", 0L));
            d.mtoOperGratuitas = toFloat(coalesce(det, "mto_oper_gratuitas", 0L));
            d.mtoIGV = toFloat(coalesce(det, "mto_igv", 0L));
            d.porcentajeIgv = toFloat(coalesce(det, "porcentaje_igv", 18L));
            if (!empty(det.get("mto_oper_exportacion"))) {
                d.mtoOperExportacion = toFloat(det.get("mto_oper_exportacion"));
            }
            if (!empty(det.get("mto_otros_cargos"))) {
                d.mtoOtrosCargos = toFloat(det.get("mto_otros_cargos"));
            }
            if (!empty(det.get("doc_referencia"))) {
                Map<String, Object> ref = (Map<String, Object>) det.get("doc_referencia");
                Document doc = new Document();
                doc.tipoDoc = toStr(ref.get("tipo_doc"));
                doc.nroDoc = toStr(ref.get("nro_doc"));
                d.docReferencia = doc;
            }
            details.add(d);
        }
        s.details = details;
        return s;
    }

    @SuppressWarnings("unchecked")
    /** {@code ReversionBuilder}: mismo modelo con identificador RR (retenciones y percepciones). */
    public static Voided reversion(Tenant tenant, Map<String, Object> data) {
        Voided v = voided(tenant, data);
        v.prefijo = "RR";
        return v;
    }

    public static Voided voided(Tenant tenant, Map<String, Object> data) {
        Voided v = new Voided();
        v.correlativo = toStr(data.get("correlativo"));
        v.fecGeneracion = PhpDates.parseDate(toStr(data.get("fecha_generacion")));
        v.fecComunicacion = PhpDates.parseDate(toStr(data.get("fecha_comunicacion")));
        v.company = company(tenant);
        List<VoidedDetail> details = new ArrayList<>();
        for (Object o : (List<Object>) data.get("detalles")) {
            Map<String, Object> det = (Map<String, Object>) o;
            VoidedDetail d = new VoidedDetail();
            d.tipoDoc = toStr(det.get("tipo_documento"));
            d.serie = toStr(det.get("serie"));
            d.correlativo = toStr(det.get("correlativo"));
            d.desMotivoBaja = toStr(det.get("motivo"));
            details.add(d);
        }
        v.details = details;
        return v;
    }
}
