package pe.facturacion.api.modules.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.catalog.SunatRules;
import pe.facturacion.api.modules.serie.Serie;
import pe.facturacion.api.modules.serie.SerieRepository;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.validation.RuleSets;
import pe.facturacion.api.shared.validation.Validator;

/**
 * {@code StoreInvoiceRequest} / {@code StoreBoletaRequest}: reglas exportadas de API-PRO más los
 * chequeos del {@code withValidator()} (serie registrada, correlativo manual, código de producto
 * obligatorio, tipo de operación 0112, NRUS, RUC en factura interna, boleta > S/ 700...).
 */
@Component
public class SaleDocumentRequest {

    private final SunatRules sunatRules;
    private final SerieRepository series;
    private final SaleDocumentRepository documents;
    private final DocumentCalculationService calculator;

    /**
     * Solo para las pruebas de paridad con API-PRO (sus escenarios traen montos que esta API
     * corrige: detracción 37.76 por 40.00, ICBPER 0.20 y una NC en USD sobre una factura en PEN). Nunca se activa fuera de las pruebas.
     */
    @org.springframework.beans.factory.annotation.Value("${app.paridad-api-pro:false}")
    boolean paridadApiPro;

    public SaleDocumentRequest(SunatRules sunatRules, SerieRepository series, SaleDocumentRepository documents,
            DocumentCalculationService calculator) {
        this.sunatRules = sunatRules;
        this.series = series;
        this.documents = documents;
        this.calculator = calculator;
    }

    private static String ruleSet(DocumentKind kind) {
        return switch (kind) {
            case FACTURA -> "store_invoice";
            case BOLETA -> "store_boleta";
            case NOTA_CREDITO -> "store_credit_note";
            case NOTA_DEBITO -> "store_debit_note";
        };
    }

    /** Etiqueta en plural para los mensajes de serie ("facturas", "notas de crédito"...). */
    static String plural(DocumentKind kind) {
        return switch (kind) {
            case FACTURA -> "facturas";
            case BOLETA -> "boletas";
            case NOTA_CREDITO -> "notas de crédito";
            case NOTA_DEBITO -> "notas de débito";
        };
    }

    /** Valida y devuelve {@code $request->validated()}; lanza ValidationException (422). */
    public Map<String, Object> validate(DocumentKind kind, Tenant tenant, Map<String, Object> input) {
        return validator(kind, tenant, input).validate();
    }

    public Validator validator(DocumentKind kind, Tenant tenant, Map<String, Object> input) {
        RuleSets.RuleSet set = RuleSets.load(ruleSet(kind), sunatRules::resolve);
        Map<String, Object> rules = set.rulesCopy();
        // Corrección (DIFERENCIAS): el porcentaje del descuento global se usa en escala 0-100
        // (API-PRO validaba max:1 y luego dividía entre 100)
        if (rules.containsKey("descuentos_globales.*.porcentaje")) {
            rules.put("descuentos_globales.*.porcentaje", List.of("nullable", "numeric", "min:0", "max:100"));
        }
        Validator v = Validator.make(input, rules).messages(set.messages());
        v.after(val -> {
            validarSerieRegistrada(val, tenant, kind);
            validarCorrelativoManual(val, tenant, kind);
            validarCodProductoObligatorio(val, tenant);
            if (!paridadApiPro) {
                validarIcbper(val);
            }
            switch (kind) {
                case FACTURA -> {
                    validarTipoOperacion0112(val);
                    afterFactura(val, tenant);
                }
                case BOLETA -> {
                    validarTipoOperacion0112(val);
                    afterBoleta(val);
                }
                case NOTA_CREDITO -> {
                    afterNotaCredito(val, tenant);
                    validarMonedaDocumentoModificado(val, tenant);
                }
                case NOTA_DEBITO -> {
                    afterNotaDebito(val, tenant);
                    validarMonedaDocumentoModificado(val, tenant);
                }
            }
        });
        return v;
    }

    private void validarSerieRegistrada(Validator v, Tenant tenant, DocumentKind kind) {
        Object serie = v.input("serie");
        if (tenant == null || !(serie instanceof String s) || s.isEmpty()) {
            return;
        }
        String etiqueta = plural(kind);
        var found = series.findByTenantIdAndTipoDocumentoAndSerie(tenant.getId(), kind.tipoDocumento, s);
        if (found.isPresent() && found.get().isActive()) {
            return;
        }
        if (found.isPresent()) {
            v.addError("serie", "La serie " + s + " está desactivada. Actívala en el módulo Series para poder emitir " + etiqueta + ".");
            return;
        }
        boolean tieneAlguna = !series.list(tenant.getId(), false, null, kind.tipoDocumento).isEmpty();
        v.addError("serie", tieneAlguna
                ? "No tienes registrada la serie " + s + " para " + etiqueta + ". Revisa el número de serie o regístrala en el módulo Series."
                : "Aún no tienes ninguna serie registrada para " + etiqueta + ". Crea una serie de " + etiqueta + " en el módulo Series antes de emitir.");
    }

    private void validarCorrelativoManual(Validator v, Tenant tenant, DocumentKind kind) {
        Object correlativo = v.input("correlativo");
        Object serie = v.input("serie");
        if (correlativo == null || "".equals(correlativo) || tenant == null || !Php.truthy(serie)) {
            return;
        }
        if (documents.existsCorrelativo(kind, tenant.getId(), Php.toStr(serie), Php.toInt(correlativo))) {
            v.addError("correlativo", "El correlativo " + Php.toStr(correlativo) + " ya existe para la serie " + Php.toStr(serie)
                    + ". Use otro número o deje el campo vacío para autoincremento.");
        }
    }

    private static void validarCodProductoObligatorio(Validator v, Tenant tenant) {
        if (tenant == null || !tenant.isObligadoCodigoProducto()) {
            return;
        }
        int i = 0;
        for (Object item : asList(v.input("items"))) {
            Object cps = item instanceof Map<?, ?> m ? m.get("cod_producto_sunat") : null;
            if (Php.empty(cps)) {
                v.addError("items." + i + ".cod_producto_sunat",
                        "Su RUC está obligado por SUNAT (padrón \"Obligado a enviar código de producto\") a consignar el Código de producto SUNAT en cada ítem.");
            }
            i++;
        }
    }

    private static void validarTipoOperacion0112(Validator v) {
        if (!Php.strictEq(v.input("tipo_operacion"), "0112")) {
            return;
        }
        boolean ok = false;
        for (Object item : asList(v.input("items"))) {
            Object cps = item instanceof Map<?, ?> m ? m.get("cod_producto_sunat") : null;
            // array_intersect compara como cadenas
            if (cps != null && (Php.toStr(cps).equals("84121901") || Php.toStr(cps).equals("80131501"))) {
                ok = true;
            }
        }
        if (!ok) {
            v.addError("items", "El tipo de operación 0112 (gastos deducibles persona natural) requiere al menos un ítem con Código de producto SUNAT "
                    + "'84121901' (crédito hipotecario) o '80131501' (arrendamiento de inmuebles).");
        }
    }

    /**
     * Corrección: ICBPER por bolsa según el año de emisión (Ley 30884: S/ 0.10 en 2019, +0.10 por
     * año, S/ 0.50 desde 2023). API-PRO usaba el factor del cliente (la colección manda 0.20, la
     * tasa de 2020): beta lo acepta, producción no.
     */
    static double tasaIcbper(int anio) {
        return anio <= 2019 ? 0.10 : anio >= 2023 ? 0.50 : Math.round((0.10 + (anio - 2019) * 0.10) * 100) / 100.0;
    }

    private static void validarIcbper(Validator v) {
        String fecha = v.input("fecha_emision") == null ? "" : Php.toStr(v.input("fecha_emision"));
        if (!fecha.matches("^\\d{4}-.*")) {
            return;
        }
        double tasa = tasaIcbper(Integer.parseInt(fecha.substring(0, 4)));
        List<Object> items = asList(v.input("items"));
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Map<?, ?> m)) {
                continue;
            }
            Object factor = m.get("factor_icbper");
            if (!Php.empty(factor) && Math.abs(Php.toFloat(factor) - tasa) > 0.0001) {
                v.addError("items." + i + ".factor_icbper", "El ICBPER vigente para " + fecha.substring(0, 4) + " es S/ "
                        + String.format(java.util.Locale.ROOT, "%.2f", tasa) + " por bolsa.");
                continue;
            }
            Object icbper = m.get("icbper");
            if (!Php.empty(icbper)) {
                double esperado = Math.round(tasa * Php.toFloat(m.get("cantidad")) * 100) / 100.0;
                if (Math.abs(Php.toFloat(icbper) - esperado) > 0.001) {
                    v.addError("items." + i + ".icbper", "El ICBPER del ítem debe ser " + String.format(java.util.Locale.ROOT, "%.2f", esperado)
                            + " (S/ " + String.format(java.util.Locale.ROOT, "%.2f", tasa) + " por bolsa × cantidad).");
                }
            }
        }
    }

    private static void afterFactura(Validator v, Tenant tenant) {
        if (tenant != null && TaxRateService.REGIMEN_RESTRINGIDO.equals(tenant.getTaxRegime())) {
            v.addError("tax_regime", "Los contribuyentes del régimen NRUS solo pueden emitir boletas de venta, no facturas.");
        }
        if (Php.strictEq(v.input("forma_pago"), "Credito") && Php.empty(v.input("cuotas"))) {
            v.addError("cuotas", "Las cuotas son obligatorias cuando la forma de pago es Crédito.");
        }
        Object tipoDoc = v.input("cliente.tipo_doc");
        String numDoc = v.input("cliente.num_doc") == null ? "" : Php.toStr(v.input("cliente.num_doc"));
        Object tipoOp = v.input("tipo_operacion");
        String tipoOperacion = tipoOp == null ? "0101" : Php.toStr(tipoOp);
        if (tipoOperacion.startsWith("02")) {
            if (Php.strictEq(tipoDoc, "6")) {
                v.addError("cliente.tipo_doc",
                        "En exportación el receptor es no domiciliado: use tipo_doc 0 (Doc. trib. no dom. sin RUC), 4, 7 o A, no RUC (6).");
            }
            return;
        }
        if (tipoDoc != null && !Php.strictEq(tipoDoc, "6")) {
            v.addError("cliente.tipo_doc",
                    "Las facturas requieren cliente con RUC (tipo_doc=6). Para clientes con DNI u otros documentos use POST /boletas.");
            return;
        }
        if (Php.strictEq(tipoDoc, "6") && numDoc.length() != 11) {
            v.addError("cliente.num_doc", "El RUC debe tener exactamente 11 dígitos.");
        }
        if (Php.strictEq(tipoDoc, "6") && numDoc.length() == 11 && !numDoc.matches("^(10|15|17|20).*")) {
            v.addError("cliente.num_doc", "El RUC no tiene un prefijo válido (10, 15, 17, 20).");
        }
    }

    private static void afterBoleta(Validator v) {
        double total = 0;
        for (Object item : asList(v.input("items"))) {
            if (item instanceof Map<?, ?> m) {
                total += Php.toFloat(m.get("cantidad")) * Php.toFloat(m.get("precio_unitario"));
            }
        }
        Object tipoDocRaw = v.input("cliente.tipo_doc");
        Object tipoDoc = tipoDocRaw == null ? "0" : tipoDocRaw;
        if (total > 700 && Php.strictEq(tipoDoc, "0")) {
            v.addError("cliente.tipo_doc",
                    "Para boletas mayores a S/ 700.00 es obligatorio consignar el documento de identidad del cliente (DNI, RUC, CE, etc.).");
        }
        String numDoc = v.input("cliente.num_doc") == null ? "" : Php.toStr(v.input("cliente.num_doc"));
        if (Php.strictEq(tipoDoc, "1") && numDoc.length() != 8) {
            v.addError("cliente.num_doc", "El DNI debe tener exactamente 8 dígitos.");
        }
        if (Php.strictEq(tipoDoc, "6") && numDoc.length() != 11) {
            v.addError("cliente.num_doc", "El RUC debe tener exactamente 11 dígitos.");
        }
        if (Php.strictEq(v.input("forma_pago"), "Credito") && Php.empty(v.input("cuotas"))) {
            v.addError("cuotas", "Las cuotas son obligatorias cuando la forma de pago es Crédito.");
        }
    }

    /**
     * Corrección: la nota va en la moneda del comprobante que modifica. API-PRO no lo revisaba y,
     * con moneda distinta, además se saltaba la validación del importe (una NC en USD sobre una
     * factura en PEN pasaba). Solo si el comprobante es de esta empresa (los ajenos o migrados no se conocen).
     */
    private void validarMonedaDocumentoModificado(Validator v, Tenant tenant) {
        if (paridadApiPro) {
            return;
        }
        Object tipo = v.input("doc_afectado_tipo");
        String correlativo = v.input("doc_afectado_correlativo") == null ? "" : Php.toStr(v.input("doc_afectado_correlativo")).trim();
        if (tenant == null || !(Php.strictEq(tipo, "01") || Php.strictEq(tipo, "03")) || !correlativo.matches("\\d{1,9}")) {
            return;
        }
        DocumentKind docKind = Php.strictEq(tipo, "01") ? DocumentKind.FACTURA : DocumentKind.BOLETA;
        List<SaleDocument> found = documents.query(docKind, "tenant_id = :t AND serie = :s AND correlativo = :c AND deleted_at IS NULL",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("t", tenant.getId())
                        .addValue("s", Php.toStr(v.input("doc_afectado_serie"))).addValue("c", Integer.parseInt(correlativo)),
                "id", 1, 0);
        String moneda = v.input("tipo_moneda") == null ? "PEN" : Php.toStr(v.input("tipo_moneda"));
        if (!found.isEmpty() && found.get(0).tipoMoneda != null && !found.get(0).tipoMoneda.equals(moneda)) {
            v.addError("tipo_moneda", "La nota debe emitirse en la moneda del comprobante que modifica ("
                    + found.get(0).tipoMoneda + "), no en " + moneda + ".");
        }
    }

    /** {@code StoreCreditNoteRequest::withValidator()} (sin la serie/correlativo/código, ya validados arriba). */
    @SuppressWarnings("unchecked")
    private void afterNotaCredito(Validator v, Tenant tenant) {
        validarMontoVsDocumentoModificado(v, tenant);
        if (tenant != null && TaxRateService.REGIMEN_RESTRINGIDO.equals(tenant.getTaxRegime())
                && !Php.strictEq(v.input("doc_afectado_tipo"), "03")) {
            v.addError("doc_afectado_tipo",
                    "Los contribuyentes del régimen NRUS solo pueden emitir notas de crédito contra boletas de venta (tipo 03).");
        }
    }

    private static void afterNotaDebito(Validator v, Tenant tenant) {
        // ERR-3507: la ND por penalidades (13) solo admite operaciones inafectas de IGV/IVAP
        if (Php.strictEq(v.input("cod_motivo"), "13")) {
            double igvTotal = Php.toFloat(v.input("mto_igv")) + Php.toFloat(v.input("mto_ivap"));
            for (Object item : asList(v.input("items"))) {
                if (item instanceof Map<?, ?> m) {
                    igvTotal += Php.toFloat(m.get("igv"));
                }
            }
            if (igvTotal > 0) {
                v.addError("cod_motivo", "La nota de débito por Penalidades (motivo 13) solo admite operaciones inafectas del IGV/IVAP "
                        + "(ERR-3507): los importes de IGV/IVAP deben ser 0.");
            }
        }
        if (tenant != null && TaxRateService.REGIMEN_RESTRINGIDO.equals(tenant.getTaxRegime())
                && !Php.strictEq(v.input("doc_afectado_tipo"), "03")) {
            v.addError("doc_afectado_tipo",
                    "Los contribuyentes del régimen NRUS solo pueden emitir notas de débito contra boletas de venta (tipo 03).");
        }
    }

    /**
     * {@code ValidatesNotaCreditoMonto} (ERR-3286 / ERR-3503): el total de la NC no puede superar el
     * del documento modificado (boleta: +1 de tolerancia), salvo motivo 10, otra moneda o documento
     * que no se emitió por esta API.
     */
    @SuppressWarnings("unchecked")
    private void validarMontoVsDocumentoModificado(Validator v, Tenant tenant) {
        if (!v.errors().isEmpty()) {
            return;
        }
        Object tipo = v.input("doc_afectado_tipo");
        if (tenant == null || Php.strictEq(v.input("cod_motivo"), "10") || !(Php.strictEq(tipo, "01") || Php.strictEq(tipo, "03"))) {
            return;
        }
        DocumentKind docKind = Php.strictEq(tipo, "01") ? DocumentKind.FACTURA : DocumentKind.BOLETA;
        List<SaleDocument> found = documents.query(docKind, "tenant_id = :t AND serie = :s AND correlativo = :c AND deleted_at IS NULL",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("t", tenant.getId())
                        .addValue("s", Php.toStr(v.input("doc_afectado_serie")))
                        .addValue("c", Php.toInt(v.input("doc_afectado_correlativo"))),
                "id", 1, 0);
        if (found.isEmpty()) {
            return;
        }
        SaleDocument doc = found.get(0);
        Object moneda = v.input("tipo_moneda") == null ? "PEN" : v.input("tipo_moneda");
        if (!Php.strictEq(moneda, doc.tipoMoneda)) {
            return;
        }
        double ncTotal;
        try {
            Map<String, Object> data = new java.util.LinkedHashMap<>(v.data());
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (Object o : asList(data.get("items"))) {
                items.add((Map<String, Object>) o);
            }
            List<Map<String, Object>> calc = calculator.calculateItems(items, tenant);
            ncTotal = pe.facturacion.api.shared.php.PhpMath.round(Php.toFloat(calculator.calculateTotals(calc, data, tenant).get("mto_imp_venta")), 2);
        } catch (RuntimeException e) {
            return;
        }
        double docTotal = pe.facturacion.api.shared.php.PhpMath.round(doc.mtoImpVenta.doubleValue(), 2);
        double tolerancia = docKind == DocumentKind.BOLETA ? 1.0 : 0.0;
        if (ncTotal > docTotal + tolerancia) {
            v.addError("doc_afectado_correlativo", "El importe total de la nota de crédito (" + pe.facturacion.api.shared.php.PhpMath.toStr(ncTotal)
                    + ") no puede superar el del documento que modifica " + Php.toStr(v.input("doc_afectado_serie")) + "-"
                    + Php.toStr(v.input("doc_afectado_correlativo")) + " (" + pe.facturacion.api.shared.php.PhpMath.toStr(docTotal)
                    + ") (ERR-3286/3503).");
        }
    }

    private static List<Object> asList(Object v) {
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        if (v instanceof Map<?, ?> m) {
            return new ArrayList<>(m.values());
        }
        return List.of();
    }

    /** Serie activa del tipo (para el Action). */
    public Serie serie(Tenant tenant, DocumentKind kind, String serie) {
        return series.findByTenantIdAndTipoDocumentoAndSerie(tenant.getId(), kind.tipoDocumento, serie)
                .filter(Serie::isActive).orElse(null);
    }
}
