package pe.facturacion.api.modules.guide;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.catalog.SunatRules;
import pe.facturacion.api.modules.serie.SerieRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.validation.Validator;
import pe.facturacion.api.sunat.builder.PhpDates;

/**
 * {@code StoreDispatchGuideRequest}: normalización previa ({@code prepareForValidation}), reglas y
 * validaciones de negocio de GRE remitente (09) y transportista (31).
 *
 * <p>Corrección: API-PRO declaraba dos veces {@code vehiculo.nro_circulacion}, {@code vehiculo.tuc} y
 * {@code vehiculo.secundarios.*.nro_circulacion}; la segunda (solo {@code max:15}) anulaba el
 * formato alfanumérico de 10–15 caracteres. Aquí se aplican ambas.
 */
@Component
public class GuideRequest {

    static final String ALFANUM = "regex:/^[A-Z0-9]{10,15}$/";
    static final String PLACA = "regex:/^[A-Z0-9]{6,8}$/";

    private final SerieRepository series;
    private final GuideRepository guides;

    public GuideRequest(SerieRepository series, GuideRepository guides) {
        this.series = series;
        this.guides = guides;
    }

    /** {@code prepareForValidation()} sobre una copia de la entrada. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> prepare(Map<String, Object> input, boolean transportista) {
        Map<String, Object> data = new LinkedHashMap<>(input);
        if (transportista) {
            data.put("tipo_documento", "31");
        }
        if (data.get("doc_relacionado") instanceof Map<?, ?> doc && doc.get("tipo_codigo") != null) {
            data.put("doc_relacionado", new ArrayList<>(List.of(doc)));
        }
        if (data.get("conductor") instanceof List<?> conductor) {
            if (Php.empty(data.get("conductores"))) {
                data.put("conductores", conductor);
            }
            data.remove("conductor");
        }
        for (String alias : List.of("fecha_de_entrega_al_transportista", "fecha_entrega_al_transportista")) {
            if (filled(data.get(alias)) && !filled(data.get("fecha_entrega_transportista"))) {
                data.put("fecha_entrega_transportista", data.get(alias));
            }
        }
        return data;
    }

    /** {@code $request->filled()}: presente y no vacío (cadenas en blanco cuentan como vacías). */
    static boolean filled(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.trim().isEmpty();
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    static Map<String, Object> rules() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tipo_documento", "sometimes|string|in:09,31");
        r.put("serie", "required|string|size:4");
        r.put("correlativo", "nullable|integer|min:1");
        r.put("fecha_emision", "required|date");
        r.put("observacion", "nullable|string|max:500");
        r.put("destinatario.tipo_doc", List.of("required", "string", SunatRules.catalog("06")));
        r.put("destinatario.num_doc", "required|string|max:15");
        r.put("destinatario.razon_social", "required|string|max:255");
        r.put("tercero", "nullable|array");
        r.put("tercero.tipo_doc", List.of("required_with:tercero", "string", SunatRules.catalog("06")));
        r.put("tercero.num_doc", "required_with:tercero|string|max:15");
        r.put("tercero.razon_social", "required_with:tercero|string|max:255");
        r.put("comprador", "nullable|array");
        r.put("comprador.tipo_doc", List.of("required_with:comprador", "string", SunatRules.catalog("06")));
        r.put("comprador.num_doc", "required_with:comprador|string|max:15");
        r.put("comprador.razon_social", "required_with:comprador|string|max:255");
        r.put("cod_traslado", "required_unless:tipo_documento,31|string|in:01,02,03,04,05,06,07,08,09,13,14,17,18,19");
        r.put("mod_traslado", "required_unless:tipo_documento,31|string|in:01,02");
        r.put("fecha_traslado", "required|date");
        r.put("fecha_entrega_transportista", "nullable|date");
        r.put("peso_total", "required|numeric|gt:0");
        r.put("und_peso_total", "nullable|string|max:3");
        r.put("num_bultos", "nullable|integer|min:1");
        r.put("indicadores", "nullable|array");
        r.put("indicadores.*", "string");
        r.put("llegada_ubigeo", "required|string|size:6");
        r.put("llegada_direccion", "required|string|max:500");
        r.put("llegada_ruc", "nullable|string|size:11");
        r.put("llegada_cod_local", "nullable|string|max:5");
        r.put("partida_ubigeo", "required|string|size:6");
        r.put("partida_direccion", "required|string|max:500");
        r.put("partida_ruc", "nullable|string|size:11");
        r.put("partida_cod_local", "nullable|string|max:5");
        r.put("transportista", "nullable|array");
        r.put("transportista.tipo_doc", "required_with:transportista|string");
        r.put("transportista.num_doc", "required_with:transportista|string|max:11");
        r.put("transportista.razon_social", "required_with:transportista|string|max:255");
        r.put("transportista.nro_mtc", "nullable|string|max:20");
        r.put("vehiculo", "nullable|array");
        r.put("vehiculo.placa", List.of("required_with:vehiculo", "string", PLACA));
        r.put("vehiculo.nro_circulacion", List.of("nullable", "string", ALFANUM, "max:15"));
        r.put("vehiculo.tuc", List.of("nullable", "string", ALFANUM, "max:15"));
        r.put("vehiculo.secundarios", "nullable|array");
        r.put("vehiculo.secundarios.*.placa", List.of("required", "string", PLACA));
        r.put("vehiculo.secundarios.*.nro_circulacion", List.of("nullable", "string", ALFANUM, "max:15"));
        r.put("vehiculo.secundarios.*.tuc", List.of("nullable", "string", ALFANUM));
        r.put("conductor", "nullable|array");
        r.put("conductor.tipo_doc", "required_with:conductor|string");
        r.put("conductor.num_doc", "required_with:conductor|string|max:15");
        r.put("conductor.tipo", "nullable|string|in:Principal,Secundario");
        r.put("conductor.nombres", "required_with:conductor|string|max:100");
        r.put("conductor.apellidos", "required_with:conductor|string|max:100");
        r.put("conductor.licencia", "required_with:conductor|string|max:20");
        r.put("conductores", "nullable|array");
        r.put("conductores.*.tipo_doc", "required|string");
        r.put("conductores.*.num_doc", "required|string|max:15");
        r.put("conductores.*.tipo", "nullable|string|in:Principal,Secundario");
        r.put("conductores.*.nombres", "required|string|max:100");
        r.put("conductores.*.apellidos", "required|string|max:100");
        r.put("conductores.*.licencia", "required|string|max:20");
        r.put("doc_relacionado", "nullable|array|max:5");
        r.put("doc_relacionado.*.tipo_codigo",
                "required_with:doc_relacionado|string|in:01,03,04,09,12,31,48,50,52,65,66,67,68,69,71,72,73,74,75,76,77,78,80,82,91");
        r.put("doc_relacionado.*.numero", "required_with:doc_relacionado|string|max:100");
        r.put("doc_relacionado.*.tipo_descripcion", "nullable|string|max:120");
        r.put("doc_relacionado.*.ruc_emisor", "nullable|string|size:11");
        r.put("remitente", "nullable|array");
        r.put("remitente.tipo_doc", "required_with:remitente|string|in:1,6");
        r.put("remitente.num_doc", "required_with:remitente|string|max:15");
        r.put("remitente.razon_social", "required_with:remitente|string|max:250");
        r.put("datos_subcontratador", "nullable|array");
        r.put("datos_subcontratador.num_doc", "required_with:datos_subcontratador|string|size:11");
        r.put("datos_subcontratador.razon_social", "required_with:datos_subcontratador|string|max:250");
        r.put("datos_pagador_flete", "nullable|array");
        r.put("datos_pagador_flete.tipo", "required_with:datos_pagador_flete|string|in:remitente,subcontratador,tercero");
        r.put("datos_pagador_flete.tipo_doc", "nullable|string|in:1,6");
        r.put("datos_pagador_flete.num_doc", "nullable|string|max:15");
        r.put("datos_pagador_flete.razon_social", "nullable|string|max:250");
        r.put("autorizacion_especial", "nullable|array");
        r.put("autorizacion_especial.cod_emisor", "nullable|string|size:2");
        r.put("autorizacion_especial.nro_autorizacion", "nullable|string|max:50");
        r.put("vehiculo.cod_emisor", "nullable|string|size:2");
        r.put("vehiculo.nro_autorizacion", "nullable|string|max:50");
        r.put("vehiculo.secundarios.*.cod_emisor", "nullable|string|size:2");
        r.put("vehiculo.secundarios.*.nro_autorizacion", "nullable|string|max:50");
        r.put("items", "required|array|min:1");
        r.put("items.*.descripcion", "required|string|max:500");
        r.put("items.*.cantidad", "required|numeric|gt:0");
        r.put("items.*.unidad", "nullable|string|max:5");
        r.put("items.*.codigo", "nullable|string|max:50");
        r.put("items.*.cod_prod_sunat", "nullable|string|max:20");
        return r;
    }

    /** Valida la entrada ya normalizada y devuelve {@code validated()}. */
    public Map<String, Object> validate(Tenant tenant, Map<String, Object> data) {
        Validator v = Validator.make(data, rules());
        v.after(val -> after(val, tenant));
        return v.validate();
    }

    private void after(Validator v, Tenant tenant) {
        String tipoGuia = Php.toStr(v.input("tipo_documento") == null ? "09" : v.input("tipo_documento"));
        validarSerieRegistrada(v, tenant, tipoGuia, "31".equals(tipoGuia) ? "guías de remisión transportista" : "guías de remisión");
        validarCorrelativoManual(v, tenant);

        Object indicadores = v.input("indicadores");
        boolean esM1L = false;
        for (Object i : asList(indicadores)) {
            if (i instanceof String s && s.contains("M1L")) {
                esM1L = true;
            }
        }
        Object modTraslado = v.input("mod_traslado");
        boolean esGrt = Php.strictEq(v.input("tipo_documento") == null ? "09" : v.input("tipo_documento"), "31");

        if (esGrt) {
            if (Php.empty(v.input("remitente"))) {
                v.addError("remitente", "La Guía de Remisión Transportista requiere los datos del remitente (quien envía la carga).");
            }
            if (tenant != null && v.input("remitente") instanceof Map<?, ?> rem && Php.strictEq(rem.get("num_doc"), tenant.getRuc())) {
                v.addError("remitente.num_doc", "El remitente no puede ser el mismo que el transportista emisor (RUC del tenant).");
            }
            if (Php.empty(v.input("doc_relacionado"))) {
                v.addError("doc_relacionado",
                        "La Guía de Remisión Transportista requiere al menos un documento relacionado (factura, boleta, DAM, GRR, etc.).");
            }
            Object rels = v.input("doc_relacionado");
            if (rels instanceof List<?> || rels instanceof Map<?, ?>) {
                List<Map.Entry<String, Object>> entries = entries(rels);
                for (Map.Entry<String, Object> e : entries) {
                    if (!(e.getValue() instanceof Map<?, ?> doc)) {
                        continue;
                    }
                    Object tipoRel = doc.get("tipo_codigo");
                    if (Php.truthy(tipoRel) && !List.of("01", "03", "09", "31", "50", "52").contains(tipoRel)) {
                        v.addError("doc_relacionado." + e.getKey() + ".tipo_codigo",
                                "Tipo de documento relacionado inválido para GRT. Use 01=factura, 03=boleta, 09=guía remitente, 31=guía transportista, 50=DAM o 52=DS.");
                    }
                    if (("09".equals(tipoRel) || "31".equals(tipoRel)) && Php.empty(doc.get("ruc_emisor"))) {
                        v.addError("doc_relacionado." + e.getKey() + ".ruc_emisor",
                                "Para relacionar una guía en GRT debe informar el RUC emisor del documento relacionado.");
                    }
                    // Corrección: SUNAT rechaza (3380) la factura o boleta relacionada sin emisor
                    if (("01".equals(tipoRel) || "03".equals(tipoRel)) && Php.empty(doc.get("ruc_emisor"))) {
                        v.addError("doc_relacionado." + e.getKey() + ".ruc_emisor",
                                "Para relacionar una factura o boleta en GRT debe informar el RUC emisor del documento relacionado (SUNAT 3380).");
                    }
                }
            }
            if (Php.empty(v.input("vehiculo"))) {
                v.addError("vehiculo", "La Guía Transportista requiere datos del vehículo.");
            }
            if (Php.empty(v.input("conductor")) && Php.empty(v.input("conductores"))) {
                v.addError("conductor", "La Guía Transportista requiere al menos un conductor.");
            }
            if (!Php.empty(v.input("datos_subcontratador")) && Php.empty(v.input("datos_pagador_flete"))) {
                v.addError("datos_pagador_flete",
                        "Si existe transporte subcontratado, debe informar quién paga el flete (remitente/subcontratador/tercero).");
            }
            if (v.input("datos_pagador_flete") instanceof Map<?, ?> pag && Php.strictEq(pag.get("tipo"), "tercero")) {
                if (Php.empty(pag.get("num_doc"))) {
                    v.addError("datos_pagador_flete.num_doc", "Si el pagador es tercero, debe informar su número de documento.");
                }
                if (Php.empty(pag.get("razon_social"))) {
                    v.addError("datos_pagador_flete.razon_social", "Si el pagador es tercero, debe informar su razón social.");
                }
            }
            return;
        }

        if (Php.strictEq(modTraslado, "01") && !esM1L) {
            if (Php.empty(v.input("transportista"))) {
                v.addError("transportista", "El transportista es requerido para transporte público.");
            }
            LocalDate emision = date(v.input("fecha_emision"));
            if (filled(v.input("fecha_emision")) && emision != null && !emision.isBefore(LocalDate.of(2026, 6, 1))
                    && Php.empty(v.input("fecha_entrega_transportista"))) {
                v.addError("fecha_entrega_transportista",
                        "La fecha de entrega de bienes al transportista es requerida para GRE Remitente con transporte público desde el 2026-06-01.");
            }
            LocalDateTime entrega = dateTime(v.input("fecha_entrega_transportista"));
            LocalDateTime traslado = dateTime(v.input("fecha_traslado"));
            if (filled(v.input("fecha_entrega_transportista")) && filled(v.input("fecha_traslado")) && entrega != null && traslado != null
                    && entrega.isBefore(traslado)) {
                v.addError("fecha_entrega_transportista", "La fecha de entrega de bienes al transportista no puede ser anterior a fecha_traslado.");
            }
            if (tenant != null && v.input("transportista") instanceof Map<?, ?> tr && Php.strictEq(tr.get("num_doc"), tenant.getRuc())) {
                v.addError("transportista.num_doc", "Para transporte público (mod_traslado=01), el transportista debe ser una empresa tercera "
                        + "distinta al RUC emisor. Si el traslado lo realiza la misma empresa, use mod_traslado=02 e informe vehículo y conductor.");
            }
        }
        if (Php.strictEq(modTraslado, "02") && !esM1L) {
            if (Php.empty(v.input("vehiculo"))) {
                v.addError("vehiculo", "El vehículo es requerido para transporte privado.");
            }
            if (Php.empty(v.input("conductor")) && Php.empty(v.input("conductores"))) {
                v.addError("conductor", "Al menos un conductor es requerido para transporte privado.");
            }
        }
        String motivo = Php.toStr(v.input("cod_traslado"));
        Object dest = v.input("destinatario");
        Object destTipoDoc = dest instanceof Map<?, ?> d ? d.get("tipo_doc") : null;
        Object destNumDoc = dest instanceof Map<?, ?> d ? d.get("num_doc") : null;
        if (List.of("06", "17", "19").contains(motivo) && !Php.strictEq(destTipoDoc, "6")) {
            v.addError("destinatario.tipo_doc", "Para el motivo de traslado " + motivo + " el destinatario debe tener RUC (tipo_doc=6).");
        }
        if (tenant != null && Php.truthy(destNumDoc)) {
            if (List.of("02", "04", "07").contains(motivo) && !Php.strictEq(destNumDoc, tenant.getRuc())) {
                v.addError("destinatario.num_doc", "Para el motivo " + motivo + " el destinatario debe ser el mismo emisor (RUC " + tenant.getRuc() + ").");
            }
            if (List.of("01", "03", "05", "06", "09", "14", "17").contains(motivo) && Php.strictEq(destNumDoc, tenant.getRuc())) {
                v.addError("destinatario.num_doc", "Para el motivo " + motivo + " el destinatario no puede ser el mismo emisor (remitente).");
            }
        }
        if (List.of("08", "09", "19").contains(motivo)) {
            List<String> tiposDam = "19".equals(motivo) ? List.of("50", "52", "91") : List.of("50", "52");
            boolean tieneDam = false;
            for (Object o : asList(v.input("doc_relacionado"))) {
                if (o instanceof Map<?, ?> d && tiposDam.contains(d.get("tipo_codigo"))) {
                    tieneDam = true;
                }
            }
            if (!tieneDam) {
                String lista = "19".equals(motivo) ? "50 (DAM), 52 (DS) o 91 (Manifiesto de carga)" : "50 (DAM) o 52 (DS)";
                v.addError("doc_relacionado", "Para el motivo de traslado " + motivo + " debe incluir un documento relacionado tipo " + lista + ".");
            }
        }
    }

    private void validarSerieRegistrada(Validator v, Tenant tenant, String tipo, String etiqueta) {
        Object serie = v.input("serie");
        if (tenant == null || !(serie instanceof String s) || s.isEmpty()) {
            return;
        }
        var found = series.findByTenantIdAndTipoDocumentoAndSerie(tenant.getId(), tipo, s);
        if (found.isPresent() && found.get().isActive()) {
            return;
        }
        if (found.isPresent()) {
            v.addError("serie", "La serie " + s + " está desactivada. Actívala en el módulo Series para poder emitir " + etiqueta + ".");
            return;
        }
        boolean tieneAlguna = !series.list(tenant.getId(), false, null, tipo).isEmpty();
        v.addError("serie", tieneAlguna
                ? "No tienes registrada la serie " + s + " para " + etiqueta + ". Revisa el número de serie o regístrala en el módulo Series."
                : "Aún no tienes ninguna serie registrada para " + etiqueta + ". Crea una serie de " + etiqueta + " en el módulo Series antes de emitir.");
    }

    private void validarCorrelativoManual(Validator v, Tenant tenant) {
        Object correlativo = v.input("correlativo");
        Object serie = v.input("serie");
        if (correlativo == null || "".equals(correlativo) || tenant == null || !Php.truthy(serie)) {
            return;
        }
        if (guides.existsCorrelativo(tenant.getId(), Php.toStr(serie), Php.toInt(correlativo))) {
            v.addError("correlativo", "El correlativo " + Php.toStr(correlativo) + " ya existe para la serie " + Php.toStr(serie)
                    + ". Use otro número o deje el campo vacío para autoincremento.");
        }
    }

    /** {@code $request->date()}: null si no se puede interpretar. */
    static LocalDateTime dateTime(Object v) {
        if (!(v instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return PhpDates.parseDateTime(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static LocalDate date(Object v) {
        LocalDateTime t = dateTime(v);
        return t == null ? null : t.toLocalDate();
    }

    private static List<Map.Entry<String, Object>> entries(Object v) {
        List<Map.Entry<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                out.add(Map.entry(String.valueOf(i), l.get(i) == null ? "" : l.get(i)));
            }
        } else if (v instanceof Map<?, ?> m) {
            m.forEach((k, val) -> out.add(Map.entry(String.valueOf(k), val == null ? "" : val)));
        }
        return out;
    }

    static List<Object> asList(Object v) {
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        if (v instanceof Map<?, ?> m) {
            return new ArrayList<>(m.values());
        }
        return new ArrayList<>();
    }
}
