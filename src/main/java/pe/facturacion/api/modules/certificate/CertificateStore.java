package pe.facturacion.api.modules.certificate;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;

/**
 * Guarda el PEM (certificado + clave privada) de cada empresa en el disco privado,
 * <b>cifrado en reposo</b> con {@link SecretCipher}. API-PRO lo deja en claro en
 * {@code certificates/{ruc}/cert.pem}; aquí la ruta es {@code certificates/{ruc}/cert.pem.enc}.
 */
@Component
public class CertificateStore {

    private final FileStorage storage;
    private final SecretCipher cipher;

    public CertificateStore(FileStorage storage, SecretCipher cipher) {
        this.storage = storage;
        this.cipher = cipher;
    }

    /** {@code DocumentStorageService::storeCertificate()}: devuelve la ruta relativa guardada en {@code certificate_path}. */
    public String store(Tenant tenant, String pem) {
        String path = "certificates/" + tenant.getRuc() + "/cert.pem.enc";
        storage.put(Disk.PRIVATE, path, cipher.encrypt(pem).getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /** PEM en claro de la empresa o null si no tiene certificado. */
    public String load(Tenant tenant) {
        String path = tenant.getCertificatePath();
        if (path == null || !storage.exists(Disk.PRIVATE, path)) {
            return null;
        }
        return cipher.decrypt(new String(storage.get(Disk.PRIVATE, path), StandardCharsets.UTF_8));
    }
}
