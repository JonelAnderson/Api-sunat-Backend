package pe.facturacion.api.modules.document;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.facturacion.api.modules.document.SaleDocument.Payment;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.php.Php;

/** {@code RegisterPaymentAction}: registra pagos y recalcula {@code monto_pagado}/{@code payment_status}. */
@Service
public class PaymentService {

    /** {@code Payment::METODOS}. */
    public static final List<String> METODOS = List.of("efectivo", "yape", "plin", "transferencia", "tarjeta", "cheque", "otro");

    private final SaleDocumentRepository documents;

    public PaymentService(SaleDocumentRepository documents) {
        this.documents = documents;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void register(SaleDocument d, List<Object> pagos) {
        // Corrección: no se cobra más que el total (API-PRO aceptaba 336.00 sobre 236.00). La fila
        // bloqueada serializa dos pagos simultáneos sobre el mismo comprobante.
        documents.lockRow(d);
        if ("anulado".equals(d.paymentStatus)) {
            throw ApiException.of(422, "El comprobante está anulado por una nota de crédito: no tiene saldo por cobrar.");
        }
        BigDecimal nuevo = BigDecimal.ZERO;
        for (Object o : pagos) {
            nuevo = nuevo.add(SaleDocumentRepository.dec(((Map<String, Object>) o).get("monto")));
        }
        // Con percepción el cliente paga además el monto percibido (percepcion.mto_total)
        BigDecimal cobrable = d.mtoImpVenta;
        if (d.percepcion instanceof Map<?, ?> pm && !Php.empty(pm.get("mto_total"))) {
            cobrable = cobrable.max(SaleDocumentRepository.dec(pm.get("mto_total")));
        }
        BigDecimal saldo = cobrable.subtract(documents.sumPayments(d));
        if (nuevo.compareTo(saldo) > 0) {
            throw ApiException.of(422, "El pago (" + nuevo.setScale(2, java.math.RoundingMode.HALF_UP) + ") supera el saldo pendiente ("
                    + saldo.max(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP) + ") del comprobante.");
        }
        for (Object o : pagos) {
            Map<String, Object> pago = (Map<String, Object>) o;
            Payment p = new Payment();
            p.metodo = Php.toStr(pago.get("metodo"));
            p.monto = SaleDocumentRepository.dec(pago.get("monto"));
            p.referencia = pago.get("referencia") == null ? null : Php.toStr(pago.get("referencia"));
            p.montoRecibido = SaleDocumentRepository.dec(pago.get("monto_recibido"));
            p.notas = pago.get("notas") == null ? null : Php.toStr(pago.get("notas"));
            documents.insertPayment(d, p);
        }
        recalculate(d);
    }

    public void recalculate(SaleDocument d) {
        BigDecimal pagado = documents.sumPayments(d);
        if ("anulado".equals(d.paymentStatus)) {
            // Anulado por NC: borrar un pago no lo vuelve a poner como deuda
            d.montoPagado = pagado;
            documents.update(d, Map.of("monto_pagado", pagado));
            return;
        }
        double montoPagado = pagado.doubleValue();
        double total = d.mtoImpVenta.doubleValue();
        String status = "pendiente";
        if (montoPagado > 0 && montoPagado < total) {
            status = "parcial";
        } else if (montoPagado >= total) {
            status = "pagado";
        }
        d.montoPagado = pagado;
        d.paymentStatus = status;
        documents.update(d, Map.of("monto_pagado", pagado, "payment_status", status));
    }
}
