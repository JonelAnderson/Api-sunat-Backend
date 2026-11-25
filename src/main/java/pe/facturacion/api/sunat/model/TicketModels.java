package pe.facturacion.api.sunat.model;

import java.time.LocalDate;
import java.util.List;
import pe.facturacion.api.sunat.model.SaleModels.Company;
import pe.facturacion.api.sunat.model.SaleModels.Document;

/**
 * Documentos que se envían por {@code sendSummary} y se resuelven con ticket:
 * {@code Greenter\Model\Summary\Summary} (RC) y {@code Greenter\Model\Voided\Voided} (RA).
 */
public final class TicketModels {

    private TicketModels() {
    }

    public static class SummaryDetail {
        public String tipoDoc;
        public String serieNro;
        public String estado;
        public String clienteTipo;
        public String clienteNro;
        public Document docReferencia;
        public Double total;
        public Double mtoOperGravadas;
        public Double mtoOperInafectas;
        public Double mtoOperExoneradas;
        public Double mtoOperGratuitas;
        public Double mtoOperExportacion;
        public Double mtoOtrosCargos;
        public Double mtoIGV;
        public Double mtoIvap;
        public Double mtoISC;
        public Double mtoOtrosTributos;
        public Double mtoIcbper;
        public Double porcentajeIgv;
    }

    /** Resumen diario de boletas. */
    public static class Summary {
        public String correlativo;
        /** Fecha de los documentos ({@code ReferenceDate}). */
        public LocalDate fecGeneracion;
        /** Fecha de envío ({@code IssueDate}, parte del identificador). */
        public LocalDate fecResumen;
        public String moneda = "PEN";
        public Company company;
        public List<SummaryDetail> details;

        public String xmlId() {
            return "RC-" + fecResumen.toString().replace("-", "") + "-" + correlativo;
        }

        public String name() {
            return company.ruc + "-" + xmlId();
        }
    }

    public static class VoidedDetail {
        public String tipoDoc;
        public String serie;
        public String correlativo;
        public String desMotivoBaja;
    }

    /** Comunicación de baja. */
    /** Comunicación de baja (RA) o, con prefijo "RR", {@code Reversion} de Greenter. */
    public static class Voided {
        public String prefijo = "RA";
        public String correlativo;
        public LocalDate fecGeneracion;
        public LocalDate fecComunicacion;
        public Company company;
        public List<VoidedDetail> details;

        public String xmlId() {
            return prefijo + "-" + fecComunicacion.toString().replace("-", "") + "-" + correlativo;
        }

        public String name() {
            return company.ruc + "-" + xmlId();
        }
    }
}
