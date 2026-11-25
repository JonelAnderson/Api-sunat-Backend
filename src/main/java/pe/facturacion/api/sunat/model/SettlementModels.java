package pe.facturacion.api.sunat.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import pe.facturacion.api.sunat.model.SaleModels.Company;

/** Liquidación de compra electrónica (tipo 04): {@code SelfBilledInvoice} de UBL 2.1. */
public final class SettlementModels {

    private SettlementModels() {
    }

    /** Domicilio o lugar con ubigeo y tipo (catálogo 60). */
    public static class Place {
        public String direccion;
        public String ubigeo;
        public String tipo;
    }

    /** Propiedad del ítem (catálogo 55), para la compra de oro. */
    public record Property(String code, String name, String value) {
    }

    public static class Line {
        public String codigo;
        public String codProductoSunat;
        public String descripcion;
        public String unidad;
        public BigDecimal cantidad;
        public BigDecimal valorUnitario;
        public BigDecimal precioUnitario;
        public String tipAfeIgv;
        public BigDecimal valorVenta;
        public BigDecimal porcentajeIgv;
        public BigDecimal igv;
        public BigDecimal porcentajeRenta;
        public BigDecimal renta;
        public List<Property> propiedades = new ArrayList<>();
    }

    public static class Settlement {
        public String serie;
        public long correlativo;
        public LocalDateTime fechaEmision;
        public String tipoOperacion;
        public String moneda;
        /** Emisor electrónico = comprador (AccountingCustomerParty). */
        public Company comprador;
        public String vendedorTipoDoc;
        public String vendedorNumDoc;
        public String vendedorNombre;
        public Place vendedorDomicilio;
        public Place lugar;
        public BigDecimal gravadas;
        public BigDecimal exoneradas;
        public BigDecimal inafectas;
        public BigDecimal igv;
        public BigDecimal valorVenta;
        public BigDecimal baseRenta;
        public BigDecimal renta;
        public BigDecimal subTotal;
        public BigDecimal neto;
        public String leyenda;
        public List<String[]> guias = new ArrayList<>();
        public String placa;
        public List<Line> lines = new ArrayList<>();

        /** Nombre del archivo: RUC-04-serie-correlativo. */
        public String name() {
            return comprador.ruc + "-04-" + serie + "-" + correlativo;
        }
    }
}
