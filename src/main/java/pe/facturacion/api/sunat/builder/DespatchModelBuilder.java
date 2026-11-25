package pe.facturacion.api.sunat.builder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.sunat.model.DespatchModels.AdditionalDoc;
import pe.facturacion.api.sunat.model.DespatchModels.Despatch;
import pe.facturacion.api.sunat.model.DespatchModels.DespatchDetail;
import pe.facturacion.api.sunat.model.DespatchModels.Direction;
import pe.facturacion.api.sunat.model.DespatchModels.Driver;
import pe.facturacion.api.sunat.model.DespatchModels.Shipment;
import pe.facturacion.api.sunat.model.DespatchModels.Transportist;
import pe.facturacion.api.sunat.model.DespatchModels.Vehicle;
import pe.facturacion.api.sunat.model.SaleModels.Client;
import pe.facturacion.api.sunat.model.SaleModels.Company;

/**
 * {@code DespatchBuilder} de API-PRO: arma la guía (09 remitente / 31 transportista) desde el
 * arreglo del trabajo de envío. En la GRT el transportista es la propia empresa, así que no se
 * informa {@code transportista} en el envío.
 */
public final class DespatchModelBuilder {

    private static final Logger log = LoggerFactory.getLogger(DespatchModelBuilder.class);

    static final List<String> INDICADORES = List.of(
            "SUNAT_Envio_IndicadorTrasladoVehiculoM1L",
            "SUNAT_Envio_IndicadorTransbordoProgramado",
            "SUNAT_Envio_IndicadorRetornoVehiculoEnvaseVacio",
            "SUNAT_Envio_IndicadorRetornoVehiculoVacio",
            "SUNAT_Envio_IndicadorTrasladoTotalDAMoDS",
            "SUNAT_Envio_IndicadorVehiculoConductoresTransp",
            "SUNAT_Envio_IndicadorPagadorFlete_Remitente",
            "SUNAT_Envio_IndicadorPagadorFlete_Subcontratador",
            "SUNAT_Envio_IndicadorPagadorFlete_Tercero",
            "SUNAT_Envio_IndicadorTrasporteSubcontratado");

    private DespatchModelBuilder() {
    }

    @SuppressWarnings("unchecked")
    public static Despatch build(Tenant tenant, Map<String, Object> data) {
        String tipo = Php.toStr(Php.coalesce(data, "tipo_documento", "09"));
        Despatch d = new Despatch();
        d.tipoDoc = tipo;
        d.serie = Php.toStr(data.get("serie"));
        d.correlativo = Php.toStr(data.get("correlativo"));
        d.fechaEmision = PhpDates.parseDateTime(Php.toStr(data.get("fecha_emision")));
        Company c = new Company();
        c.ruc = tenant.getRuc();
        c.razonSocial = tenant.getRazonSocial();
        d.company = c;
        if (!Php.empty(data.get("observacion"))) {
            d.observacion = Php.toStr(data.get("observacion"));
        }
        d.destinatario = client((Map<String, Object>) data.get("destinatario"));
        if (!Php.empty(data.get("tercero"))) {
            d.tercero = client((Map<String, Object>) data.get("tercero"));
        }
        if (!Php.empty(data.get("comprador"))) {
            d.comprador = client((Map<String, Object>) data.get("comprador"));
        }
        if (!Php.empty(data.get("doc_relacionado"))) {
            for (Map<String, Object> rel : docRelacionados(data.get("doc_relacionado"))) {
                AdditionalDoc a = new AdditionalDoc();
                a.tipo = Php.toStr(rel.get("tipo_codigo"));
                a.nro = Php.toStr(rel.get("numero"));
                if (!Php.empty(rel.get("tipo_descripcion"))) {
                    a.tipoDesc = Php.toStr(rel.get("tipo_descripcion"));
                }
                if (!Php.empty(rel.get("ruc_emisor"))) {
                    a.emisor = Php.toStr(rel.get("ruc_emisor"));
                }
                d.addDocs.add(a);
            }
        }
        d.envio = shipment(data, tipo);
        for (Object o : (List<Object>) data.get("items")) {
            Map<String, Object> item = (Map<String, Object>) o;
            DespatchDetail det = new DespatchDetail();
            det.cantidad = Php.toFloat(item.get("cantidad"));
            det.unidad = Php.toStr(Php.coalesce(item, "unidad", "ZZ"));
            det.descripcion = Php.toStr(item.get("descripcion"));
            det.codigo = Php.toStr(Php.coalesce(item, "codigo", ""));
            if (!Php.empty(item.get("cod_prod_sunat"))) {
                det.codProdSunat = Php.toStr(item.get("cod_prod_sunat"));
            }
            d.details.add(det);
        }
        return d;
    }

    /** {@code normalizeDocRelacionado()}: objeto único o lista. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> docRelacionados(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof Map<?, ?> m) {
            if (m.containsKey("tipo_codigo") && m.get("tipo_codigo") != null || m.get("numero") != null) {
                out.add((Map<String, Object>) m);
                return out;
            }
            for (Object o : m.values()) {
                out.add((Map<String, Object>) o);
            }
            return out;
        }
        for (Object o : (List<Object>) v) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Shipment shipment(Map<String, Object> data, String tipo) {
        Shipment s = new Shipment();
        s.codTraslado = Php.toStr(data.get("cod_traslado"));
        s.modTraslado = Php.toStr(data.get("mod_traslado"));
        s.fecTraslado = PhpDates.parseDate(Php.toStr(data.get("fecha_traslado")));
        s.pesoTotal = Php.toFloat(data.get("peso_total"));
        s.undPesoTotal = Php.toStr(Php.coalesce(data, "und_peso_total", "KGM"));
        if (!Php.empty(data.get("num_bultos"))) {
            s.numBultos = (int) Php.toInt(data.get("num_bultos"));
        }
        List<String> indicadores = new ArrayList<>();
        Object raw = data.get("indicadores");
        if (raw instanceof List<?> l) {
            l.forEach(i -> indicadores.add(Php.toStr(i)));
        } else if (raw instanceof Map<?, ?> m) {
            m.values().forEach(i -> indicadores.add(Php.toStr(i)));
        }
        Object pagador = data.get("datos_pagador_flete");
        if (pagador instanceof Map<?, ?> p && !Php.empty(p.get("tipo"))) {
            String ind = switch (Php.toStr(p.get("tipo"))) {
                case "remitente" -> "SUNAT_Envio_IndicadorPagadorFlete_Remitente";
                case "subcontratador" -> "SUNAT_Envio_IndicadorPagadorFlete_Subcontratador";
                case "tercero" -> "SUNAT_Envio_IndicadorPagadorFlete_Tercero";
                default -> null;
            };
            if (ind != null && !indicadores.contains(ind)) {
                indicadores.add(ind);
            }
        }
        if (!Php.empty(data.get("datos_subcontratador")) && !indicadores.contains("SUNAT_Envio_IndicadorTrasporteSubcontratado")) {
            indicadores.add("SUNAT_Envio_IndicadorTrasporteSubcontratado");
        }
        LinkedHashSet<String> unicos = new LinkedHashSet<>();
        for (String i : indicadores) {
            unicos.add("SUNAT_Envio_IndicadorTrasladoVehiculoCategoriaM1L".equals(i) ? "SUNAT_Envio_IndicadorTrasladoVehiculoM1L" : i);
        }
        for (String i : unicos) {
            if (!INDICADORES.contains(i)) {
                log.warn("Indicador GRE fuera de catálogo (posible typo → riesgo de rechazo SUNAT 3388): {}", i);
            }
        }
        s.indicadores = new ArrayList<>(unicos);
        s.llegada = direction(data, "llegada");
        s.partida = direction(data, "partida");
        if (!"31".equals(tipo) && !Php.empty(data.get("transportista"))) {
            Map<String, Object> t = (Map<String, Object>) data.get("transportista");
            Transportist tr = new Transportist();
            tr.tipoDoc = Php.toStr(t.get("tipo_doc"));
            tr.numDoc = Php.toStr(t.get("num_doc"));
            tr.rznSocial = Php.toStr(t.get("razon_social"));
            if (!Php.empty(t.get("nro_mtc"))) {
                tr.nroMtc = Php.toStr(t.get("nro_mtc"));
            }
            s.transportista = tr;
        }
        if (!Php.empty(data.get("vehiculo"))) {
            s.vehiculo = vehicle((Map<String, Object>) data.get("vehiculo"));
        }
        List<Object> drivers = new ArrayList<>();
        if (!Php.empty(data.get("conductores"))) {
            drivers.addAll(asList(data.get("conductores")));
        } else if (!Php.empty(data.get("conductor"))) {
            Object cond = data.get("conductor");
            if (cond instanceof List<?> l) {
                drivers.addAll(l);
            } else {
                drivers.add(cond);
            }
        }
        for (int i = 0; i < drivers.size(); i++) {
            Map<String, Object> cd = (Map<String, Object>) drivers.get(i);
            Driver dr = new Driver();
            dr.tipoDoc = Php.toStr(cd.get("tipo_doc"));
            dr.nroDoc = Php.toStr(cd.get("num_doc"));
            dr.tipo = Php.toStr(Php.coalesce(cd, "tipo", i == 0 ? "Principal" : "Secundario"));
            if (!Php.empty(cd.get("nombres"))) {
                dr.nombres = Php.toStr(cd.get("nombres"));
            }
            if (!Php.empty(cd.get("apellidos"))) {
                dr.apellidos = Php.toStr(cd.get("apellidos"));
            }
            if (!Php.empty(cd.get("licencia"))) {
                dr.licencia = Php.toStr(cd.get("licencia"));
            }
            s.choferes.add(dr);
        }
        return s;
    }

    private static Direction direction(Map<String, Object> data, String p) {
        Direction d = new Direction();
        d.ubigueo = Php.toStr(data.get(p + "_ubigeo"));
        d.direccion = Php.toStr(data.get(p + "_direccion"));
        if (!Php.empty(data.get(p + "_ruc"))) {
            d.ruc = Php.toStr(data.get(p + "_ruc"));
        }
        if (!Php.empty(data.get(p + "_cod_local"))) {
            d.codLocal = Php.toStr(data.get(p + "_cod_local"));
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Vehicle vehicle(Map<String, Object> v) {
        Vehicle ve = new Vehicle();
        ve.placa = Php.toStr(v.get("placa"));
        if (!Php.empty(v.get("nro_circulacion"))) {
            ve.nroCirculacion = Php.toStr(v.get("nro_circulacion"));
        } else if (!Php.empty(v.get("tuc"))) {
            ve.nroCirculacion = Php.toStr(v.get("tuc"));
        }
        if (!Php.empty(v.get("cod_emisor"))) {
            ve.codEmisor = Php.toStr(v.get("cod_emisor"));
        }
        if (!Php.empty(v.get("nro_autorizacion"))) {
            ve.nroAutorizacion = Php.toStr(v.get("nro_autorizacion"));
        }
        if (!Php.empty(v.get("secundarios"))) {
            for (Object o : asList(v.get("secundarios"))) {
                Map<String, Object> s = (Map<String, Object>) o;
                Vehicle sec = new Vehicle();
                sec.placa = Php.toStr(s.get("placa"));
                if (!Php.empty(s.get("nro_circulacion"))) {
                    sec.nroCirculacion = Php.toStr(s.get("nro_circulacion"));
                }
                if (!Php.empty(s.get("cod_emisor"))) {
                    sec.codEmisor = Php.toStr(s.get("cod_emisor"));
                }
                if (!Php.empty(s.get("nro_autorizacion"))) {
                    sec.nroAutorizacion = Php.toStr(s.get("nro_autorizacion"));
                }
                ve.secundarios.add(sec);
            }
        }
        return ve;
    }

    private static Client client(Map<String, Object> m) {
        Client c = new Client();
        c.tipoDoc = Php.toStr(m.get("tipo_doc"));
        c.numDoc = Php.toStr(m.get("num_doc"));
        c.rznSocial = Php.toStr(m.get("razon_social"));
        return c;
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
}
