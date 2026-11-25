package pe.facturacion.api.modules.ticket;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pe.facturacion.api.modules.ticket.TicketRepository.TicketDoc;

/**
 * Crea un Resumen diario de reversiones (RR) en {@code voided_documents}: numeración del día con
 * bloqueo, documentos en proceso de reversión y envío encolado (servicio "otros CPE", anexo
 * N.° 22). Lo usan {@code POST /reversiones} (retenciones y percepciones) y
 * {@code POST /liquidaciones-compra/{id}/reversion}.
 */
@Service
public class ReversionService {

    private final TicketRepository tickets;
    private final VoidedEndpoints voided;
    private final TicketJobs jobs;
    private final TransactionTemplate tx;

    public ReversionService(TicketRepository tickets, VoidedEndpoints voided, TicketJobs jobs, TransactionTemplate tx) {
        this.tickets = tickets;
        this.voided = voided;
        this.jobs = jobs;
        this.tx = tx;
    }

    /**
     * @param detalles {@code tipo_documento, serie, correlativo, motivo} de cada documento
     * @param fechaGeneracion fecha de emisión de los documentos revertidos (ReferenceDate)
     * @param fechaComunicacion fecha del resumen (IssueDate e identificador)
     */
    public TicketDoc crear(long tenantId, List<Object> detalles, LocalDate fechaGeneracion, LocalDate fechaComunicacion, boolean enviar) {
        return tx.execute(st -> {
            tickets.lockNumbering(TicketRepository.VOIDED, tenantId);
            String correlativo = SummaryEndpoints.pad3(tickets.maxCorrelativo(TicketRepository.VOIDED, tenantId, fechaComunicacion, "RR-") + 1);
            TicketDoc d = new TicketDoc();
            d.table = TicketRepository.VOIDED;
            d.tenantId = tenantId;
            d.identifier = "RR-" + fechaComunicacion.toString().replace("-", "") + "-" + correlativo;
            d.correlativo = correlativo;
            d.fechaReferencia = fechaGeneracion;
            d.fechaEnvio = fechaComunicacion;
            d.totalDocumentos = detalles.size();
            d.detalles = detalles;
            d.sunatStatus = "pendiente";
            tickets.insert(d);
            if (enviar) {
                // Corrección: una reversión manual pendiente no marca los documentos hasta que se envía
                voided.marcar(tenantId, detalles, "anulacion_en_proceso", null);
                jobs.dispatchSend(TicketRepository.VOIDED, d.id);
                tickets.update(d, Map.of("sunat_status", "enviado"));
                d.sunatStatus = "enviado";
            }
            return d;
        });
    }
}
