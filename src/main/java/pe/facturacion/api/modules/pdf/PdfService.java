package pe.facturacion.api.modules.pdf;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocumentRepository;
import pe.facturacion.api.modules.guide.GuideRepository;
import pe.facturacion.api.modules.retper.RetPerKind;
import pe.facturacion.api.modules.retper.RetPerRepository;
import pe.facturacion.api.modules.settlement.SettlementRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.sunat.pdf.PdfFormat;
import pe.facturacion.api.sunat.pdf.PdfRenderer;

/**
 * Representación impresa: {@code GET .../{id}/pdf?formato=a4|a5|ticket-80|ticket-58} y el PDF que
 * se guarda al ser aceptado ({@code PdfGeneratorService::generateAndStore()}, formato A4).
 *
 * <p>Correcciones: se acepta {@code formato} y {@code format} en todos los documentos (API-PRO solo
 * leía {@code formato} en facturas); la caché de una hora se invalida cuando el documento cambia
 * (la clave incluye {@code updated_at}; API-PRO podía servir un PDF sin el hash o el estado
 * nuevo); se imprimen también la percepción y la liquidación de compra.
 */
@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);
    static final long CACHE_TTL_MS = 3_600_000;

    private final PdfDataMapper mapper;
    private final SaleDocumentRepository documents;
    private final GuideRepository guides;
    private final RetPerRepository retper;
    private final SettlementRepository settlements;
    private final FileStorage storage;
    private final String defaultFormat;
    private final boolean autoGenerate;

    public PdfService(PdfDataMapper mapper, SaleDocumentRepository documents, GuideRepository guides, RetPerRepository retper,
            SettlementRepository settlements, FileStorage storage, @Value("${app.pdf.default-format:a4}") String defaultFormat,
            @Value("${app.pdf.auto-generate:true}") boolean autoGenerate) {
        this.mapper = mapper;
        this.documents = documents;
        this.guides = guides;
        this.retper = retper;
        this.settlements = settlements;
        this.storage = storage;
        this.defaultFormat = defaultFormat;
        this.autoGenerate = autoGenerate;
    }

    // ------------------------------------------------------------- descargas

    public ResponseEntity<Object> sale(HttpServletRequest http, DocumentKind kind, long id) {
        Tenant tenant = ApiContext.requireTenant(http);
        return sale(tenant, Input.of(http), kind, id);
    }

    /** PDF de un comprobante de la empresa indicada (404 si es de otra); {@code in} lleva {@code formato}. */
    public ResponseEntity<Object> sale(Tenant tenant, Input in, DocumentKind kind, long id) {
        SaleDocument d = documents.find(kind, tenant.getId(), id).orElseThrow(ApiException::notFound);
        return respond(tenant, in, kind.table, id, d.updatedAt, d.numeroCompleto(), f -> render(saleData(d, tenant), f));
    }

    public ResponseEntity<Object> guide(HttpServletRequest http, long id) {
        Tenant tenant = ApiContext.requireTenant(http);
        return guide(tenant, Input.of(http), id);
    }

    public ResponseEntity<Object> guide(Tenant tenant, Input in, long id) {
        Map<String, Object> g = guides.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        String numero = g.get("serie") + "-" + String.format("%06d", ((Number) g.get("correlativo")).longValue());
        return respond(tenant, in, "dispatch_guides", id, (LocalDateTime) g.get("updated_at"), numero, f -> render(mapper.guide(g, tenant), f));
    }

    public ResponseEntity<Object> retper(HttpServletRequest http, RetPerKind k, long id) {
        Tenant tenant = ApiContext.requireTenant(http);
        return retper(tenant, Input.of(http), k, id);
    }

    public ResponseEntity<Object> retper(Tenant tenant, Input in, RetPerKind k, long id) {
        Map<String, Object> r = retper.find(k, tenant.getId(), id).orElseThrow(ApiException::notFound);
        String numero = r.get("serie") + "-" + String.format("%08d", ((Number) r.get("correlativo")).longValue());
        return respond(tenant, in, k.table, id, (LocalDateTime) r.get("updated_at"), numero,
                f -> render(mapper.retper(k, r, retper.items(k, id), tenant), f));
    }

    public ResponseEntity<Object> settlement(HttpServletRequest http, long id) {
        Tenant tenant = ApiContext.requireTenant(http);
        return settlement(tenant, Input.of(http), id);
    }

    public ResponseEntity<Object> settlement(Tenant tenant, Input in, long id) {
        Map<String, Object> s = settlements.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        String numero = s.get("serie") + "-" + String.format("%08d", ((Number) s.get("correlativo")).longValue());
        return respond(tenant, in, SettlementRepository.TABLE, id, (LocalDateTime) s.get("updated_at"), numero,
                f -> render(mapper.settlement(s, settlements.items(id), tenant), f));
    }

    @FunctionalInterface
    interface Generator {
        byte[] generate(PdfFormat format);
    }

    private ResponseEntity<Object> respond(Tenant tenant, Input in, String table, long id, LocalDateTime updatedAt, String numero,
            Generator generator) {
        Object raw = in.get("formato") != null ? in.get("formato") : in.get("format");
        PdfFormat format = PdfFormat.from(raw == null ? defaultFormat : Php.toStr(raw));
        if (format == null) {
            return ApiResponse.error("Formato inválido. Opciones: a4, a5, ticket-80, ticket-58", 422);
        }
        long tenantId = tenant.getId();
        long version = updatedAt == null ? 0 : updatedAt.toEpochSecond(ZoneOffset.UTC);
        String cachePath = "pdf-cache/" + tenantId + "/" + table + "_" + id + "_" + format.value + "_" + version + ".pdf";
        byte[] content = cached(cachePath);
        if (content == null) {
            content = generator.generate(format);
            storage.put(Disk.PRIVATE, cachePath, content);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + numero + ".pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(content);
    }

    private byte[] cached(String path) {
        if (!storage.exists(Disk.PRIVATE, path)) {
            return null;
        }
        try {
            Path p = storage.resolve(Disk.PRIVATE, path);
            if (Files.getLastModifiedTime(p).toMillis() < System.currentTimeMillis() - CACHE_TTL_MS) {
                return null;
            }
            return Files.readAllBytes(p);
        } catch (Exception e) {
            return null;
        }
    }

    // --------------------------------------------------------- generación

    public Map<String, Object> saleData(SaleDocument d, Tenant tenant) {
        documents.loadItems(d);
        // Como API-PRO: los pagos (adelanto) solo se cargan en facturas y boletas
        List<SaleDocument.Payment> pagos = d.kind == DocumentKind.FACTURA || d.kind == DocumentKind.BOLETA ? documents.payments(d) : null;
        return mapper.sale(d, tenant, pagos);
    }

    public byte[] render(Map<String, Object> data, PdfFormat format) {
        return PdfRenderer.render(data, format);
    }

    /**
     * {@code generateAndStore()}: PDF A4 en {ruc}/{cod_local}/{fecha}/{carpeta}/pdf/{numero}.pdf y
     * {@code pdf_path}. Un fallo no interrumpe el flujo del comprobante (como en API-PRO).
     */
    public void storeSale(SaleDocument d, Tenant tenant) {
        if (!autoGenerate) {
            return;
        }
        try {
            String path = tenant.getRuc() + "/" + (d.codLocal == null ? "0000" : d.codLocal) + "/" + d.fechaEmision.toLocalDate() + "/"
                    + d.kind.folder + "/pdf/" + d.numeroCompleto() + ".pdf";
            storage.put(Disk.PRIVATE, path, render(saleData(d, tenant), PdfFormat.A4));
            documents.update(d, Map.of("pdf_path", path));
            d.pdfPath = path;
        } catch (RuntimeException e) {
            log.warn("pdf.store.failed tipo={} id={} error={}", d.kind.tipoDocumento, d.id, e.getMessage());
        }
    }

    public void storeGuide(long id, Tenant tenant) {
        if (!autoGenerate) {
            return;
        }
        try {
            Map<String, Object> g = guides.findById(id).orElseThrow();
            String numero = g.get("serie") + "-" + String.format("%06d", ((Number) g.get("correlativo")).longValue());
            String path = tenant.getRuc() + "/" + g.get("cod_local") + "/" + ((LocalDateTime) g.get("fecha_emision")).toLocalDate()
                    + "/guias/pdf/" + numero + ".pdf";
            storage.put(Disk.PRIVATE, path, render(mapper.guide(g, tenant), PdfFormat.A4));
            guides.update(id, Map.of("pdf_path", path));
        } catch (RuntimeException e) {
            log.warn("pdf.store.failed tipo=guia id={} error={}", id, e.getMessage());
        }
    }

    public void storeSettlement(long id, Tenant tenant) {
        if (!autoGenerate) {
            return;
        }
        try {
            Map<String, Object> s = settlements.findById(id).orElseThrow();
            String numero = s.get("serie") + "-" + String.format("%08d", ((Number) s.get("correlativo")).longValue());
            String path = tenant.getRuc() + "/" + s.get("cod_local") + "/" + ((LocalDateTime) s.get("fecha_emision")).toLocalDate()
                    + "/liquidaciones_compra/pdf/" + numero + ".pdf";
            storage.put(Disk.PRIVATE, path, render(mapper.settlement(s, settlements.items(id), tenant), PdfFormat.A4));
            settlements.update(id, Map.of("pdf_path", path));
        } catch (RuntimeException e) {
            log.warn("pdf.store.failed tipo=liquidacion id={} error={}", id, e.getMessage());
        }
    }
}
