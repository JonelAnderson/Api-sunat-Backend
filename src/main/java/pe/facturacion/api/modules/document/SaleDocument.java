package pe.facturacion.api.modules.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fila de {@code invoices} o {@code boletas} (modelos {@code Invoice}/{@code Boleta} de API-PRO).
 * Los JSON ({@code cuotas}, {@code detraccion}...) se guardan decodificados con los tipos de
 * {@code PhpJson}. {@code fechaEmision} es la hora local de Lima (como en API-PRO).
 */
public class SaleDocument {

    public DocumentKind kind;
    public Long id;
    public Long tenantId;
    public Long sucursalId;
    public Long clientId;
    public String serie;
    public int correlativo;
    public String codLocal = "0000";
    public LocalDateTime fechaEmision;
    public LocalDate fechaVencimiento;
    public String tipoOperacion = "0101";
    public String tipoMoneda = "PEN";
    public String formaPago = "Contado";
    public String clientTipoDoc;
    public String clientNumDoc;
    public String clientRazonSocial;
    public String clientDireccion;
    public BigDecimal mtoOperGravadas = BigDecimal.ZERO;
    public BigDecimal mtoOperExoneradas = BigDecimal.ZERO;
    public BigDecimal mtoOperInafectas = BigDecimal.ZERO;
    public BigDecimal mtoOperExportacion = BigDecimal.ZERO;
    public BigDecimal mtoOperGratuitas = BigDecimal.ZERO;
    public BigDecimal mtoIgv = BigDecimal.ZERO;
    public BigDecimal mtoBaseIvap = BigDecimal.ZERO;
    public BigDecimal mtoIvap = BigDecimal.ZERO;
    public BigDecimal mtoIsc = BigDecimal.ZERO;
    public BigDecimal mtoIcbper = BigDecimal.ZERO;
    public BigDecimal totalImpuestos = BigDecimal.ZERO;
    public BigDecimal valorVenta = BigDecimal.ZERO;
    public BigDecimal subTotal = BigDecimal.ZERO;
    public BigDecimal mtoImpVenta = BigDecimal.ZERO;
    public BigDecimal totalAnticipos = BigDecimal.ZERO;
    public BigDecimal totalDescuentos = BigDecimal.ZERO;
    public String leyenda;
    public String observacion;
    /** Corrección: automatico | manual | solo_registro (cómo se creó; PUT lo respeta). */
    public String modoEnvio = "automatico";
    public String paymentStatus = "pendiente";
    public BigDecimal montoPagado = BigDecimal.ZERO;
    public Object cuotas;
    public Object detraccion;
    public Object percepcion;
    public Object anticipos;
    public Object descuentosGlobales;
    public Object guias;
    public Object extras;
    public Object leyendas;
    public Object contratoColaboracion;
    public String xmlPath;
    public String cdrPath;
    public String pdfPath;
    public String hashCpe;
    public String sunatStatus = "pendiente";
    public String sunatCode;
    public String sunatDescription;
    public Object sunatNotes;
    public String ticket;
    public LocalDateTime sentAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    // Solo notas de crédito/débito
    public String docAfectadoTipo;
    public String docAfectadoSerie;
    public String docAfectadoCorrelativo;
    public String codMotivo;
    public String desMotivo;

    public List<SaleItem> items = new ArrayList<>();

    /** {@code numero_completo}: serie + correlativo con 6 dígitos ({@code F001-000001}). */
    public String numeroCompleto() {
        String c = String.valueOf(correlativo);
        return serie + "-" + (c.length() < 6 ? "0".repeat(6 - c.length()) + c : c);
    }

    /** Ítem de {@code invoice_items}/{@code boleta_items}. */
    public static class SaleItem {
        public Long id;
        public String codigo;
        public String codProductoSunat;
        public String descripcion;
        public String unidad;
        public BigDecimal cantidad;
        public BigDecimal mtoValorUnitario = BigDecimal.ZERO;
        public BigDecimal mtoValorVenta = BigDecimal.ZERO;
        public BigDecimal mtoBaseIgv = BigDecimal.ZERO;
        public BigDecimal porcentajeIgv = new BigDecimal("18");
        public BigDecimal igv = BigDecimal.ZERO;
        public String tipAfeIgv = "10";
        public BigDecimal isc = BigDecimal.ZERO;
        public BigDecimal icbper = BigDecimal.ZERO;
        public BigDecimal totalImpuestos = BigDecimal.ZERO;
        public BigDecimal mtoPrecioUnitario = BigDecimal.ZERO;
        public BigDecimal descuento = BigDecimal.ZERO;
        public Object descuentos;
        public String tipSisIsc;
        public BigDecimal porcentajeIsc;
        public BigDecimal factorIcbper;
    }

    /** Fila de {@code payments}. */
    public static class Payment {
        public Long id;
        public String metodo;
        public BigDecimal monto;
        public String referencia;
        public BigDecimal montoRecibido;
        public String notas;
        public LocalDateTime createdAt;
    }
}
