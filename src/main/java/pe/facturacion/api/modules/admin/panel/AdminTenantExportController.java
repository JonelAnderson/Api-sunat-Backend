package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.report.ExportController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;

/**
 * Exportación ZIP de los comprobantes de una empresa desde el panel ({@code GET tenants/{id}/comprobantes/exportar-zip}).
 * Los filtros, la validación y la elección de archivos son los de {@code GET /api/v1/comprobantes/exportar-zip}
 * ({@link ExportController#select}); el panel agrega topes ({@link AdminZipLimits}: 31 días, 2 000 archivos, 200 MB)
 * que se comprueban <b>antes</b> de escribir y arma el ZIP en streaming hacia la respuesta, leyendo cada archivo del
 * disco sin cargarlo entero (corrige H12 en esta ruta; {@code /api/v1} no cambia). Cada exportación se audita.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · comprobantes de la empresa")
public class AdminTenantExportController {

    private static final Logger log = LoggerFactory.getLogger(AdminTenantExportController.class);

    private final AdminTenantViews views;
    private final ExportController export;
    private final FileStorage storage;

    public AdminTenantExportController(AdminTenantViews views, ExportController export, FileStorage storage) {
        this.views = views;
        this.export = export;
        this.storage = storage;
    }

    @Operation(summary = "ZIP de XML y/o PDF (fecha_desde, fecha_hasta: máx. 31 días; tipo xml|pdf|ambos; documentos; sucursal_id; estado)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/comprobantes/exportar-zip")
    public void exportZip(@PathVariable long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Tenant tenant = views.tenant(id);
        Input in = views.input(request);
        String rangeError = AdminZipLimits.rangeError(Php.toStr(in.get("fecha_desde")), Php.toStr(in.get("fecha_hasta")));
        if (rangeError != null) {
            new AdminValidation().add("fecha_hasta", rangeError).throwIfAny();
        }
        ExportController.Selection s = export.select(tenant, in);
        if (s.error() != null) {
            throw AdminV1Responses.error(s.error().getStatusCode().value(), s.error().getBody());
        }
        List<Path> paths = new ArrayList<>();
        long bytes = 0;
        for (String[] f : s.files()) {
            Path p = storage.resolve(Disk.PRIVATE, f[0]);
            paths.add(p);
            bytes += Files.size(p);
        }
        String sizeError = AdminZipLimits.sizeError(paths.size(), bytes);
        if (sizeError != null) {
            throw AdminException.fail(sizeError);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("vista", "comprobantes.exportar-zip");
        meta.put("archivo", "zip");
        meta.put("fecha_desde", s.desde().toString());
        meta.put("fecha_hasta", s.hasta().toString());
        meta.put("tipo", in.get("tipo") == null ? "xml" : Php.toStr(in.get("tipo")));
        if (in.get("documentos") != null) {
            meta.put("documentos", Php.toStr(in.get("documentos")));
        }
        meta.put("archivos", paths.size());
        meta.put("bytes", bytes);
        views.auditExport(request, tenant, meta);

        response.setStatus(200);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + s.fileName() + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
            for (int i = 0; i < paths.size(); i++) {
                zip.putNextEntry(new ZipEntry(s.files().get(i)[1]));
                Files.copy(paths.get(i), zip);
                zip.closeEntry();
            }
        } catch (IOException | UncheckedIOException e) {
            // La respuesta ya empezó: solo queda registrar (el cliente recibe un ZIP incompleto)
            log.warn("admin.exportar_zip.interrumpido empresa={} error={}", tenant.getId(), e.getClass().getSimpleName());
        }
    }
}
