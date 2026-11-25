package pe.facturacion.api.sunat.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import pe.facturacion.api.sunat.model.SaleModels.Client;
import pe.facturacion.api.sunat.model.SaleModels.Company;

/**
 * {@code Greenter\Model\Retention\*} y {@code Greenter\Model\Perception\*} en un solo modelo:
 * en la retención el monto es lo retenido y el neto lo pagado; en la percepción, lo percibido y
 * lo cobrado.
 */
public final class RetPerModels {

    private RetPerModels() {
    }

    /** {@code Exchange}: tipo de cambio del documento relacionado. */
    public static class Exchange {
        public String monedaRef;
        public String monedaObj;
        public double factor;
        public LocalDate fecha;
    }

    /** {@code Payment}: pago (retención) o cobro (percepción). */
    public static class Payment {
        public String moneda;
        public double importe;
        public LocalDate fecha;
    }

    /** {@code RetentionDetail} / {@code PerceptionDetail}. */
    public static class Detail {
        public String tipoDoc;
        public String numDoc;
        public LocalDate fechaEmision;
        public double impTotal;
        public String moneda;
        public List<Payment> pagos = new ArrayList<>();
        /** Fecha de retención o de percepción. */
        public LocalDate fecha;
        /** Importe retenido o percibido (PEN). */
        public double impMonto;
        /** Neto pagado o cobrado (PEN). */
        public double impNeto;
        public Exchange tipoCambio;
    }

    /** {@code Retention} / {@code Perception}. */
    public static class Doc {
        public boolean perception;
        public String serie;
        public String correlativo;
        public LocalDateTime fechaEmision;
        public Company company;
        /** Proveedor (retención) o cliente (percepción). */
        public Client tercero;
        public String regimen;
        public double tasa;
        public double impMonto;
        public double impNeto;
        public String observacion;
        public List<Detail> details = new ArrayList<>();

        public String tipoDoc() {
            return perception ? "40" : "20";
        }

        /** {@code getName()}: RUC-tipo-serie-correlativo. */
        public String name() {
            return company.ruc + "-" + tipoDoc() + "-" + serie + "-" + correlativo;
        }
    }
}
