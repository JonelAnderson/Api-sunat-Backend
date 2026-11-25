package pe.facturacion.api.modules.document;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;

/**
 * {@code DocumentStorageService}: XML, CDR y PDF en el disco privado con la ruta
 * {@code {ruc}/{cod_local}/{fecha_emision}/{carpeta}/{xml|cdr|pdf}/{archivo}}.
 * Nunca hay URL pública: se descargan solo por los endpoints autenticados.
 */
@Service
public class DocumentStorage {

    private final FileStorage storage;
    private final SaleDocumentRepository documents;

    public DocumentStorage(FileStorage storage, SaleDocumentRepository documents) {
        this.storage = storage;
        this.documents = documents;
    }

    public String storeXml(SaleDocument d, Tenant tenant, String xml) {
        String path = buildPath(tenant, d, "xml") + "/" + d.numeroCompleto() + ".xml";
        storage.put(Disk.PRIVATE, path, xml.getBytes(StandardCharsets.UTF_8));
        d.xmlPath = path;
        documents.update(d, Map.of("xml_path", path));
        return path;
    }

    public String storeCdr(SaleDocument d, Tenant tenant, byte[] cdrZip) {
        String path = buildPath(tenant, d, "cdr") + "/R-" + d.numeroCompleto() + ".zip";
        storage.put(Disk.PRIVATE, path, cdrZip);
        d.cdrPath = path;
        documents.update(d, Map.of("cdr_path", path));
        return path;
    }

    public byte[] xmlContent(SaleDocument d) {
        return d.xmlPath != null && storage.exists(Disk.PRIVATE, d.xmlPath) ? storage.get(Disk.PRIVATE, d.xmlPath) : null;
    }

    public byte[] cdrContent(SaleDocument d) {
        return d.cdrPath != null && storage.exists(Disk.PRIVATE, d.cdrPath) ? storage.get(Disk.PRIVATE, d.cdrPath) : null;
    }

    private static String buildPath(Tenant tenant, SaleDocument d, String type) {
        String codLocal = d.codLocal == null ? "0000" : d.codLocal;
        return tenant.getRuc() + "/" + codLocal + "/" + d.fechaEmision.toLocalDate() + "/" + d.kind.folder + "/" + type;
    }
}
