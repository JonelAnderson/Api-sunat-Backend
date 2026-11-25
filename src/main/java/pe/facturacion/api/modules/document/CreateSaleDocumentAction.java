package pe.facturacion.api.modules.document;

import static pe.facturacion.api.modules.document.SaleDocumentRepository.dec;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.facturacion.api.modules.client.Client;
import pe.facturacion.api.modules.client.ClientResolver;
import pe.facturacion.api.modules.plan.PlanService;
import org.springframework.jdbc.core.JdbcTemplate;

import pe.facturacion.api.modules.sucursal.SucursalRepository;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.builder.PhpDates;

/**
 * {@code CreateInvoiceAction} / {@code CreateBoletaAction}: en una transacción bloquea la serie,
 * resuelve el correlativo, registra el cliente, calcula ítems y totales, genera la leyenda,
 * guarda, suma el uso del mes y encola el envío a SUNAT (al confirmar la transacción).
 */
@Service
public class CreateSaleDocumentAction {

    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern LEADING_DATE = Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2})");

    private final JdbcTemplate jdbc;
    private final SucursalRepository sucursales;
    private final ClientResolver clientResolver;
    private final DocumentCalculationService calculator;
    private final SaleDocumentRepository documents;
    private final PaymentService payments;
    private final PlanService plans;
    private final SendDocumentJob sendJob;

    /**
     * Solo para las pruebas de paridad con API-PRO (sus escenarios traen montos que esta API
     * corrige: detracción 37.76 por 40.00 e ICBPER 0.20). Nunca se activa fuera de las pruebas.
     */
    @org.springframework.beans.factory.annotation.Value("${app.paridad-api-pro:false}")
    boolean paridadApiPro;

    public CreateSaleDocumentAction(JdbcTemplate jdbc, SucursalRepository sucursales, ClientResolver clientResolver,
            DocumentCalculationService calculator, SaleDocumentRepository documents, PaymentService payments, PlanService plans,
            SendDocumentJob sendJob) {
        this.jdbc = jdbc;
        this.sucursales = sucursales;
        this.clientResolver = clientResolver;
        this.calculator = calculator;
        this.documents = documents;
        this.payments = payments;
        this.plans = plans;
        this.sendJob = sendJob;
    }

    /**
     * @param soloRegistro boleta que se informará por resumen diario (queda {@code pendiente})
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public SaleDocument execute(DocumentKind kind, Tenant tenant, Map<String, Object> data, boolean soloRegistro, boolean enviarAutomatico) {
        // Serie activa bloqueada (SELECT ... FOR UPDATE) hasta el fin de la transacción
        List<Map<String, Object>> locked = jdbc.queryForList(
                "SELECT id, correlativo, sucursal_id FROM series WHERE tenant_id = ? AND tipo_documento = ? AND serie = ? "
                        + "AND is_active = true LIMIT 1 FOR UPDATE",
                tenant.getId(), kind.tipoDocumento, Php.toStr(data.get("serie")));
        if (locked.isEmpty()) {
            throw ApiException.notFound();
        }
        long serieId = ((Number) locked.get(0).get("id")).longValue();
        int actual = ((Number) locked.get(0).get("correlativo")).intValue();
        Long sucursalId = locked.get(0).get("sucursal_id") == null ? null : ((Number) locked.get(0).get("sucursal_id")).longValue();

        // Serie::resolveCorrelativo()
        int correlativo;
        Object manual = data.get("correlativo");
        if (manual == null) {
            correlativo = actual + 1;
            jdbc.update("UPDATE series SET correlativo = ?, updated_at = ? WHERE id = ?", correlativo, Clock.nowUtc().withNano(0), serieId);
        } else {
            correlativo = (int) Php.toInt(manual);
            if (correlativo > actual) {
                jdbc.update("UPDATE series SET correlativo = ?, updated_at = ? WHERE id = ?", correlativo, Clock.nowUtc().withNano(0), serieId);
            }
        }
        data.put("correlativo", (long) correlativo);
        data.put("tipo_documento", kind.tipoDocumento);

        var sucursal = sucursalId == null ? null : sucursales.findById(sucursalId).orElse(null);
        Map<String, Object> cliente = (Map<String, Object>) data.get("cliente");
        Client client = clientResolver.resolve(tenant, cliente);

        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        List<Map<String, Object>> calculated = calculator.calculateItems(items, tenant);
        Map<String, Object> totals = calculator.calculateTotals(calculated, data, tenant);
        data.putAll(totals);
        if (!paridadApiPro) {
            validarDetraccion(data);
        }

        if (Php.empty(data.get("leyenda"))) {
            Object percepcion = data.get("percepcion");
            Object mtoTotal = percepcion instanceof Map<?, ?> p ? p.get("mto_total") : null;
            double leyendaTotal = !Php.empty(mtoTotal) ? Php.toFloat(mtoTotal) : Php.toFloat(totals.get("mto_imp_venta"));
            data.put("leyenda", calculator.generateLeyenda(leyendaTotal, Php.toStr(Php.coalesce(data, "tipo_moneda", "PEN"))));
        }

        SaleDocument d = new SaleDocument();
        d.kind = kind;
        d.tenantId = tenant.getId();
        d.sucursalId = sucursal == null ? null : sucursal.getId();
        d.codLocal = sucursal != null && sucursal.getCodLocal() != null
                ? sucursal.getCodLocal()
                : Php.toStr(Php.coalesce(data, "cod_local", "0000"));
        d.clientId = client.getId();
        d.serie = Php.toStr(data.get("serie"));
        d.correlativo = correlativo;
        d.fechaEmision = resolveEmisionDateTime(Php.toStr(data.get("fecha_emision")));
        d.fechaVencimiento = toDate(data.get("fecha_vencimiento"));
        String defaultTipoOperacion = kind == DocumentKind.BOLETA && TaxRateService.REGIMEN_RESTRINGIDO.equals(tenant.getTaxRegime())
                ? "0113" : "0101";
        d.tipoOperacion = tipoOperacion(data, defaultTipoOperacion);
        d.tipoMoneda = Php.toStr(Php.coalesce(data, "tipo_moneda", "PEN"));
        d.formaPago = Php.toStr(Php.coalesce(data, "forma_pago", "Contado"));
        d.clientTipoDoc = Php.toStr(cliente.get("tipo_doc"));
        d.clientNumDoc = Php.toStr(cliente.get("num_doc"));
        d.clientRazonSocial = Php.toStr(cliente.get("razon_social"));
        d.clientDireccion = cliente.get("direccion") == null ? null : Php.toStr(cliente.get("direccion"));
        d.mtoOperGravadas = dec(totals.get("mto_oper_gravadas"));
        d.mtoOperExoneradas = dec(totals.get("mto_oper_exoneradas"));
        d.mtoOperInafectas = dec(totals.get("mto_oper_inafectas"));
        d.mtoOperExportacion = dec(totals.get("mto_oper_exportacion"));
        d.mtoOperGratuitas = dec(totals.get("mto_oper_gratuitas"));
        d.mtoIgv = dec(totals.get("mto_igv"));
        d.mtoBaseIvap = dec(totals.get("mto_base_ivap"));
        d.mtoIvap = dec(totals.get("mto_ivap"));
        d.mtoIsc = dec(totals.get("mto_isc"));
        d.mtoIcbper = dec(totals.get("mto_icbper"));
        d.totalImpuestos = dec(totals.get("total_impuestos"));
        d.valorVenta = dec(totals.get("valor_venta"));
        d.subTotal = dec(totals.get("sub_total"));
        d.mtoImpVenta = dec(totals.get("mto_imp_venta"));
        d.totalAnticipos = dec(data.get("total_anticipos") != null
                ? data.get("total_anticipos")
                : DocumentCalculationService.collectionSum(Php.coalesce(data, "anticipos", List.of()), "monto"));
        d.totalDescuentos = dec(Php.coalesce(data, "total_descuentos", 0L));
        d.leyenda = data.get("leyenda") == null ? null : Php.toStr(data.get("leyenda"));
        d.observacion = data.get("observacion") == null ? null : Php.toStr(data.get("observacion"));
        d.cuotas = data.get("cuotas");
        d.detraccion = data.get("detraccion");
        d.percepcion = data.get("percepcion");
        d.anticipos = data.get("anticipos");
        d.descuentosGlobales = data.get("descuentos_globales");
        d.guias = data.get("guias");
        d.contratoColaboracion = data.get("contrato_colaboracion");
        d.extras = data.get("extras");
        d.leyendas = data.get("leyendas");
        if (kind.isNote()) {
            d.docAfectadoTipo = data.get("doc_afectado_tipo") == null ? null : Php.toStr(data.get("doc_afectado_tipo"));
            d.docAfectadoSerie = data.get("doc_afectado_serie") == null ? null : Php.toStr(data.get("doc_afectado_serie"));
            d.docAfectadoCorrelativo = data.get("doc_afectado_correlativo") == null ? null : Php.toStr(data.get("doc_afectado_correlativo"));
            d.codMotivo = Php.toStr(data.get("cod_motivo"));
            d.desMotivo = Php.toStr(data.get("des_motivo"));
        }
        d.sunatStatus = "pendiente";
        d.modoEnvio = soloRegistro ? "solo_registro" : enviarAutomatico ? "automatico" : "manual";
        documents.insert(d);

        List<SaleDocument.SaleItem> rows = new ArrayList<>();
        for (Map<String, Object> c : calculated) {
            rows.add(toItem(c));
        }
        documents.insertItems(d, rows);
        d.items = rows;

        // Un solo punto de conteo de uso (API-PRO podía contar dos veces por event discovery)
        plans.incrementDocumentUsage(tenant);

        if (!soloRegistro && enviarAutomatico) {
            sendJob.dispatch(kind, d.id);
            d.sunatStatus = "enviado";
            documents.update(d, Map.of("sunat_status", "enviado"));
        }

        if (!Php.empty(data.get("pagos"))) {
            payments.register(d, (List<Object>) data.get("pagos"));
        }
        return d;
    }

    /**
     * Corrección: el tipo de operación guardado es el mismo que lleva el XML (InvoiceModelBuilder):
     * con detracción 1001 y con percepción 2001 salvo que vengan en su bloque. API-PRO guardaba
     * 0101 aunque el XML saliera con 1001.
     */
    @SuppressWarnings("unchecked")
    static String tipoOperacion(Map<String, Object> data, String porDefecto) {
        String tipo = Php.toStr(Php.coalesce(data, "tipo_operacion", porDefecto));
        if (!Php.empty(data.get("detraccion")) && data.get("detraccion") instanceof Map<?, ?> det) {
            tipo = Php.toStr(Php.coalesce((Map<String, Object>) det, "tipo_operacion", "1001"));
        }
        if (!Php.empty(data.get("percepcion")) && data.get("percepcion") instanceof Map<?, ?> per) {
            tipo = Php.toStr(Php.coalesce((Map<String, Object>) per, "tipo_operacion", "2001"));
        }
        return tipo;
    }

    /**
     * Corrección: el monto de la detracción es el porcentaje del importe total (en soles). API-PRO
     * lo tomaba del cliente sin revisar (37.76 por 40.00). Se tolera 1.00 por el redondeo a enteros
     * con que se deposita; en moneda extranjera no se valida (depende del tipo de cambio del depósito).
     */
    @SuppressWarnings("unchecked")
    static void validarDetraccion(Map<String, Object> data) {
        if (Php.empty(data.get("detraccion")) || !(data.get("detraccion") instanceof Map<?, ?> m)
                || !"PEN".equals(Php.toStr(Php.coalesce(data, "tipo_moneda", "PEN")))) {
            return;
        }
        Map<String, Object> det = (Map<String, Object>) m;
        double esperado = pe.facturacion.api.shared.php.PhpMath.round(
                Php.toFloat(data.get("mto_imp_venta")) * Php.toFloat(det.get("porcentaje")) / 100, 2);
        double monto = Php.toFloat(det.get("monto"));
        if (Math.abs(monto - esperado) > 1.0) {
            throw ApiException.of(422, "El monto de la detracción (" + String.format(java.util.Locale.ROOT, "%.2f", monto)
                    + ") no corresponde al " + Php.toStr(det.get("porcentaje")) + "% del importe total (" + String.format(java.util.Locale.ROOT, "%.2f", esperado) + ").",
                    Map.of("detraccion.monto", List.of("Debe ser " + String.format(java.util.Locale.ROOT, "%.2f", esperado) + ".")));
        }
    }

    /** Ítem calculado → fila (los floats pasan por la conversión de PDO). */
    static SaleDocument.SaleItem toItem(Map<String, Object> c) {
        SaleDocument.SaleItem i = new SaleDocument.SaleItem();
        i.codigo = c.get("codigo") == null ? null : Php.toStr(c.get("codigo"));
        i.codProductoSunat = c.get("cod_producto_sunat") == null ? null : Php.toStr(c.get("cod_producto_sunat"));
        i.descripcion = Php.toStr(c.get("descripcion"));
        i.unidad = Php.toStr(c.get("unidad"));
        i.cantidad = dec(c.get("cantidad"));
        i.mtoValorUnitario = dec(c.get("mto_valor_unitario"));
        i.mtoValorVenta = dec(c.get("mto_valor_venta"));
        i.mtoBaseIgv = dec(c.get("mto_base_igv"));
        i.porcentajeIgv = dec(c.get("porcentaje_igv"));
        i.igv = dec(c.get("igv"));
        i.tipAfeIgv = Php.toStr(c.get("tip_afe_igv"));
        i.isc = dec(c.get("isc"));
        i.icbper = dec(c.get("icbper"));
        i.totalImpuestos = dec(c.get("total_impuestos"));
        i.mtoPrecioUnitario = dec(c.get("mto_precio_unitario"));
        i.descuento = dec(c.get("descuento"));
        i.descuentos = c.get("descuentos");
        i.tipSisIsc = c.get("tip_sis_isc") == null ? null : Php.toStr(c.get("tip_sis_isc"));
        i.porcentajeIsc = dec(c.get("porcentaje_isc"));
        i.factorIcbper = dec(c.get("factor_icbper"));
        return i;
    }

    /**
     * {@code ResolvesEmisionDateTime}: solo fecha → se agrega la hora actual de Lima; con hora
     * (naive o con zona) → hora local de Lima.
     */
    static LocalDateTime resolveEmisionDateTime(String fecha) {
        if (DATE_ONLY.matcher(fecha).matches()) {
            return LocalDate.parse(fecha).atTime(Clock.nowLima().toLocalTime().withNano(0));
        }
        return PhpDates.parseDateTime(fecha);
    }

    /** Cast {@code date} de Eloquent: la parte de fecha del valor recibido. */
    static LocalDate toDate(Object v) {
        if (v == null || Php.toStr(v).isEmpty()) {
            return null;
        }
        Matcher m = LEADING_DATE.matcher(Php.toStr(v));
        return m.find() ? LocalDate.parse(m.group(1)) : null;
    }
}
