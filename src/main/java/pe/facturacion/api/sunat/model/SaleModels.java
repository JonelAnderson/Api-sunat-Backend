package pe.facturacion.api.sunat.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Modelo de comprobante de venta equivalente a {@code Greenter\Model\Sale\*}: solo lo que
 * leen las plantillas UBL 2.1. Los importes son {@code Double} (los setters de Greenter son
 * {@code ?float}); {@code null} significa "no asignado" (los {@code is not null} de la plantilla).
 */
public final class SaleModels {

    private SaleModels() {
    }

    public static class Address {
        public String ubigueo;
        public String codigoPais = "PE";
        public String departamento;
        public String provincia;
        public String distrito;
        public String urbanizacion;
        public String direccion;
        public String codLocal = "0000";
    }

    public static class Company {
        public String ruc;
        public String razonSocial;
        public String nombreComercial;
        public Address address;
        public String email;
        public String telephone;
    }

    public static class Client {
        public String tipoDoc;
        public String numDoc;
        public String rznSocial;
        public Address address;
        public String email;
        public String telephone;
    }

    public static class Legend {
        public String code;
        public String value;

        public Legend(String code, String value) {
            this.code = code;
            this.value = value;
        }
    }

    public static class Document {
        public String tipoDoc;
        public String nroDoc;
    }

    public static class Charge {
        public String codTipo;
        public Double factor;
        public Double monto;
        public Double montoBase;
    }

    public static class Prepayment {
        public String tipoDocRel;
        public String nroDocRel;
        public Double total;
    }

    public static class Detraction {
        public Double percent;
        public Double mount;
        public String ctaBanco;
        public String codMedioPago;
        public String codBienDetraccion;
    }

    public static class SalePerception {
        public String codReg;
        public Double porcentaje;
        public Double mtoBase;
        public Double mto;
        public Double mtoTotal;
    }

    public static class FormaPago {
        public String tipo;
        public Double monto;
        public String moneda;

        public static FormaPago contado() {
            FormaPago f = new FormaPago();
            f.tipo = "Contado";
            return f;
        }

        public static FormaPago credito(double monto) {
            FormaPago f = new FormaPago();
            f.tipo = "Credito";
            f.monto = monto;
            return f;
        }
    }

    public static class Cuota {
        public Double monto;
        public String moneda;
        public LocalDate fechaPago;
    }

    public static class SaleDetail {
        public String unidad;
        public Double cantidad;
        public String codProducto;
        public String codProdSunat;
        public String codProdGS1;
        public String descripcion;
        public Double mtoBaseIgv;
        public Double porcentajeIgv;
        public Double igv;
        public String tipAfeIgv;
        public Double mtoBaseIsc;
        public String tipSisIsc;
        public Double porcentajeIsc;
        public Double isc;
        public Double mtoBaseOth;
        public Double porcentajeOth;
        public Double otroTributo;
        public Double icbper;
        public Double factorIcbper = 0.30;
        public Double totalImpuestos;
        public Double mtoPrecioUnitario;
        public Double mtoValorVenta;
        public Double mtoValorUnitario;
        public Double mtoValorGratuito;
        public List<Charge> descuentos;
        public List<Charge> cargos;
    }

    /** {@code Greenter\Model\Sale\Invoice} (factura 01 y boleta 03). */
    public static class Invoice {
        public String ublVersion = "2.1";
        public String tipoOperacion;
        public String tipoDoc;
        public String serie;
        public String correlativo;
        /** Fecha y hora de emisión en hora de Lima. */
        public LocalDateTime fechaEmision;
        public LocalDate fecVencimiento;
        public String tipoMoneda;
        public Company company;
        public Client client;
        public FormaPago formaPago;
        public List<Cuota> cuotas;
        public Double mtoOperGravadas;
        public Double mtoOperInafectas;
        public Double mtoOperExoneradas;
        public Double mtoOperExportacion;
        public Double mtoOperGratuitas;
        public Double mtoIGV;
        public Double mtoIGVGratuitas;
        public Double mtoBaseIsc;
        public Double mtoISC;
        public Double mtoBaseIvap;
        public Double mtoIvap;
        public Double mtoBaseOth;
        public Double mtoOtrosTributos;
        public Double icbper;
        public Double totalImpuestos;
        public Double valorVenta;
        public Double subTotal;
        public Double sumOtrosDescuentos;
        public Double sumOtrosCargos;
        public Double totalAnticipos;
        public Double redondeo;
        public Double mtoImpVenta;
        public List<SaleDetail> details;
        public List<Legend> legends;
        public List<Document> guias;
        public List<Prepayment> anticipos;
        public Detraction detraccion;
        public SalePerception perception;
        public List<Charge> descuentos;
        public List<Charge> cargos;

        /** {@code getName()}: {@code RUC-tipoDoc-serie-correlativo}. */
        public String name() {
            return company.ruc + "-" + tipoDoc + "-" + serie + "-" + correlativo;
        }
    }

    /** {@code Greenter\Model\Sale\Note}: nota de crédito (07) o débito (08). */
    public static class Note extends Invoice {
        public String tipDocAfectado;
        public String numDocfectado;
        public String codMotivo;
        public String desMotivo;
    }
}
