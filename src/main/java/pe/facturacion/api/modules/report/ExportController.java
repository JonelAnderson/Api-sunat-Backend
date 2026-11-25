package pe.facturacion.api.modules.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;

/**
 * {@code GET /api/v1/comprobantes/exportar-zip} ({@code ExportController::zip}): XML y/o PDF
 * guardados de facturas, boletas y notas en un ZIP con carpetas {@code tipo/AAAA-MM}.
 *
 * <p>Correcciones: las fechas deben ser AAAA-MM-DD (API-PRO aceptaba cualquier texto que Carbon
 * entendiera, como "tomorrow") y el nombre del archivo se arma con las fechas ya validadas, no
 * con el texto recibido.
 */
@RestController
@Tag(name = "Exportación")
public class ExportController {

    private static final Map<String, DocumentKind> TIPOS = new LinkedHashMap<>();

    static {
        TIPOS.put("facturas", DocumentKind.FACTURA);
        TIPOS.put("boletas", DocumentKind.BOLETA);
        TIPOS.put("notas-credito", DocumentKind.NOTA_CREDITO);
        TIPOS.put("notas-debito", DocumentKind.NOTA_DEBITO);
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final FileStorage storage;

    public ExportController(NamedParameterJdbcTemplate jdbc, FileStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Operation(summary = "Exportar XML y/o PDF de comprobantes en un ZIP (fecha_desde, fecha_hasta, tipo, documentos, sucursal_id, estado)")
    @GetMapping("/api/v1/comprobantes/exportar-zip")
    public ResponseEntity<Object> zip(HttpServletRequest http) throws IOException {
        Selection s = select(ApiContext.requireTenant(http), Input.of(http));
        if (s.error() != null) {
            return s.error();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String[] a : s.files()) {
                zip.putNextEntry(new ZipEntry(a[1]));
                zip.write(storage.get(Disk.PRIVATE, a[0]));
                zip.closeEntry();
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + s.fileName())
                .body(out.toByteArray());
    }

    /**
     * Archivos que irían en el ZIP: {@code [ruta en el disco privado, nombre dentro del ZIP]}, en el orden de
     * {@code /api/v1}, con el nombre del ZIP; o el error de validación (422 / 404) tal cual lo responde la API.
     */
    public record Selection(ResponseEntity<Object> error, List<String[]> files, String fileName, LocalDate desde, LocalDate hasta) {

        static Selection fail(ResponseEntity<Object> error) {
            return new Selection(error, List.of(), null, null, null);
        }
    }

    /**
     * Valida los filtros y elige los archivos de la empresa indicada (lo usa también el panel de administración, que
     * arma el ZIP en streaming y con topes). No lee el contenido de los archivos.
     */
    public Selection select(Tenant t, Input in) {
        String desdeRaw = Php.toStr(in.get("fecha_desde"));
        String hastaRaw = Php.toStr(in.get("fecha_hasta"));
        if (desdeRaw.isEmpty() || hastaRaw.isEmpty()) {
            return Selection.fail(ApiResponse.error("Los parámetros fecha_desde y fecha_hasta son requeridos (formato Y-m-d).", 422));
        }
        LocalDate desde = Reports.parseDate(desdeRaw);
        LocalDate hasta = Reports.parseDate(hastaRaw);
        if (desde == null || hasta == null) {
            return Selection.fail(ApiResponse.error("Formato de fecha inválido. Use Y-m-d (ej: 2026-05-01).", 422));
        }
        if (desde.isAfter(hasta)) {
            return Selection.fail(ApiResponse.error("fecha_desde no puede ser mayor que fecha_hasta.", 422));
        }
        // API-PRO compara desde 00:00:00 hasta 23:59:59: 366 días de diferencia ya superan el máximo
        if (ChronoUnit.DAYS.between(desde, hasta) >= 366) {
            return Selection.fail(ApiResponse.error("El rango máximo permitido es 366 días.", 422));
        }
        String tipo = in.get("tipo") == null ? "xml" : Php.toStr(in.get("tipo"));
        if (!List.of("xml", "pdf", "ambos").contains(tipo)) {
            return Selection.fail(ApiResponse.error("El parámetro tipo debe ser xml, pdf o ambos.", 422));
        }
        String documentos = in.get("documentos") == null ? "facturas,boletas,notas-credito,notas-debito" : Php.toStr(in.get("documentos"));
        List<String> tipos = new ArrayList<>();
        for (String s : documentos.split(",", -1)) {
            if (TIPOS.containsKey(s.trim())) {
                tipos.add(s.trim());
            }
        }
        if (tipos.isEmpty()) {
            return Selection.fail(ApiResponse.error("Ningún tipo de documento válido especificado. Use: " + String.join(", ", TIPOS.keySet()), 422));
        }
        Object sucursal = in.get("sucursal_id");
        String estado = in.get("estado") == null ? "todos" : Php.toStr(in.get("estado"));

        List<String[]> archivos = new ArrayList<>();
        for (String carpeta : tipos) {
            StringBuilder sql = new StringBuilder("SELECT id, serie, correlativo, fecha_emision, xml_path, pdf_path FROM ").append(TIPOS.get(carpeta).table)
                    .append(" WHERE tenant_id = :t AND deleted_at IS NULL AND fecha_emision BETWEEN :d AND :h");
            // API-PRO no ordena: la BD devuelve el orden del índice (tenant_id, fecha_emision)
            MapSqlParameterSource p = new MapSqlParameterSource("t", t.getId()).addValue("d", desde.atStartOfDay()).addValue("h", hasta.atTime(23, 59, 59));
            if (Php.truthy(sucursal)) {
                sql.append(" AND sucursal_id = :s");
                p.addValue("s", (long) Input.intval(sucursal));
            }
            if (!"todos".equals(estado)) {
                sql.append(" AND sunat_status = :e");
                p.addValue("e", estado);
            }
            for (Map<String, Object> d : jdbc.queryForList(sql.append(" ORDER BY fecha_emision, id").toString(), p)) {
                String numero = Reports.numero(d.get("serie"), d.get("correlativo"), 6);
                String folder = carpeta + "/" + ((Timestamp) d.get("fecha_emision")).toLocalDateTime().toString().substring(0, 7);
                if (!"pdf".equals(tipo) && existe(d.get("xml_path"))) {
                    archivos.add(new String[] {(String) d.get("xml_path"), folder + "/" + numero + ".xml"});
                }
                if (!"xml".equals(tipo) && existe(d.get("pdf_path"))) {
                    archivos.add(new String[] {(String) d.get("pdf_path"), folder + "/" + numero + ".pdf"});
                }
            }
        }
        if (archivos.isEmpty()) {
            return Selection.fail(ApiResponse.error("No se encontraron archivos para los criterios especificados. "
                    + "Verifique que los documentos tengan XML/PDF generados y estén dentro del rango de fechas.", 404));
        }
        String nombre = "comprobantes_" + t.getRuc() + "_" + desde.toString().replace("-", "") + "_" + hasta.toString().replace("-", "") + "_" + tipo + ".zip";
        return new Selection(null, List.copyOf(archivos), nombre, desde, hasta);
    }

    private boolean existe(Object path) {
        return path instanceof String s && !s.isEmpty() && storage.exists(Disk.PRIVATE, s);
    }
}
