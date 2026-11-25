package pe.facturacion.api.modules.settlement;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.modules.webhook.WebhookJob;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.queue.JobQueue.JobContext;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatSender;
import pe.facturacion.api.sunat.model.SaleModels.Address;
import pe.facturacion.api.sunat.model.SaleModels.Company;
import pe.facturacion.api.sunat.model.SettlementModels.Line;
import pe.facturacion.api.sunat.model.SettlementModels.Place;
import pe.facturacion.api.sunat.model.SettlementModels.Property;
import pe.facturacion.api.sunat.model.SettlementModels.Settlement;
import pe.facturacion.api.sunat.xml.SettlementXmlBuilder;

/**
 * Envío de la liquidación de compra: {@code sendBill} al servicio de comprobantes (el mismo de la
 * factura: RS 097-2012/SUNAT art. 10 num. 10.5 b), CDR síncrono. 10 intentos con backoff; un error
 * de certificado deja la liquidación {@code pendiente}; el CDR aceptado guarda {@code cdr_fecha},
 * base del plazo de reversión.
 */
@Component
public class SendSettlementJob implements JobQueue.JobHandler {

    public static final String TYPE = "send-settlement";
    static final long[] BACKOFF = {15, 30, 60, 60, 120, 120, 300, 300, 600, 600};
    static final int TRIES = 10;
    private static final List<String> RETRYABLE = List.of("0", "100", "109", "500", "1033", "2800", "HTTP", "NETWORK_ERROR");
    static final Map<String, String> ORO = Map.of(
            "6000", "Comercialización de Oro :  Código Unico Concesión Minera",
            "6004", "Comercialización de Oro : Ley Mineral (% concent. oro)",
            "6005", "Comercialización de Oro : Naturaleza del mineral",
            "6006", "Comercialización de Oro : Nombre del derecho minero");
    static final Map<String, String> ORO_CAMPOS = Map.of(
            "6000", "codigo_concesion", "6004", "ley_mineral", "6005", "naturaleza_mineral", "6006", "nombre_derecho_minero");

    private final JobQueue queue;
    private final SettlementRepository repo;
    private final TenantRepository tenants;
    private final SunatSender sender;
    private final FileStorage storage;
    private final WebhookJob webhooks;
    private final pe.facturacion.api.modules.pdf.PdfService pdf;

    public SendSettlementJob(JobQueue queue, SettlementRepository repo, TenantRepository tenants, SunatSender sender, FileStorage storage,
            WebhookJob webhooks, @org.springframework.context.annotation.Lazy pe.facturacion.api.modules.pdf.PdfService pdf) {
        this.queue = queue;
        this.repo = repo;
        this.tenants = tenants;
        this.sender = sender;
        this.storage = storage;
        this.webhooks = webhooks;
        this.pdf = pdf;
    }

    @PostConstruct
    void register() {
        queue.register(this);
    }

    public void dispatch(long id) {
        queue.dispatch(TYPE, Map.of("id", id));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String queue() {
        return "sunat";
    }

    @Override
    public int tries() {
        return TRIES;
    }

    @Override
    public long backoff(int attempt) {
        return attempt - 1 < BACKOFF.length ? BACKOFF[attempt - 1] : 600;
    }

    @Override
    public void handle(JobContext ctx) {
        long id = ((Number) ctx.data().get("id")).longValue();
        Map<String, Object> row = repo.findById(id).orElse(null);
        if (row == null || !"enviado".equals(row.get("sunat_status")) && !"pendiente".equals(row.get("sunat_status"))) {
            return;
        }
        Tenant tenant = tenants.findById(((Number) row.get("tenant_id")).longValue()).orElse(null);
        if (tenant == null) {
            return;
        }
        Settlement doc = model(tenant, row, repo.items(id));
        SunatSender.Result r;
        try {
            r = sender.sendBill(tenant, doc.name(), sender.signXml(tenant, SettlementXmlBuilder.build(doc)));
        } catch (SunatSender.ConfigurationException e) {
            repo.update(id, map("sunat_status", "pendiente", "sunat_code", "CERT_ERROR", "sunat_description", e.getMessage()));
            return;
        }
        if (r.xml() != null) {
            String xmlPath = path(tenant, row, "xml") + "/" + numero(row) + ".xml";
            storage.put(Disk.PRIVATE, xmlPath, r.xml().getBytes(StandardCharsets.UTF_8));
            repo.update(id, Map.of("xml_path", xmlPath));
        }
        if (r.success()) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("sunat_status", "aceptado");
            ch.put("sunat_code", r.code());
            ch.put("sunat_description", r.description());
            ch.put("sunat_notes", r.notes());
            ch.put("hash_cpe", r.hash());
            LocalDateTime now = Clock.nowUtc().withNano(0);
            ch.put("sent_at", now);
            ch.put("cdr_fecha", Clock.nowLima().withNano(0));
            if (r.cdrZip() != null) {
                String cdrPath = path(tenant, row, "cdr") + "/R-" + numero(row) + ".zip";
                storage.put(Disk.PRIVATE, cdrPath, r.cdrZip());
                ch.put("cdr_path", cdrPath);
            }
            repo.update(id, ch);
            pdf.storeSettlement(id, tenant);
            webhook(tenant, id, "purchase_settlement.accepted");
            return;
        }
        String code = r.errorCode() == null ? "" : r.errorCode();
        if (RETRYABLE.contains(code)) {
            if (ctx.attempts() >= TRIES) {
                reject(tenant, id, "MAX_RETRIES", "Agotados " + TRIES + " intentos: " + r.errorMessage());
                return;
            }
            ctx.release(backoff(ctx.attempts()));
            return;
        }
        reject(tenant, id, code, r.errorMessage());
    }

    private void reject(Tenant tenant, long id, String code, String message) {
        repo.update(id, map("sunat_status", "rechazado", "sunat_code", code.length() > 20 ? code.substring(0, 20) : code,
                "sunat_description", message));
        webhook(tenant, id, "purchase_settlement.rejected");
    }

    private void webhook(Tenant tenant, long id, String event) {
        if (tenant.getWebhookUrl() != null && !tenant.getWebhookUrl().isEmpty()) {
            webhooks.dispatchRow(SettlementRepository.TABLE, id, event);
        }
    }

    /** Modelo del XML desde la fila guardada. */
    @SuppressWarnings("unchecked")
    public static Settlement model(Tenant tenant, Map<String, Object> row, List<Map<String, Object>> items) {
        Settlement s = new Settlement();
        s.serie = (String) row.get("serie");
        s.correlativo = ((Number) row.get("correlativo")).longValue();
        s.fechaEmision = (LocalDateTime) row.get("fecha_emision");
        s.tipoOperacion = (String) row.get("tipo_operacion");
        s.moneda = (String) row.get("tipo_moneda");
        Company c = new Company();
        c.ruc = tenant.getRuc();
        c.razonSocial = tenant.getRazonSocial();
        c.nombreComercial = tenant.getNombreComercial();
        Address a = new Address();
        a.direccion = tenant.getDireccion() == null ? "-" : tenant.getDireccion();
        a.ubigueo = tenant.getUbigeo();
        a.departamento = tenant.getDepartamento();
        a.provincia = tenant.getProvincia();
        a.distrito = tenant.getDistrito();
        c.address = a;
        s.comprador = c;
        s.vendedorTipoDoc = (String) row.get("vendedor_tipo_doc");
        s.vendedorNumDoc = (String) row.get("vendedor_num_doc");
        s.vendedorNombre = (String) row.get("vendedor_nombre");
        s.vendedorDomicilio = place((String) row.get("vendedor_direccion"), (String) row.get("vendedor_ubigeo"), (String) row.get("vendedor_tipo_domicilio"));
        s.lugar = place((String) row.get("lugar_direccion"), (String) row.get("lugar_ubigeo"), (String) row.get("lugar_tipo"));
        s.gravadas = (BigDecimal) row.get("mto_oper_gravadas");
        s.exoneradas = (BigDecimal) row.get("mto_oper_exoneradas");
        s.inafectas = (BigDecimal) row.get("mto_oper_inafectas");
        s.igv = (BigDecimal) row.get("mto_igv");
        s.valorVenta = (BigDecimal) row.get("valor_venta");
        s.baseRenta = (BigDecimal) row.get("mto_base_renta");
        s.renta = (BigDecimal) row.get("mto_retencion_renta");
        s.subTotal = (BigDecimal) row.get("sub_total");
        s.neto = (BigDecimal) row.get("mto_imp_neto");
        s.leyenda = (String) row.get("leyenda");
        s.placa = (String) row.get("placa_vehiculo");
        if (row.get("guias") instanceof List<?> guias) {
            for (Object o : guias) {
                Map<String, Object> g = (Map<String, Object>) o;
                s.guias.add(new String[] {Php.toStr(g.get("tipo")), Php.toStr(g.get("numero"))});
            }
        }
        for (Map<String, Object> it : items) {
            Line l = new Line();
            l.codigo = (String) it.get("codigo");
            l.codProductoSunat = (String) it.get("cod_producto_sunat");
            l.descripcion = (String) it.get("descripcion");
            l.unidad = (String) it.get("unidad");
            l.cantidad = (BigDecimal) it.get("cantidad");
            l.valorUnitario = (BigDecimal) it.get("valor_unitario");
            l.precioUnitario = (BigDecimal) it.get("precio_unitario");
            l.tipAfeIgv = (String) it.get("tip_afe_igv");
            l.valorVenta = (BigDecimal) it.get("valor_venta");
            l.porcentajeIgv = (BigDecimal) it.get("porcentaje_igv");
            l.igv = (BigDecimal) it.get("igv");
            l.porcentajeRenta = (BigDecimal) it.get("porcentaje_renta");
            l.renta = (BigDecimal) it.get("renta");
            if (it.get("oro") instanceof Map<?, ?> oro) {
                for (String code : List.of("6000", "6004", "6005", "6006")) {
                    Object v = oro.get(ORO_CAMPOS.get(code));
                    if (v != null) {
                        l.propiedades.add(new Property(code, ORO.get(code), Php.toStr(v)));
                    }
                }
            }
            s.lines.add(l);
        }
        return s;
    }

    private static Place place(String direccion, String ubigeo, String tipo) {
        Place p = new Place();
        p.direccion = direccion;
        p.ubigeo = ubigeo;
        p.tipo = tipo;
        return p;
    }

    /** {ruc}/{cod_local}/{fecha}/liquidaciones_compra/{tipo}. */
    static String path(Tenant tenant, Map<String, Object> row, String type) {
        return tenant.getRuc() + "/" + row.get("cod_local") + "/" + ((LocalDateTime) row.get("fecha_emision")).toLocalDate()
                + "/liquidaciones_compra/" + type;
    }

    /** Serie-correlativo con 8 dígitos (el correlativo de la liquidación admite hasta 8). */
    static String numero(Map<String, Object> row) {
        return row.get("serie") + "-" + String.format("%08d", ((Number) row.get("correlativo")).longValue());
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
