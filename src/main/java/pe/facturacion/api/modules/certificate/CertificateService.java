package pe.facturacion.api.modules.certificate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Conversión y validación de certificados digitales, equivalente a
 * {@code App\Services\CertificateService} de API-PRO (mismos mensajes). Siempre produce un
 * PEM con el certificado y la clave privada sin frase de paso, y rechaza en el momento de la
 * subida los archivos que no sirven para firmar.
 */
@Service
public class CertificateService {

    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);
    private static final Pattern PEM_BLOCK = Pattern.compile("-----BEGIN ([A-Z ]+)-----\\s*(.*?)\\s*-----END \\1-----", Pattern.DOTALL);

    /** Error con mensaje para el usuario (API-PRO: RuntimeException → 422). */
    public static class CertificateException extends RuntimeException {
        public CertificateException(String message) {
            super(message);
        }
    }

    public record Material(X509Certificate certificate, PrivateKey privateKey) {
    }

    public String convertToPem(byte[] content, String originalExtension, String password) {
        String extension = originalExtension == null ? "" : originalExtension.toLowerCase();
        if (extension.equals("pem") || extension.equals("cer") || extension.equals("crt")) {
            String text = new String(content, StandardCharsets.ISO_8859_1);
            if (text.contains("-----BEGIN")) {
                return asegurarClavePrivada(new String(content, StandardCharsets.UTF_8));
            }
            String pem = derToPem(content);
            if (pem != null) {
                return asegurarClavePrivada(pem);
            }
            throw new CertificateException("El archivo no contiene un certificado PEM válido.");
        }
        if (extension.equals("pfx") || extension.equals("p12")) {
            return asegurarClavePrivada(pfxToPem(content, password == null ? "" : password));
        }
        throw new CertificateException("Formato de certificado no soportado: ." + extension);
    }

    /** Lee certificado y clave de un PEM ya validado (para firmar). */
    public Material parse(String pem) {
        X509Certificate cert = readCertificate(pem);
        PrivateKey key = readPrivateKey(pem);
        if (cert == null || key == null) {
            throw new CertificateException("El archivo no contiene un certificado digital válido.");
        }
        return new Material(cert, key);
    }

    private String asegurarClavePrivada(String pem) {
        if (readCertificate(pem) == null) {
            throw new CertificateException("El archivo no contiene un certificado digital válido.");
        }
        if (readPrivateKey(pem) == null) {
            if (pem.contains("ENCRYPTED PRIVATE KEY") || pem.contains("Proc-Type: 4,ENCRYPTED")) {
                throw new CertificateException("La clave privada del archivo está protegida con una contraseña propia y no se puede usar "
                        + "para firmar. Al convertir el .p12 hay que agregar -nodes: "
                        + "openssl pkcs12 -in certificado.p12 -out certificado.pem -nodes");
            }
            throw new CertificateException("El archivo tiene el certificado pero NO la clave privada, así que no sirve para firmar "
                    + "comprobantes. Si convertiste tu .p12, la conversión descartó la clave: repetila con "
                    + "openssl pkcs12 -in certificado.p12 -out certificado.pem -nodes (sin -nokeys). "
                    + "También podés subir el .p12 tal cual, con su contraseña.");
        }
        return pem;
    }

    private String pfxToPem(byte[] original, String password) {
        byte[] pfx = normalizarPfxBinario(original);
        String nativeError;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfx), password.toCharArray());
            StringBuilder certs = new StringBuilder();
            String keyPem = null;
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (ks.isKeyEntry(alias) && keyPem == null) {
                    Key k = ks.getKey(alias, password.toCharArray());
                    if (k instanceof PrivateKey pk) {
                        keyPem = pem("PRIVATE KEY", pk.getEncoded());
                        Certificate c = ks.getCertificate(alias);
                        if (c != null) {
                            certs.insert(0, pem("CERTIFICATE", c.getEncoded()));
                        }
                    }
                } else if (ks.isCertificateEntry(alias) && certs.isEmpty()) {
                    certs.append(pem("CERTIFICATE", ks.getCertificate(alias).getEncoded()));
                }
            }
            String out = certs + (keyPem == null ? "" : keyPem);
            if (!out.isEmpty()) {
                return out;
            }
            throw new CertificateException("El archivo PFX no contiene certificado ni clave privada.");
        } catch (CertificateException e) {
            throw e;
        } catch (Exception e) {
            nativeError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        // No se registra la contraseña.
        log.warn("No se pudo leer un PFX: bytes={}, error={}", pfx.length, nativeError);

        if (new String(original, StandardCharsets.ISO_8859_1).contains("ï¿½")) {
            throw new CertificateException("El archivo del certificado llegó dañado: fue tratado como texto y perdió parte de su "
                    + "contenido binario (se detectaron caracteres de reemplazo \"�\"). Esto ocurre al abrir y "
                    + "guardar el .pfx con un editor de texto, al copiar y pegar su contenido, o al enviarlo como "
                    + "texto en lugar de archivo. Suba el archivo .pfx ORIGINAL tal como lo obtuvo de SUNAT, "
                    + "adjuntándolo como archivo (no pegue su contenido).");
        }
        throw new CertificateException("No se pudo leer el archivo PFX. Verifique que el password sea correcto. "
                + "Si el password es correcto, el certificado puede usar un formato antiguo "
                + "que el servidor no pudo abrir; vuelva a exportarlo desde SUNAT o conviértalo con "
                + "\"openssl pkcs12 -legacy -in cert.pfx -out cert.pem -nodes\".");
    }

    /** Un PKCS12 es DER (empieza con 0x30); si llegó como texto base64 (o con BOM) se decodifica. */
    static byte[] normalizarPfxBinario(byte[] content) {
        if (content.length > 0 && content[0] == 0x30) {
            return content;
        }
        byte[] c = content;
        byte[][] boms = {{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, {(byte) 0xFF, (byte) 0xFE}, {(byte) 0xFE, (byte) 0xFF}};
        for (byte[] bom : boms) {
            if (startsWith(c, bom)) {
                c = java.util.Arrays.copyOfRange(c, bom.length, c.length);
                break;
            }
        }
        if (c.length > 0 && c[0] == 0x30) {
            return c;
        }
        String text = new String(c, StandardCharsets.ISO_8859_1).replaceAll("-----[^-]*-----", "");
        text = text.replaceAll("[^A-Za-z0-9+/_=-]", "").replace('-', '+').replace('_', '/').replaceAll("=+$", "");
        if (text.length() >= 100) {
            try {
                while (text.length() % 4 != 0) {
                    text += "=";
                }
                byte[] decoded = Base64.getDecoder().decode(text);
                if (decoded.length > 0 && decoded[0] == 0x30) {
                    return decoded;
                }
            } catch (IllegalArgumentException ignored) {
                // no era base64
            }
        }
        return content;
    }

    private static String derToPem(byte[] der) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate c = cf.generateCertificate(new ByteArrayInputStream(der));
            return pem("CERTIFICATE", c.getEncoded());
        } catch (Exception e) {
            return null;
        }
    }

    private static X509Certificate readCertificate(String pem) {
        Matcher m = PEM_BLOCK.matcher(pem);
        while (m.find()) {
            if ("CERTIFICATE".equals(m.group(1)) || "TRUSTED CERTIFICATE".equals(m.group(1))) {
                try {
                    byte[] der = Base64.getMimeDecoder().decode(m.group(2));
                    return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static PrivateKey readPrivateKey(String pem) {
        Matcher m = PEM_BLOCK.matcher(pem);
        while (m.find()) {
            String type = m.group(1);
            String body = m.group(2);
            if (body.contains("Proc-Type: 4,ENCRYPTED")) {
                return null;
            }
            try {
                if ("PRIVATE KEY".equals(type)) {
                    return pkcs8(Base64.getMimeDecoder().decode(body));
                }
                if ("RSA PRIVATE KEY".equals(type)) {
                    return pkcs8(wrapPkcs1(Base64.getMimeDecoder().decode(body)));
                }
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static PrivateKey pkcs8(byte[] der) throws Exception {
        for (String alg : new String[] {"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (java.security.spec.InvalidKeySpecException ignored) {
                // probar el siguiente algoritmo
            }
        }
        throw new java.security.spec.InvalidKeySpecException("Clave no soportada");
    }

    /** Envuelve una clave RSA PKCS#1 en PKCS#8 (PrivateKeyInfo con rsaEncryption). */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algId = {0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octet = derTlv((byte) 0x04, pkcs1);
        byte[] inner = concat(version, algId, octet);
        return derTlv((byte) 0x30, inner);
    }

    private static byte[] derTlv(byte tag, byte[] value) {
        int len = value.length;
        byte[] lenBytes;
        if (len < 0x80) {
            lenBytes = new byte[] {(byte) len};
        } else if (len < 0x100) {
            lenBytes = new byte[] {(byte) 0x81, (byte) len};
        } else if (len < 0x10000) {
            lenBytes = new byte[] {(byte) 0x82, (byte) (len >> 8), (byte) len};
        } else {
            lenBytes = new byte[] {(byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) len};
        }
        return concat(new byte[] {tag}, lenBytes, value);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static String pem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }
}
