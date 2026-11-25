package pe.facturacion.api.modules.document;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import pe.facturacion.api.modules.document.SaleDocument.Payment;
import pe.facturacion.api.modules.document.SaleDocument.SaleItem;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.time.Clock;

/**
 * Acceso a {@code invoices}/{@code boletas}, sus ítems y {@code payments} con JDBC (las dos
 * tablas tienen la misma estructura). Los comprobantes con {@code deleted_at} quedan fuera,
 * como el {@code SoftDeletes} de API-PRO.
 */
@Repository
public class SaleDocumentRepository {

    /** Columnas JSON (jsonb). */
    private static final List<String> JSON_COLUMNS = List.of(
            "cuotas", "detraccion", "percepcion", "anticipos", "descuentos_globales", "guias", "extras",
            "leyendas", "contrato_colaboracion", "sunat_notes");

    private final NamedParameterJdbcTemplate jdbc;

    public SaleDocumentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ lectura

    public Optional<SaleDocument> find(DocumentKind kind, long tenantId, long id) {
        List<SaleDocument> list = jdbc.query(
                "SELECT * FROM " + kind.table + " WHERE id = :id AND tenant_id = :t AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("id", id).addValue("t", tenantId), (rs, n) -> map(kind, rs));
        return list.stream().findFirst();
    }

    /** Sin filtro de tenant: para el job de envío. */
    public Optional<SaleDocument> findById(DocumentKind kind, long id) {
        List<SaleDocument> list = jdbc.query("SELECT * FROM " + kind.table + " WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", id), (rs, n) -> map(kind, rs));
        return list.stream().findFirst();
    }

    /** {@code SELECT ... FOR UPDATE} del comprobante (serializa cambios de estado). */
    public Optional<SaleDocument> lock(DocumentKind kind, long id) {
        List<SaleDocument> list = jdbc.query("SELECT * FROM " + kind.table + " WHERE id = :id AND deleted_at IS NULL FOR UPDATE",
                Map.of("id", id), (rs, n) -> map(kind, rs));
        return list.stream().findFirst();
    }

    public List<SaleDocument> query(DocumentKind kind, String where, MapSqlParameterSource params, String orderBy, int limit, int offset) {
        String sql = "SELECT * FROM " + kind.table + " WHERE " + where + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset";
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql, params, (rs, n) -> map(kind, rs));
    }

    public long count(DocumentKind kind, String where, MapSqlParameterSource params) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + kind.table + " WHERE " + where, params, Long.class);
        return n == null ? 0 : n;
    }

    public void loadItems(SaleDocument doc) {
        doc.items = jdbc.query("SELECT * FROM " + doc.kind.itemsTable + " WHERE " + doc.kind.itemsFk + " = :id ORDER BY id",
                Map.of("id", doc.id), (rs, n) -> mapItem(rs));
    }

    public List<Payment> payments(SaleDocument doc) {
        return jdbc.query("SELECT * FROM payments WHERE payable_type = :type AND payable_id = :id ORDER BY id",
                new MapSqlParameterSource().addValue("type", doc.kind.payableType).addValue("id", doc.id), (rs, n) -> {
                    Payment p = new Payment();
                    p.id = rs.getLong("id");
                    p.metodo = rs.getString("metodo");
                    p.monto = rs.getBigDecimal("monto");
                    p.referencia = rs.getString("referencia");
                    p.montoRecibido = rs.getBigDecimal("monto_recibido");
                    p.notas = rs.getString("notas");
                    p.createdAt = ts(rs, "created_at");
                    return p;
                });
    }

    /** ¿Existe ya ese correlativo en la serie? (incluye eliminados: la restricción UNIQUE también). */
    public boolean existsCorrelativo(DocumentKind kind, long tenantId, String serie, long correlativo) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + kind.table + " WHERE tenant_id = :t AND serie = :s AND correlativo = :c",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("s", serie).addValue("c", correlativo), Long.class);
        return n != null && n > 0;
    }

    // ---------------------------------------------------------------- escritura

    public long insert(SaleDocument d) {
        LocalDateTime now = Clock.nowUtc().withNano(0);
        d.createdAt = now;
        d.updatedAt = now;
        Map<String, Object> cols = columns(d);
        cols.put("created_at", now);
        cols.put("updated_at", now);
        StringJoiner names = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");
        MapSqlParameterSource p = new MapSqlParameterSource();
        cols.forEach((k, v) -> {
            names.add(k);
            values.add(JSON_COLUMNS.contains(k) ? "CAST(:" + k + " AS jsonb)" : ":" + k);
            p.addValue(k, JSON_COLUMNS.contains(k) ? json(v) : v);
        });
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO " + d.kind.table + " (" + names + ") VALUES (" + values + ")", p, keys, new String[] {"id"});
        d.id = keys.getKey().longValue();
        return d.id;
    }

    /** Actualiza columnas sueltas (y {@code updated_at}), como {@code $model->update([...])}. */
    public void update(SaleDocument d, Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return;
        }
        LocalDateTime now = Clock.nowUtc().withNano(0);
        StringJoiner set = new StringJoiner(", ");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", d.id).addValue("updated_at", now);
        changes.forEach((k, v) -> {
            set.add(k + " = " + (JSON_COLUMNS.contains(k) ? "CAST(:" + k + " AS jsonb)" : ":" + k));
            p.addValue(k, JSON_COLUMNS.contains(k) ? json(v) : v);
        });
        set.add("updated_at = :updated_at");
        jdbc.update("UPDATE " + d.kind.table + " SET " + set + " WHERE id = :id", p);
        d.updatedAt = now;
    }

    /** Guarda todas las columnas editables del comprobante. */
    public void save(SaleDocument d) {
        Map<String, Object> cols = columns(d);
        cols.remove("tenant_id");
        update(d, cols);
    }

    public void insertItems(SaleDocument d, List<SaleItem> items) {
        LocalDateTime now = Clock.nowUtc().withNano(0);
        for (SaleItem i : items) {
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("doc", d.id).addValue("codigo", i.codigo).addValue("cps", i.codProductoSunat)
                    .addValue("descripcion", i.descripcion).addValue("unidad", i.unidad).addValue("cantidad", i.cantidad)
                    .addValue("vu", i.mtoValorUnitario).addValue("vv", i.mtoValorVenta).addValue("bi", i.mtoBaseIgv)
                    .addValue("pi", i.porcentajeIgv).addValue("igv", i.igv).addValue("tip", i.tipAfeIgv).addValue("isc", i.isc)
                    .addValue("icbper", i.icbper).addValue("ti", i.totalImpuestos).addValue("pu", i.mtoPrecioUnitario)
                    .addValue("descuento", i.descuento).addValue("descuentos", json(i.descuentos))
                    .addValue("tsi", i.tipSisIsc).addValue("pisc", i.porcentajeIsc).addValue("fi", i.factorIcbper)
                    .addValue("now", now);
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO " + d.kind.itemsTable + " (" + d.kind.itemsFk + ", codigo, cod_producto_sunat, descripcion, unidad, cantidad, "
                    + "mto_valor_unitario, mto_valor_venta, mto_base_igv, porcentaje_igv, igv, tip_afe_igv, isc, icbper, "
                    + "total_impuestos, mto_precio_unitario, descuento, descuentos, tip_sis_isc, porcentaje_isc, factor_icbper, created_at, updated_at) "
                    + "VALUES (:doc, :codigo, :cps, :descripcion, :unidad, :cantidad, :vu, :vv, :bi, :pi, :igv, :tip, :isc, :icbper, "
                    + ":ti, :pu, :descuento, CAST(:descuentos AS jsonb), :tsi, :pisc, :fi, :now, :now)", p, keys, new String[] {"id"});
            i.id = keys.getKey().longValue();
        }
    }

    public void deleteItems(SaleDocument d) {
        jdbc.update("DELETE FROM " + d.kind.itemsTable + " WHERE " + d.kind.itemsFk + " = :id", Map.of("id", d.id));
    }

    /** {@code $model->delete()} con SoftDeletes. */
    public void softDelete(SaleDocument d) {
        LocalDateTime now = Clock.nowUtc().withNano(0);
        jdbc.update("UPDATE " + d.kind.table + " SET deleted_at = :now, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("now", now).addValue("id", d.id));
    }

    public void insertPayment(SaleDocument d, Payment p) {
        LocalDateTime now = Clock.nowUtc().withNano(0);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO payments (tenant_id, payable_type, payable_id, metodo, monto, referencia, monto_recibido, notas, created_at, updated_at) "
                + "VALUES (:t, :type, :id, :metodo, :monto, :ref, :mr, :notas, :now, :now)",
                new MapSqlParameterSource().addValue("t", d.tenantId).addValue("type", d.kind.payableType).addValue("id", d.id)
                        .addValue("metodo", p.metodo).addValue("monto", p.monto).addValue("ref", p.referencia)
                        .addValue("mr", p.montoRecibido).addValue("notas", p.notas).addValue("now", now),
                keys, new String[] {"id"});
        p.id = keys.getKey().longValue();
        p.createdAt = now;
    }

    public int deletePayment(SaleDocument d, long paymentId) {
        return jdbc.update("DELETE FROM payments WHERE id = :pid AND payable_type = :type AND payable_id = :id",
                new MapSqlParameterSource().addValue("pid", paymentId).addValue("type", d.kind.payableType).addValue("id", d.id));
    }

    /** Catálogo 09: anulación de la operación, anulación por error en el RUC y devolución total. */
    public static final List<String> MOTIVOS_NC_TOTAL = List.of("01", "02", "06");

    /**
     * Corrección: una NC de anulación total aceptada deja el comprobante afectado sin deuda
     * ({@code payment_status = anulado}); API-PRO lo seguía cobrando. El estado SUNAT no cambia:
     * el comprobante sigue aceptado y la NC es la que lo anula.
     */
    public void anularCobroPorNotaCredito(SaleDocument nc) {
        if (nc.kind != DocumentKind.NOTA_CREDITO || !MOTIVOS_NC_TOTAL.contains(nc.codMotivo) || nc.docAfectadoCorrelativo == null
                || !nc.docAfectadoCorrelativo.trim().matches("\\d{1,9}")) {
            return;
        }
        DocumentKind afectado = DocumentKind.fromTipo(nc.docAfectadoTipo);
        if (afectado != DocumentKind.FACTURA && afectado != DocumentKind.BOLETA) {
            return;
        }
        jdbc.update("UPDATE " + afectado.table + " SET payment_status = 'anulado', updated_at = :now WHERE tenant_id = :t AND serie = :s "
                + "AND correlativo = :c AND deleted_at IS NULL", new MapSqlParameterSource("now", Clock.nowUtc().withNano(0))
                .addValue("t", nc.tenantId).addValue("s", nc.docAfectadoSerie).addValue("c", Integer.parseInt(nc.docAfectadoCorrelativo.trim())));
    }

    /**
     * La NC de anulación total fue dada de baja (RA aceptada): el comprobante vuelve a deberse,
     * salvo que otra NC de anulación total aceptada lo siga anulando.
     */
    public void restaurarCobroTrasBajaDeNc(long tenantId, String ncSerie, int ncCorrelativo) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId).addValue("s", ncSerie).addValue("c", ncCorrelativo)
                .addValue("m", MOTIVOS_NC_TOTAL).addValue("now", Clock.nowUtc().withNano(0));
        List<Map<String, Object>> nc = jdbc.queryForList("SELECT doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo FROM credit_notes "
                + "WHERE tenant_id = :t AND serie = :s AND correlativo = :c AND cod_motivo IN (:m) AND deleted_at IS NULL", p);
        if (nc.isEmpty()) {
            return;
        }
        DocumentKind afectado = DocumentKind.fromTipo(Php.toStr(nc.get(0).get("doc_afectado_tipo")));
        String corr = Php.toStr(nc.get(0).get("doc_afectado_correlativo")).trim();
        if ((afectado != DocumentKind.FACTURA && afectado != DocumentKind.BOLETA) || !corr.matches("\\d{1,9}")) {
            return;
        }
        p.addValue("tipo", afectado.tipoDocumento).addValue("as", nc.get(0).get("doc_afectado_serie")).addValue("ac", Integer.parseInt(corr));
        jdbc.update("UPDATE " + afectado.table + " SET updated_at = :now, payment_status = CASE WHEN monto_pagado <= 0 THEN 'pendiente' "
                + "WHEN monto_pagado < mto_imp_venta THEN 'parcial' ELSE 'pagado' END "
                + "WHERE tenant_id = :t AND serie = :as AND correlativo = :ac AND payment_status = 'anulado' AND NOT EXISTS ("
                + "SELECT 1 FROM credit_notes n WHERE n.tenant_id = :t AND n.doc_afectado_tipo = :tipo AND n.doc_afectado_serie = :as "
                + "AND ltrim(n.doc_afectado_correlativo, '0') = ltrim(CAST(:ac AS text), '0') AND n.cod_motivo IN (:m) "
                + "AND n.sunat_status = 'aceptado' AND n.deleted_at IS NULL AND NOT (n.serie = :s AND n.correlativo = :c))", p);
    }

    /** {@code SELECT ... FOR UPDATE} del comprobante hasta el fin de la transacción. */
    public void lockRow(SaleDocument d) {
        jdbc.queryForList("SELECT id FROM " + d.kind.table + " WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", d.id));
    }

    public BigDecimal sumPayments(SaleDocument d) {
        BigDecimal s = jdbc.queryForObject("SELECT COALESCE(sum(monto), 0) FROM payments WHERE payable_type = :type AND payable_id = :id",
                new MapSqlParameterSource().addValue("type", d.kind.payableType).addValue("id", d.id), BigDecimal.class);
        return s == null ? BigDecimal.ZERO : s;
    }

    // ---------------------------------------------------------------- mapeo

    private static Map<String, Object> columns(SaleDocument d) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tenant_id", d.tenantId);
        c.put("sucursal_id", d.sucursalId);
        c.put("client_id", d.clientId);
        c.put("serie", d.serie);
        c.put("correlativo", d.correlativo);
        c.put("cod_local", d.codLocal);
        c.put("fecha_emision", d.fechaEmision);
        c.put("fecha_vencimiento", d.fechaVencimiento);
        c.put("tipo_operacion", d.tipoOperacion);
        c.put("tipo_moneda", d.tipoMoneda);
        c.put("forma_pago", d.formaPago);
        c.put("client_tipo_doc", d.clientTipoDoc);
        c.put("client_num_doc", d.clientNumDoc);
        c.put("client_razon_social", d.clientRazonSocial);
        c.put("client_direccion", d.clientDireccion);
        c.put("mto_oper_gravadas", d.mtoOperGravadas);
        c.put("mto_oper_exoneradas", d.mtoOperExoneradas);
        c.put("mto_oper_inafectas", d.mtoOperInafectas);
        c.put("mto_oper_exportacion", d.mtoOperExportacion);
        c.put("mto_oper_gratuitas", d.mtoOperGratuitas);
        c.put("mto_igv", d.mtoIgv);
        c.put("mto_base_ivap", d.mtoBaseIvap);
        c.put("mto_ivap", d.mtoIvap);
        c.put("mto_isc", d.mtoIsc);
        c.put("mto_icbper", d.mtoIcbper);
        c.put("total_impuestos", d.totalImpuestos);
        c.put("valor_venta", d.valorVenta);
        c.put("sub_total", d.subTotal);
        c.put("mto_imp_venta", d.mtoImpVenta);
        c.put("total_anticipos", d.totalAnticipos);
        c.put("total_descuentos", d.totalDescuentos);
        c.put("leyenda", d.leyenda);
        c.put("observacion", d.observacion);
        c.put("modo_envio", d.modoEnvio);
        c.put("payment_status", d.paymentStatus);
        c.put("monto_pagado", d.montoPagado);
        c.put("cuotas", d.cuotas);
        c.put("detraccion", d.detraccion);
        c.put("percepcion", d.percepcion);
        c.put("anticipos", d.anticipos);
        c.put("descuentos_globales", d.descuentosGlobales);
        c.put("guias", d.guias);
        c.put("extras", d.extras);
        c.put("leyendas", d.leyendas);
        c.put("contrato_colaboracion", d.contratoColaboracion);
        c.put("xml_path", d.xmlPath);
        c.put("cdr_path", d.cdrPath);
        c.put("pdf_path", d.pdfPath);
        c.put("hash_cpe", d.hashCpe);
        c.put("sunat_status", d.sunatStatus);
        c.put("sunat_code", d.sunatCode);
        c.put("sunat_description", d.sunatDescription);
        c.put("sunat_notes", d.sunatNotes);
        c.put("ticket", d.ticket);
        c.put("sent_at", d.sentAt);
        if (d.kind.isNote()) {
            NOTE_ABSENT.forEach(c::remove);
            c.put("doc_afectado_tipo", d.docAfectadoTipo);
            c.put("doc_afectado_serie", d.docAfectadoSerie);
            c.put("doc_afectado_correlativo", d.docAfectadoCorrelativo);
            c.put("cod_motivo", d.codMotivo);
            c.put("des_motivo", d.desMotivo);
        }
        return c;
    }

    /** Columnas de factura/boleta que no existen en {@code credit_notes}/{@code debit_notes}. */
    private static final List<String> NOTE_ABSENT = List.of("fecha_vencimiento", "tipo_operacion", "forma_pago",
            "mto_oper_exportacion", "mto_base_ivap", "mto_ivap", "payment_status", "monto_pagado", "cuotas", "detraccion",
            "percepcion", "anticipos", "extras");

    private static SaleDocument map(DocumentKind kind, ResultSet rs) throws SQLException {
        SaleDocument d = new SaleDocument();
        d.kind = kind;
        d.id = rs.getLong("id");
        d.tenantId = rs.getLong("tenant_id");
        d.sucursalId = (Long) rs.getObject("sucursal_id", Long.class);
        d.clientId = (Long) rs.getObject("client_id", Long.class);
        d.serie = rs.getString("serie");
        d.correlativo = rs.getInt("correlativo");
        d.codLocal = rs.getString("cod_local");
        d.fechaEmision = ts(rs, "fecha_emision");
        java.sql.Date fv = has(rs, "fecha_vencimiento") ? rs.getDate("fecha_vencimiento") : null;
        d.fechaVencimiento = fv == null ? null : fv.toLocalDate();
        d.tipoOperacion = has(rs, "tipo_operacion") ? rs.getString("tipo_operacion") : null;
        d.tipoMoneda = rs.getString("tipo_moneda");
        d.formaPago = has(rs, "forma_pago") ? rs.getString("forma_pago") : null;
        d.clientTipoDoc = rs.getString("client_tipo_doc");
        d.clientNumDoc = rs.getString("client_num_doc");
        d.clientRazonSocial = rs.getString("client_razon_social");
        d.clientDireccion = rs.getString("client_direccion");
        d.mtoOperGravadas = rs.getBigDecimal("mto_oper_gravadas");
        d.mtoOperExoneradas = rs.getBigDecimal("mto_oper_exoneradas");
        d.mtoOperInafectas = rs.getBigDecimal("mto_oper_inafectas");
        d.mtoOperExportacion = has(rs, "mto_oper_exportacion") ? rs.getBigDecimal("mto_oper_exportacion") : java.math.BigDecimal.ZERO;
        d.mtoOperGratuitas = rs.getBigDecimal("mto_oper_gratuitas");
        d.mtoIgv = rs.getBigDecimal("mto_igv");
        d.mtoBaseIvap = has(rs, "mto_base_ivap") ? rs.getBigDecimal("mto_base_ivap") : java.math.BigDecimal.ZERO;
        d.mtoIvap = has(rs, "mto_ivap") ? rs.getBigDecimal("mto_ivap") : java.math.BigDecimal.ZERO;
        d.mtoIsc = rs.getBigDecimal("mto_isc");
        d.mtoIcbper = rs.getBigDecimal("mto_icbper");
        d.totalImpuestos = rs.getBigDecimal("total_impuestos");
        d.valorVenta = rs.getBigDecimal("valor_venta");
        d.subTotal = rs.getBigDecimal("sub_total");
        d.mtoImpVenta = rs.getBigDecimal("mto_imp_venta");
        d.totalAnticipos = rs.getBigDecimal("total_anticipos");
        d.totalDescuentos = rs.getBigDecimal("total_descuentos");
        d.leyenda = rs.getString("leyenda");
        d.observacion = rs.getString("observacion");
        d.modoEnvio = has(rs, "modo_envio") ? rs.getString("modo_envio") : "automatico";
        d.paymentStatus = has(rs, "payment_status") ? rs.getString("payment_status") : null;
        d.montoPagado = has(rs, "monto_pagado") ? rs.getBigDecimal("monto_pagado") : java.math.BigDecimal.ZERO;
        d.cuotas = has(rs, "cuotas") ? jsonValue(rs, "cuotas") : null;
        d.detraccion = has(rs, "detraccion") ? jsonValue(rs, "detraccion") : null;
        d.percepcion = has(rs, "percepcion") ? jsonValue(rs, "percepcion") : null;
        d.anticipos = has(rs, "anticipos") ? jsonValue(rs, "anticipos") : null;
        d.descuentosGlobales = jsonValue(rs, "descuentos_globales");
        d.guias = jsonValue(rs, "guias");
        d.extras = has(rs, "extras") ? jsonValue(rs, "extras") : null;
        d.leyendas = jsonValue(rs, "leyendas");
        d.contratoColaboracion = jsonValue(rs, "contrato_colaboracion");
        d.xmlPath = rs.getString("xml_path");
        d.cdrPath = rs.getString("cdr_path");
        d.pdfPath = rs.getString("pdf_path");
        d.hashCpe = rs.getString("hash_cpe");
        d.sunatStatus = rs.getString("sunat_status");
        d.sunatCode = rs.getString("sunat_code");
        d.sunatDescription = rs.getString("sunat_description");
        d.sunatNotes = jsonValue(rs, "sunat_notes");
        d.ticket = rs.getString("ticket");
        d.sentAt = ts(rs, "sent_at");
        d.createdAt = ts(rs, "created_at");
        d.updatedAt = ts(rs, "updated_at");
        if (kind.isNote()) {
            d.docAfectadoTipo = rs.getString("doc_afectado_tipo");
            d.docAfectadoSerie = rs.getString("doc_afectado_serie");
            d.docAfectadoCorrelativo = rs.getString("doc_afectado_correlativo");
            d.codMotivo = rs.getString("cod_motivo");
            d.desMotivo = rs.getString("des_motivo");
        }
        return d;
    }

    private static SaleItem mapItem(ResultSet rs) throws SQLException {
        SaleItem i = new SaleItem();
        i.id = rs.getLong("id");
        i.codigo = rs.getString("codigo");
        i.codProductoSunat = rs.getString("cod_producto_sunat");
        i.descripcion = rs.getString("descripcion");
        i.unidad = rs.getString("unidad");
        i.cantidad = rs.getBigDecimal("cantidad");
        i.mtoValorUnitario = rs.getBigDecimal("mto_valor_unitario");
        i.mtoValorVenta = rs.getBigDecimal("mto_valor_venta");
        i.mtoBaseIgv = rs.getBigDecimal("mto_base_igv");
        i.porcentajeIgv = rs.getBigDecimal("porcentaje_igv");
        i.igv = rs.getBigDecimal("igv");
        i.tipAfeIgv = rs.getString("tip_afe_igv");
        i.isc = rs.getBigDecimal("isc");
        i.icbper = rs.getBigDecimal("icbper");
        i.totalImpuestos = rs.getBigDecimal("total_impuestos");
        i.mtoPrecioUnitario = rs.getBigDecimal("mto_precio_unitario");
        i.descuento = rs.getBigDecimal("descuento");
        i.descuentos = jsonValue(rs, "descuentos");
        i.tipSisIsc = rs.getString("tip_sis_isc");
        i.porcentajeIsc = rs.getBigDecimal("porcentaje_isc");
        i.factorIcbper = rs.getBigDecimal("factor_icbper");
        return i;
    }

    /** ¿La consulta trae esa columna? (las notas no tienen las columnas de pago y operación). */
    private static boolean has(ResultSet rs, String col) throws SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (md.getColumnName(i).equalsIgnoreCase(col)) {
                return true;
            }
        }
        return false;
    }

    private static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private static Object jsonValue(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? null : PhpJson.decode(s);
    }

    static String json(Object v) {
        return v == null ? null : PhpJson.encode(v);
    }

    /**
     * Valor numérico de PHP tal como lo guarda PDO en una columna decimal: el float pasa por
     * {@code (string)} (precisión 14) y la BD redondea a la escala de la columna.
     */
    public static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return new BigDecimal(PhpMath.toStr(bd.doubleValue()));
        }
        if (Php.isInt(v)) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof String s) {
            return Php.isNumericString(s) ? new BigDecimal(s.trim()) : BigDecimal.ZERO;
        }
        return new BigDecimal(PhpMath.toStr(Php.toFloat(v)));
    }

    /** Escala de lectura de un cast {@code decimal:N}: cadena con N decimales. */
    public static String cast(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    public static LocalDate toDate(LocalDateTime dt) {
        return dt == null ? null : dt.toLocalDate();
    }

    public static List<Object> listOrNull(Object v) {
        return v instanceof List<?> l ? new ArrayList<>(l) : null;
    }
}
