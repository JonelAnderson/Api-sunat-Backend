package pe.facturacion.api.sunat.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import pe.facturacion.api.sunat.model.SaleModels.Client;
import pe.facturacion.api.sunat.model.SaleModels.Company;

/** {@code Greenter\Model\Despatch\*} (GRE 2022: remitente 09 y transportista 31). */
public final class DespatchModels {

    private DespatchModels() {
    }

    public static class AdditionalDoc {
        public String tipo;
        public String nro;
        public String tipoDesc;
        public String emisor;
    }

    public static class Direction {
        public String ubigueo;
        public String direccion;
        public String ruc;
        public String codLocal;
    }

    public static class Transportist {
        public String tipoDoc;
        public String numDoc;
        public String rznSocial;
        public String nroMtc;
    }

    public static class Vehicle {
        public String placa;
        public String nroCirculacion;
        public String codEmisor;
        public String nroAutorizacion;
        public List<Vehicle> secundarios = new ArrayList<>();
    }

    public static class Driver {
        public String tipo;
        public String tipoDoc;
        public String nroDoc;
        public String nombres;
        public String apellidos;
        public String licencia;
    }

    public static class Shipment {
        public String codTraslado;
        public String modTraslado;
        public LocalDate fecTraslado;
        public double pesoTotal;
        public String undPesoTotal;
        public Integer numBultos;
        public List<String> indicadores = new ArrayList<>();
        public Direction llegada;
        public Direction partida;
        public Transportist transportista;
        public Vehicle vehiculo;
        public List<Driver> choferes = new ArrayList<>();
    }

    public static class DespatchDetail {
        public double cantidad;
        public String unidad;
        public String descripcion;
        public String codigo;
        public String codProdSunat;
    }

    public static class Despatch {
        public String tipoDoc;
        public String serie;
        public String correlativo;
        public LocalDateTime fechaEmision;
        public Company company;
        public String observacion;
        public Client destinatario;
        public Client tercero;
        public Client comprador;
        public List<AdditionalDoc> addDocs = new ArrayList<>();
        public Shipment envio;
        public List<DespatchDetail> details = new ArrayList<>();

        /** {@code getName()}: RUC-tipo-serie-correlativo. */
        public String name() {
            return company.ruc + "-" + tipoDoc + "-" + serie + "-" + correlativo;
        }
    }
}
