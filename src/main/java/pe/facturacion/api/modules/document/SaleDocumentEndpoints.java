package pe.facturacion.api.modules.document;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import pe.facturacion.api.modules.catalog.SunatRules;
import pe.facturacion.api.modules.client.Client;
import pe.facturacion.api.modules.client.ClientRepository;
import pe.facturacion.api.modules.client.ClientResolver;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.api.Paginator;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.validation.ValidationException;
import pe.facturacion.api.shared.validation.Validator;

/**
 * Lógica de {@code InvoiceController}, {@code BoletaController} y {@code PaymentController}
 * (mismas respuestas), compartida por los controladores de facturas y boletas.
 */
@Service
public class SaleDocumentEndpoints {

    private static final Logger log = LoggerFactory.getLogger(SaleDocumentEndpoints.class);
    private static final List<String> ORDER_COLUMNS = List.of("fecha_emision", "fecha_vencimiento", "created_at", "updated_at",
            "correlativo", "mto_imp_venta", "sunat_status", "payment_status", "client_razon_social");

    private final SaleDocumentRequest request;
    private final CreateSaleDocumentAction createAction;
    private final SaleDocumentRepository documents;
    private final ClientRepository clients;
    private final ClientResolver clientResolver;
    private final DocumentCalculationService calculator;
    private final DocumentStorage storage;
    private final PaymentService payments;
    private final SendDocumentJob sendJob;
    private final EmissionPolicy emission;
    private final TransactionTemplate tx;

    public SaleDocumentEndpoints(SaleDocumentRequest request, CreateSaleDocumentAction createAction, SaleDocumentRepository documents,
            ClientRepository clients, ClientResolver clientResolver, DocumentCalculationService calculator, DocumentStorage storage,
            PaymentService payments, SendDocumentJob sendJob, EmissionPolicy emission, TransactionTemplate tx) {
        this.request = request;
        this.createAction = createAction;
        this.documents = documents;
        this.clients = clients;
        this.clientResolver = clientResolver;
        this.calculator = calculator;
        this.storage = storage;
        this.payments = payments;
        this.sendJob = sendJob;
        this.emission = emission;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ store

    public ResponseEntity<Object> store(DocumentKind kind, HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return store(kind, tenant, Input.of(http));
    }

    /**
     * Emisión para la empresa indicada: la misma tubería de {@code POST /api/v1/{tipo}} (cupo del plan, validación,
     * correlativo y envío encolado). La usa también el panel de administración.
     */
    public ResponseEntity<Object> store(DocumentKind kind, Tenant tenant, Input input) {
        ResponseEntity<Object> denied = emission.deny(tenant);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> data = request.validate(kind, tenant, input.all());
        boolean enviarAuto = booleanInput(input, "enviar_automatico", true);
        boolean soloRegistro = kind == DocumentKind.BOLETA && booleanInput(input, "solo_registro", false);
        if (soloRegistro) {
            // Corrección: el resumen diario solo admite boletas de hasta 7 días; fuera de ese plazo
            // quedaba "pendiente" para siempre sin poder declararse (API-PRO la aceptaba igual)
            java.time.LocalDate emision = java.time.LocalDate.parse(Php.toStr(data.get("fecha_emision")).substring(0, 10));
            java.time.LocalDate limite = pe.facturacion.api.shared.time.Clock.todayLima().minusDays(7);
            if (emision.isBefore(limite)) {
                return ApiResponse.error("La boleta con solo_registro debe informarse por resumen diario dentro de los 7 días siguientes a su emisión "
                        + "(fecha mínima " + limite + "). Envíela individualmente (sin solo_registro).", 422);
            }
        }
        try {
            SaleDocument d = createAction.execute(kind, tenant, data, soloRegistro, enviarAuto);
            String msg;
            if (kind != DocumentKind.BOLETA) {
                msg = enviarAuto
                        ? kind.label + " creada y encolada para envío a SUNAT."
                        : kind.label + " creada en estado pendiente. Use POST /" + kind.urlSegment + "/{id}/enviar para enviarla a SUNAT.";
            } else if (soloRegistro) {
                msg = "Boleta registrada. Pendiente de envío vía resumen diario.";
            } else if (!enviarAuto) {
                msg = "Boleta creada en estado pendiente. Use POST /boletas/{id}/enviar para enviarla a SUNAT.";
            } else {
                msg = "Boleta creada y encolada para envío a SUNAT.";
            }
            return ApiResponse.created(full(reload(d), null), msg);
        } catch (ApiException e) {
            throw e; // p. ej. pagos que superan el total: 422, no error interno
        } catch (RuntimeException e) {
            return errorInterno(e, "No se pudo crear la " + kind.labelLower + ".");
        }
    }

    /** {@code POST /facturas/masivo} con la validación completa por ítem (SEC-010). */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> masivo(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        ResponseEntity<Object> denied = emission.deny(tenant);
        if (denied != null) {
            return denied;
        }
        Object raw = Input.of(http).get("facturas");
        List<Object> list = raw instanceof List<?> l ? new ArrayList<>(l)
                : raw instanceof Map<?, ?> m ? new ArrayList<>(m.values()) : null;
        if (list == null || list.isEmpty()) {
            return ApiResponse.error("El campo \"facturas\" debe ser un array con al menos 1 elemento.", 422);
        }
        if (list.size() > 100) {
            return ApiResponse.error("Máximo 100 facturas por envío masivo.", 422);
        }
        List<Object> creadas = new ArrayList<>();
        List<Object> errores = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            if (!(item instanceof Map<?, ?>)) {
                errores.add(map("indice", index, "mensaje", "Cada elemento de \"facturas\" debe ser un objeto.", "data", item));
                continue;
            }
            Map<String, Object> data = (Map<String, Object>) item;
            Map<String, Object> validated;
            try {
                validated = request.validate(DocumentKind.FACTURA, tenant, data);
            } catch (ValidationException e) {
                errores.add(map("indice", index, "errores", e.getErrors(), "data", data));
                continue;
            }
            try {
                SaleDocument d = createAction.execute(DocumentKind.FACTURA, tenant, validated, false, true);
                creadas.add(map("indice", index, "id", d.id, "numero_completo", d.numeroCompleto(),
                        "total", SaleDocumentRepository.cast(d.mtoImpVenta, 2), "estado_sunat", d.sunatStatus));
            } catch (ApiException e) {
                errores.add(map("indice", index, "mensaje", e.getMessage(), "data", data));
            } catch (RuntimeException e) {
                String ref = UUID.randomUUID().toString();
                log.error("No se pudo crear la factura. error_id={}", ref, e);
                errores.add(map("indice", index, "mensaje", "No se pudo crear la factura. (ref: " + ref + ")", "data", data));
            }
        }
        int total = creadas.size();
        Map<String, Object> resumen = map("total_enviadas", list.size(), "creadas", total, "errores", errores.size());
        Map<String, Object> datos = map("resumen", resumen, "facturas", creadas, "errores", errores);
        if (total == 0) {
            // Corrección: API-PRO respondía HTTP 422 con "estado":"exito" cuando no se creaba ninguna
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "error");
            body.put("mensaje", "Ninguna factura fue creada. Revisa los errores.");
            body.put("datos", datos);
            return ResponseEntity.status(422).body(body);
        }
        return ApiResponse.success(datos, total + " factura(s) creadas y encoladas para envío a SUNAT.", 201, null);
    }

    // ------------------------------------------------------------------ lectura

    public ResponseEntity<Object> index(DocumentKind kind, HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return index(kind, tenant, Input.of(http));
    }

    /** Listado de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(DocumentKind kind, Tenant tenant, Input input) {
        if (kind.isNote()) {
            return noteIndex(kind, tenant, input);
        }
        boolean withItems = false;
        boolean withPayments = false;
        if (input.filled("con")) {
            List<String> rel = Arrays.asList(Php.toStr(input.get("con")).split(",", -1));
            withItems = rel.contains("items");
            withPayments = rel.contains("payments");
        }
        if (input.filled("correlativo")) {
            withItems = true;
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", tenant.getId());
        String where = filters(input, p);
        String order = order(input);
        int perPage = Paginator.perPage(Math.min(input.integer("por_pagina", 15), 100));
        int page = Paginator.page(input);
        long total = documents.count(kind, where, p);
        List<SaleDocument> rows = documents.query(kind, where, p, order, perPage, Paginator.offset(page, perPage));
        List<Object> datos = new ArrayList<>();
        for (SaleDocument d : rows) {
            if (withItems) {
                documents.loadItems(d);
            }
            datos.add(SaleDocumentResource.toArray(d, client(d), withItems ? d.items : null,
                    withPayments ? documents.payments(d) : null, baseUrl()));
        }
        Paginator<Object> pag = Paginator.of(datos, page, perPage, total);
        return ApiResponse.success(map("datos", datos, "paginacion", pag.paginacion()));
    }

    /**
     * Listado de {@code CreditNoteController}/{@code DebitNoteController}: filtros con {@code has()}
     * (presencia), orden por creación descendente y sin ítems. Corrección: un filtro vacío se ignora
     * (API-PRO respondía 500) y {@code por_pagina} se limita a 1–100.
     */
    private ResponseEntity<Object> noteIndex(DocumentKind kind, Tenant tenant, Input input) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", tenant.getId());
        List<String> w = new ArrayList<>(List.of("tenant_id = :tenant", "deleted_at IS NULL"));
        if (input.filled("sunat_status")) {
            p.addValue("sunat_status", Php.toStr(input.get("sunat_status")));
            w.add("sunat_status = :sunat_status");
        }
        if (input.has("fecha_desde") && input.has("fecha_hasta")) {
            p.addValue("desde", Php.toStr(input.get("fecha_desde")) + " 00:00:00");
            p.addValue("hasta", Php.toStr(input.get("fecha_hasta")) + " 23:59:59");
            w.add("CAST(fecha_emision AS text) BETWEEN :desde AND :hasta");
        }
        for (String col : List.of("serie", "client_num_doc", "tipo_moneda")) {
            if (input.filled(col)) {
                p.addValue(col, Php.toStr(input.get(col)));
                w.add(col + " = :" + col);
            }
        }
        if (input.filled("sucursal_id")) {
            p.addValue("sucursal_id", Php.toInt(input.get("sucursal_id")));
            w.add("sucursal_id = :sucursal_id");
        }
        String where = String.join(" AND ", w);
        int perPage = Paginator.perPage(Math.min(input.integer("por_pagina", 15), 100));
        int page = Paginator.page(input);
        long total = documents.count(kind, where, p);
        List<Object> datos = new ArrayList<>();
        for (SaleDocument d : documents.query(kind, where, p, "created_at desc, id desc", perPage, Paginator.offset(page, perPage))) {
            datos.add(SaleDocumentResource.toNoteArray(d, client(d), null, baseUrl()));
        }
        return ApiResponse.success(map("datos", datos, "paginacion", Paginator.of(datos, page, perPage, total).paginacion()));
    }

    public ResponseEntity<Object> show(DocumentKind kind, HttpServletRequest http, long id) {
        return ApiResponse.success(full(find(kind, http, id), http));
    }

    /** Detalle de un comprobante de la empresa indicada (404 si es de otra). */
    public ResponseEntity<Object> show(DocumentKind kind, Tenant tenant, long id) {
        return ApiResponse.success(full(find(kind, tenant, id), null));
    }

    // ----------------------------------------------------------------- update

    /** {@code PUT}: validación de la rama de seguridad, tasa de IGV del régimen y sin perder datos del ítem. */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> update(DocumentKind kind, HttpServletRequest http, long id) {
        Tenant tenant = ApiContext.requireTenant(http);
        SaleDocument d = find(kind, tenant, id);
        return update(kind, tenant, d, Input.of(http));
    }

    /** Corregir y reenviar un comprobante no aceptado de la empresa indicada (404 si es de otra). Lo usa también el panel. */
    public ResponseEntity<Object> update(DocumentKind kind, Tenant tenant, Input input, long id) {
        return update(kind, tenant, find(kind, tenant, id), input);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Object> update(DocumentKind kind, Tenant tenant, SaleDocument d, Input input) {
        if ("aceptado".equals(d.sunatStatus)) {
            Map<String, Object> docAfectado = map("tipo", kind.tipoDocumento, "serie", d.serie, "correlativo", d.correlativo);
            if (kind.isNote()) {
                return ApiResponse.errorAccionable(
                        "No se puede editar una " + kind.labelLower + " aceptada por SUNAT. Si la "
                                + (kind == DocumentKind.NOTA_CREDITO ? "NC" : "ND") + " es errónea, anúlela por comunicación de baja.",
                        "documento_aceptado_no_editable",
                        map("operacion", "anular_por_comunicacion_baja", "endpoint", "POST /api/v1/anulaciones", "doc_afectado", docAfectado), 422);
            }
            return ApiResponse.errorAccionable(
                    "No se puede editar una " + kind.labelLower + " aceptada por SUNAT. Emita una Nota de Crédito para corregir o anular.",
                    "documento_aceptado_no_editable",
                    map("operacion", "emitir_nota_credito", "endpoint", "POST /api/v1/notas-credito", "doc_afectado", docAfectado), 422);
        }
        ResponseEntity<Object> anulado = blockAnulado(kind, d);
        if (anulado != null) {
            return anulado;
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("cliente", "sometimes|array");
        rules.put("cliente.tipo_doc", List.of("sometimes", "string", SunatRules.catalog("06")));
        rules.put("cliente.num_doc", "sometimes|string|max:15");
        rules.put("cliente.razon_social", "sometimes|string|max:1500");
        rules.put("cliente.direccion", "nullable|string|max:500");
        rules.put("tipo_moneda", "sometimes|string|in:PEN,USD,EUR");
        rules.put("forma_pago", "sometimes|string|in:Contado,Credito,Tarjeta,Billetera Digital,Deposito,Transferencia");
        rules.put("fecha_vencimiento", "nullable|date");
        rules.put("observacion", "nullable|string|max:500");
        rules.put("leyenda", "nullable|string|max:500");
        rules.put("items", "sometimes|array|min:1");
        rules.put("items.*.descripcion", "required_with:items|string|max:500");
        rules.put("items.*.unidad", "required_with:items|string|max:10");
        rules.put("items.*.cantidad", "required_with:items|numeric|gt:0");
        rules.put("items.*.precio_unitario", "required_with:items|numeric|min:0");
        if (kind.isNote()) {
            rules.remove("forma_pago");
            rules.remove("fecha_vencimiento");
            rules.put("doc_afectado_tipo", "sometimes|nullable|string|in:01,03,12");
            rules.put("doc_afectado_serie", "sometimes|nullable|string|size:4");
            rules.put("doc_afectado_correlativo", "sometimes|nullable|string");
            rules.put("cod_motivo", List.of("sometimes", "string", SunatRules.catalog(kind == DocumentKind.NOTA_CREDITO ? "09" : "10")));
            rules.put("des_motivo", "sometimes|string|max:250");
        }
        rules.put("enviar_automatico", "sometimes|boolean");
        Validator.make(input.all(), rules).validate();
        Map<String, Object> data = new LinkedHashMap<>(input.all());
        // Corrección: se respeta el modo con que se creó (API-PRO siempre encolaba el envío);
        // enviar_automatico en el cuerpo decide solo para esta edición
        boolean enviar = input.all().containsKey("enviar_automatico") ? booleanInput(input, "enviar_automatico", true)
                : "automatico".equals(d.modoEnvio);

        tx.executeWithoutResult(status -> {
            if (!Php.empty(data.get("cliente")) && data.get("cliente") instanceof Map<?, ?> cm) {
                Map<String, Object> c = (Map<String, Object>) cm;
                d.clientTipoDoc = c.get("tipo_doc") != null ? Php.toStr(c.get("tipo_doc")) : d.clientTipoDoc;
                d.clientNumDoc = c.get("num_doc") != null ? Php.toStr(c.get("num_doc")) : d.clientNumDoc;
                d.clientRazonSocial = c.get("razon_social") != null ? Php.toStr(c.get("razon_social")) : d.clientRazonSocial;
                d.clientDireccion = c.get("direccion") != null ? Php.toStr(c.get("direccion")) : d.clientDireccion;
                Map<String, Object> resolve = new LinkedHashMap<>();
                resolve.put("tipo_doc", d.clientTipoDoc);
                resolve.put("num_doc", d.clientNumDoc);
                resolve.put("razon_social", d.clientRazonSocial);
                resolve.put("direccion", d.clientDireccion);
                clientResolver.resolve(tenant, resolve);
            }
            if (data.containsKey("fecha_vencimiento")) {
                d.fechaVencimiento = CreateSaleDocumentAction.toDate(data.get("fecha_vencimiento"));
            }
            if (data.containsKey("tipo_operacion")) {
                d.tipoOperacion = data.get("tipo_operacion") == null ? d.tipoOperacion : Php.toStr(data.get("tipo_operacion"));
            }
            if (data.containsKey("tipo_moneda") && data.get("tipo_moneda") != null) {
                d.tipoMoneda = Php.toStr(data.get("tipo_moneda"));
            }
            if (data.containsKey("forma_pago") && data.get("forma_pago") != null) {
                d.formaPago = Php.toStr(data.get("forma_pago"));
            }
            if (data.containsKey("observacion")) {
                d.observacion = data.get("observacion") == null ? null : Php.toStr(data.get("observacion"));
            }
            if (kind.isNote()) {
                if (data.containsKey("doc_afectado_tipo")) {
                    d.docAfectadoTipo = data.get("doc_afectado_tipo") == null ? null : Php.toStr(data.get("doc_afectado_tipo"));
                }
                if (data.containsKey("doc_afectado_serie")) {
                    d.docAfectadoSerie = data.get("doc_afectado_serie") == null ? null : Php.toStr(data.get("doc_afectado_serie"));
                }
                if (data.containsKey("doc_afectado_correlativo")) {
                    d.docAfectadoCorrelativo = data.get("doc_afectado_correlativo") == null ? null : Php.toStr(data.get("doc_afectado_correlativo"));
                }
                if (data.containsKey("cod_motivo") && data.get("cod_motivo") != null) {
                    d.codMotivo = Php.toStr(data.get("cod_motivo"));
                }
                if (data.containsKey("des_motivo") && data.get("des_motivo") != null) {
                    d.desMotivo = Php.toStr(data.get("des_motivo"));
                }
            }
            if (!Php.empty(data.get("items"))) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (Object o : asList(data.get("items"))) {
                    items.add((Map<String, Object>) o);
                }
                List<Map<String, Object>> calculated = calculator.calculateItems(items, tenant);
                Map<String, Object> totals = calculator.calculateTotals(calculated, data, tenant);
                d.mtoOperGravadas = SaleDocumentRepository.dec(totals.get("mto_oper_gravadas"));
                d.mtoOperExoneradas = SaleDocumentRepository.dec(totals.get("mto_oper_exoneradas"));
                d.mtoOperInafectas = SaleDocumentRepository.dec(totals.get("mto_oper_inafectas"));
                d.mtoOperExportacion = SaleDocumentRepository.dec(totals.get("mto_oper_exportacion"));
                d.mtoOperGratuitas = SaleDocumentRepository.dec(totals.get("mto_oper_gratuitas"));
                d.mtoIgv = SaleDocumentRepository.dec(totals.get("mto_igv"));
                d.mtoBaseIvap = SaleDocumentRepository.dec(totals.get("mto_base_ivap"));
                d.mtoIvap = SaleDocumentRepository.dec(totals.get("mto_ivap"));
                d.mtoIsc = SaleDocumentRepository.dec(totals.get("mto_isc"));
                d.mtoIcbper = SaleDocumentRepository.dec(totals.get("mto_icbper"));
                d.totalImpuestos = SaleDocumentRepository.dec(totals.get("total_impuestos"));
                d.valorVenta = SaleDocumentRepository.dec(totals.get("valor_venta"));
                d.subTotal = SaleDocumentRepository.dec(totals.get("sub_total"));
                d.mtoImpVenta = SaleDocumentRepository.dec(totals.get("mto_imp_venta"));
                d.totalDescuentos = SaleDocumentRepository.dec(totals.get("total_descuentos"));
                Object percepcion = data.get("percepcion");
                Object mtoTotal = percepcion instanceof Map<?, ?> pm ? pm.get("mto_total") : null;
                double leyendaTotal = !Php.empty(mtoTotal) ? Php.toFloat(mtoTotal) : Php.toFloat(totals.get("mto_imp_venta"));
                d.leyenda = data.get("leyenda") != null ? Php.toStr(data.get("leyenda"))
                        : calculator.generateLeyenda(leyendaTotal, data.get("tipo_moneda") != null ? Php.toStr(data.get("tipo_moneda"))
                                : (d.tipoMoneda != null ? d.tipoMoneda : "PEN"));
                documents.deleteItems(d);
                List<SaleDocument.SaleItem> rows = new ArrayList<>();
                for (Map<String, Object> c : calculated) {
                    rows.add(CreateSaleDocumentAction.toItem(c));
                }
                documents.insertItems(d, rows);
            }
            d.sunatStatus = enviar ? "enviado" : "pendiente";
            d.sunatCode = null;
            d.sunatDescription = null;
            d.sunatNotes = null;
            documents.save(d);
            if (enviar) {
                sendJob.dispatch(kind, d.id);
            }
        });
        String msg = enviar ? kind.label + " actualizada y reenviada a SUNAT."
                : "solo_registro".equals(d.modoEnvio) ? kind.label + " actualizada. Pendiente de envío vía resumen diario."
                : kind.label + " actualizada en estado pendiente. Use POST /" + kind.urlSegment + "/{id}/enviar para enviarla a SUNAT.";
        return ApiResponse.success(full(reload(d), null), msg);
    }

    // ------------------------------------------------------------------ envío

    public ResponseEntity<Object> enviar(DocumentKind kind, HttpServletRequest http, long id) {
        return enviar(kind, ApiContext.requireTenant(http), id);
    }

    /** Enviar o reenviar a SUNAT un comprobante de la empresa indicada (404 si es de otra). Lo usa también el panel. */
    public ResponseEntity<Object> enviar(DocumentKind kind, Tenant tenant, long id) {
        SaleDocument d = find(kind, tenant, id);
        if ("aceptado".equals(d.sunatStatus)) {
            return ApiResponse.error("Esta " + kind.labelLower + " ya fue aceptada por SUNAT.", 422);
        }
        ResponseEntity<Object> anulado = blockAnulado(kind, d);
        if (anulado != null) {
            return anulado;
        }
        tx.executeWithoutResult(status -> {
            // El trabajo se encola al confirmar: no hay carrera que deje "enviado" sobre "aceptado"
            documents.update(d, map("sunat_status", "enviado", "sunat_code", null, "sunat_description", null));
            sendJob.dispatch(kind, d.id);
        });
        SaleDocument fresh = documents.find(kind, d.tenantId, d.id).orElseThrow(ApiException::notFound);
        return ApiResponse.success(fresh.kind.isNote()
                        ? SaleDocumentResource.toNoteArray(fresh, client(fresh), null, baseUrl())
                        : SaleDocumentResource.toArray(fresh, client(fresh), null, null, baseUrl()),
                kind.label + " enviada a SUNAT.");
    }

    /**
     * Consulta en SUNAT el CDR de un comprobante pendiente o enviado ({@code getStatusCdr}) y guarda el resultado con la
     * misma regla del trabajo de envío ({@code SendDocumentJob.recoverFromCdr}): aceptado (con su CDR y PDF) o rechazado.
     * No existe en {@code /api/v1}: lo usa el panel de administración para destrabar un comprobante que se quedó
     * esperando a SUNAT. Si SUNAT no lo tiene o no responde, el comprobante queda como estaba.
     */
    public ResponseEntity<Object> refreshCdr(DocumentKind kind, Tenant tenant, long id) {
        SaleDocument d = find(kind, tenant, id);
        if (!"pendiente".equals(d.sunatStatus) && !"enviado".equals(d.sunatStatus)) {
            return ApiResponse.error("Solo se consulta el CDR de comprobantes pendientes o enviados (estado: " + d.sunatStatus + ").", 422);
        }
        if ("solo_registro".equals(d.modoEnvio)) {
            return ApiResponse.error("La boleta se informa por resumen diario: refresque el resumen que la contiene.", 422);
        }
        boolean resuelto;
        try {
            resuelto = sendJob.recoverFromCdr(d, tenant);
        } catch (RuntimeException e) {
            return errorInterno(e, "No se pudo consultar el CDR en SUNAT.");
        }
        SaleDocument fresh = reload(d);
        if (!resuelto) {
            return ApiResponse.error("SUNAT no devolvió un CDR para la " + kind.labelLower + " (sigue en estado " + fresh.sunatStatus
                    + "). Si nunca se envió, use enviar.", 422);
        }
        return ApiResponse.success(full(fresh, null), "Estado actualizado con el CDR de SUNAT: " + fresh.sunatStatus + ".");
    }

    /** Corrección: un comprobante anulado (o en anulación) no se vuelve a enviar. */
    private static ResponseEntity<Object> blockAnulado(DocumentKind kind, SaleDocument d) {
        if ("anulado".equals(d.sunatStatus) || "anulacion_en_proceso".equals(d.sunatStatus)) {
            return ApiResponse.error("Esta " + kind.labelLower + " está anulada (o en proceso de anulación) y no se puede enviar a SUNAT.", 422);
        }
        return null;
    }

    public ResponseEntity<Object> xml(DocumentKind kind, HttpServletRequest http, long id) {
        return xml(kind, ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> xml(DocumentKind kind, Tenant tenant, long id) {
        SaleDocument d = find(kind, tenant, id);
        byte[] content = storage.xmlContent(d);
        if (content == null || content.length == 0) {
            return ApiResponse.error("XML no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/xml")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.numeroCompleto() + ".xml\"")
                .body(new String(content, StandardCharsets.UTF_8));
    }

    public ResponseEntity<Object> cdr(DocumentKind kind, HttpServletRequest http, long id) {
        return cdr(kind, ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> cdr(DocumentKind kind, Tenant tenant, long id) {
        SaleDocument d = find(kind, tenant, id);
        byte[] content = storage.cdrContent(d);
        if (content == null || content.length == 0) {
            return ApiResponse.error("CDR no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"R-" + d.numeroCompleto() + ".zip\"")
                .body(content);
    }

    /** {@code DELETE /boletas/{id}}: solo boletas que SUNAT no conoce. */
    public ResponseEntity<Object> destroy(DocumentKind kind, HttpServletRequest http, long id) {
        SaleDocument d = find(kind, http, id);
        if (!("pendiente".equals(d.sunatStatus) || "rechazado".equals(d.sunatStatus)) || (d.hashCpe != null && !d.hashCpe.isEmpty())) {
            return ApiResponse.error("No se puede eliminar una boleta ya aceptada por SUNAT. Debe anularla vía resumen RC.", 422);
        }
        tx.executeWithoutResult(status -> {
            documents.deleteItems(d);
            documents.softDelete(d);
        });
        return ApiResponse.success(null, "Boleta eliminada localmente.");
    }

    // ----------------------------------------------------------------- pagos

    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> paymentStore(DocumentKind kind, HttpServletRequest http, String id) {
        Tenant tenant = ApiContext.requireTenant(http);
        Map<String, Object> data = new LinkedHashMap<>(Input.of(http).all());
        if (data.containsKey("metodo") && !data.containsKey("pagos")) {
            Map<String, Object> pago = new LinkedHashMap<>();
            pago.put("metodo", data.get("metodo"));
            pago.put("monto", data.get("monto"));
            pago.put("referencia", data.get("referencia"));
            pago.put("monto_recibido", data.get("monto_recibido"));
            pago.put("notas", data.get("notas"));
            data.put("pagos", new ArrayList<>(List.of(pago)));
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("pagos", "required|array|min:1");
        rules.put("pagos.*.metodo", "required|string|in:" + String.join(",", PaymentService.METODOS));
        rules.put("pagos.*.monto", "required|numeric|min:0.01");
        rules.put("pagos.*.referencia", "nullable|string|max:100");
        rules.put("pagos.*.monto_recibido", "nullable|numeric|min:0");
        rules.put("pagos.*.notas", "nullable|string|max:255");
        Validator.make(data, rules).validate();
        SaleDocument d = documents.find(kind, tenant.getId(), Input.intval(id)).orElseThrow(ApiException::notFound);
        tx.executeWithoutResult(s -> payments.register(d, asList(data.get("pagos"))));
        SaleDocument fresh = documents.find(kind, tenant.getId(), d.id).orElseThrow(ApiException::notFound);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mensaje", "Pagos registrados correctamente");
        body.put("estado_pago", fresh.paymentStatus);
        body.put("monto_pagado", SaleDocumentResource.f(fresh.montoPagado));
        body.put("pagos", documents.payments(fresh).stream().map(SaleDocumentResource::payment).toList());
        return ResponseEntity.ok(body);
    }

    public ResponseEntity<Object> paymentIndex(DocumentKind kind, HttpServletRequest http, String id) {
        return paymentIndex(kind, ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> paymentIndex(DocumentKind kind, Tenant tenant, String id) {
        SaleDocument d = documents.find(kind, tenant.getId(), Input.intval(id)).orElseThrow(ApiException::notFound);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datos", documents.payments(d).stream().map(SaleDocumentResource::payment).toList());
        body.put("estado_pago", d.paymentStatus);
        body.put("monto_pagado", SaleDocumentResource.f(d.montoPagado));
        body.put("total_documento", SaleDocumentResource.f(d.mtoImpVenta));
        return ResponseEntity.ok(body);
    }

    public ResponseEntity<Object> paymentDestroy(DocumentKind kind, HttpServletRequest http, String id, String paymentId) {
        Tenant tenant = ApiContext.requireTenant(http);
        SaleDocument d = documents.find(kind, tenant.getId(), Input.intval(id)).orElseThrow(ApiException::notFound);
        tx.executeWithoutResult(s -> {
            if (documents.deletePayment(d, Input.intval(paymentId)) == 0) {
                throw ApiException.notFound();
            }
            payments.recalculate(d);
        });
        SaleDocument fresh = documents.find(kind, tenant.getId(), d.id).orElseThrow(ApiException::notFound);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mensaje", "Pago eliminado correctamente");
        body.put("estado_pago", fresh.paymentStatus);
        body.put("monto_pagado", SaleDocumentResource.f(fresh.montoPagado));
        return ResponseEntity.ok(body);
    }

    // ---------------------------------------------------------------- apoyo

    private SaleDocument find(DocumentKind kind, HttpServletRequest http, long id) {
        return find(kind, ApiContext.requireTenant(http), id);
    }

    private SaleDocument find(DocumentKind kind, Tenant tenant, long id) {
        return documents.find(kind, tenant.getId(), id).orElseThrow(ApiException::notFound);
    }

    private SaleDocument reload(SaleDocument d) {
        return documents.find(d.kind, d.tenantId, d.id).orElseThrow(ApiException::notFound);
    }

    /** Resource con {@code items} y {@code payments} cargados. */
    private Map<String, Object> full(SaleDocument d, HttpServletRequest http) {
        documents.loadItems(d);
        if (d.kind.isNote()) {
            return SaleDocumentResource.toNoteArray(d, client(d), d.items, baseUrl());
        }
        return SaleDocumentResource.toArray(d, client(d), d.items, documents.payments(d), baseUrl());
    }

    private Client client(SaleDocument d) {
        return d.clientId == null ? null : clients.findById(d.clientId).orElse(null);
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    private static boolean booleanInput(Input input, String key, boolean def) {
        return input.all().containsKey(key) ? input.bool(key) : def;
    }

    /** Filtros de {@code AppliesDocumentFilters} (texto sin distinguir mayúsculas ni tildes, como MySQL). */
    static String filters(Input in, MapSqlParameterSource p) {
        List<String> w = new ArrayList<>();
        w.add("tenant_id = :tenant");
        w.add("deleted_at IS NULL");
        dateFilter(in, p, w, "fecha_desde", "fecha_emision", ">=");
        dateFilter(in, p, w, "fecha_hasta", "fecha_emision", "<=");
        dateFilter(in, p, w, "vencimiento_desde", "fecha_vencimiento", ">=");
        dateFilter(in, p, w, "vencimiento_hasta", "fecha_vencimiento", "<=");
        if (in.filled("sunat_status")) {
            p.addValue("sunat_status", trimmedList(in.get("sunat_status")));
            w.add("sunat_status IN (:sunat_status)");
        }
        if (in.filled("estado_pago")) {
            p.addValue("estado_pago", trimmedList(in.get("estado_pago")));
            w.add("payment_status IN (:estado_pago)");
        }
        if (in.filled("serie")) {
            String serie = Php.toStr(in.get("serie"));
            p.addValue("serie", serie);
            w.add(serie.contains("%") ? "f_unaccent_lower(serie) LIKE f_unaccent_lower(:serie)" : "serie = :serie");
        }
        if (in.filled("correlativo_desde")) {
            p.addValue("correlativo_desde", Php.toInt(in.get("correlativo_desde")));
            w.add("correlativo >= :correlativo_desde");
        }
        if (in.filled("correlativo_hasta")) {
            p.addValue("correlativo_hasta", Php.toInt(in.get("correlativo_hasta")));
            w.add("correlativo <= :correlativo_hasta");
        }
        if (in.filled("correlativo")) {
            p.addValue("correlativo", Php.toInt(in.get("correlativo")));
            w.add("correlativo = :correlativo");
        }
        if (in.filled("client_num_doc")) {
            p.addValue("client_num_doc", Php.toStr(in.get("client_num_doc")));
            w.add("client_num_doc = :client_num_doc");
        }
        if (in.filled("client_tipo_doc")) {
            p.addValue("client_tipo_doc", Php.toStr(in.get("client_tipo_doc")));
            w.add("client_tipo_doc = :client_tipo_doc");
        }
        if (in.filled("cliente")) {
            p.addValue("cliente", "%" + Php.toStr(in.get("cliente")) + "%");
            w.add("f_unaccent_lower(client_razon_social) LIKE f_unaccent_lower(:cliente)");
        }
        if (in.filled("tipo_moneda")) {
            p.addValue("tipo_moneda", Php.toStr(in.get("tipo_moneda")).toUpperCase(java.util.Locale.ROOT));
            w.add("tipo_moneda = :tipo_moneda");
        }
        if (in.filled("tipo_operacion")) {
            p.addValue("tipo_operacion", Php.toStr(in.get("tipo_operacion")));
            w.add("tipo_operacion = :tipo_operacion");
        }
        if (in.filled("forma_pago")) {
            p.addValue("forma_pago", Php.toStr(in.get("forma_pago")));
            w.add("forma_pago = :forma_pago");
        }
        if (in.filled("sucursal_id")) {
            p.addValue("sucursal_id", Php.toInt(in.get("sucursal_id")));
            w.add("sucursal_id = :sucursal_id");
        }
        if (in.filled("monto_min")) {
            p.addValue("monto_min", Php.toFloat(in.get("monto_min")));
            w.add("mto_imp_venta >= :monto_min");
        }
        if (in.filled("monto_max")) {
            p.addValue("monto_max", Php.toFloat(in.get("monto_max")));
            w.add("mto_imp_venta <= :monto_max");
        }
        Object search = in.get("search") != null ? in.get("search") : in.get("q");
        if (Php.truthy(search)) {
            String s = Php.toStr(search);
            p.addValue("term", "%" + s + "%");
            String or = "f_unaccent_lower(client_razon_social) LIKE f_unaccent_lower(:term) OR client_num_doc LIKE :term "
                    + "OR f_unaccent_lower(serie) LIKE f_unaccent_lower(:term)";
            if (Php.isNumericString(s)) {
                p.addValue("term_int", Php.toInt(s));
                or += " OR correlativo = :term_int";
            }
            w.add("(" + or + ")");
        }
        if (in.bool("con_cdr")) {
            w.add("cdr_path IS NOT NULL");
        }
        return String.join(" AND ", w);
    }

    /** {@code whereDate()}: compara la fecha como texto, sin errores con valores inválidos. */
    private static void dateFilter(Input in, MapSqlParameterSource p, List<String> w, String param, String column, String op) {
        if (in.filled(param)) {
            p.addValue(param, Php.toStr(in.get(param)));
            w.add("CAST(CAST(" + column + " AS date) AS text) " + op + " :" + param);
        }
    }

    private static List<String> trimmedList(Object v) {
        return Arrays.stream(Php.toStr(v).split(",", -1)).map(String::trim).toList();
    }

    static String order(Input in) {
        String col = Php.toStr(in.get("ordenar_por", "created_at"));
        String dir = Php.toStr(in.get("orden", "desc")).toLowerCase(java.util.Locale.ROOT);
        dir = dir.equals("asc") ? "asc" : "desc";
        if (!ORDER_COLUMNS.contains(col)) {
            col = "created_at";
        }
        String expr = col.equals("client_razon_social") ? "client_razon_social COLLATE es_ci" : col;
        // Desempate por id para un orden estable entre páginas
        return expr + " " + dir + ", id " + dir;
    }

    private ResponseEntity<Object> errorInterno(RuntimeException e, String mensaje) {
        String ref = UUID.randomUUID().toString();
        log.error("{} error_id={}", mensaje, ref, e);
        return ApiResponse.error(mensaje + " (ref: " + ref + ")", 500, map("error_id", ref));
    }

    private static List<Object> asList(Object v) {
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        if (v instanceof Map<?, ?> m) {
            return new ArrayList<>(m.values());
        }
        return new ArrayList<>();
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
