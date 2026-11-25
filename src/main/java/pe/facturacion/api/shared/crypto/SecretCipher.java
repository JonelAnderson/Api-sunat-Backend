package pe.facturacion.api.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import pe.facturacion.api.config.AppProperties;

/**
 * Cifrado reversible de secretos en BD (clave SOL, api_secret, client_secret, contraseña del
 * certificado, tokens). Equivale al cast {@code encrypted} de Laravel en API-PRO, pero con
 * AES-256-GCM y clave propia ({@code APP_ENCRYPTION_KEY}); ver docs/DIFERENCIAS.md.
 *
 * <p>Formato: {@code v1:} + base64(iv[12] || ciphertext || tag[16]).
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private static volatile SecretCipher instance;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public SecretCipher(AppProperties props) {
        this(props.encryptionKey());
    }

    public SecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY no está definida (genérala con: openssl rand -base64 32)");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY debe ser de 32 bytes (AES-256) codificados en base64");
        }
        this.key = new SecretKeySpec(raw, "AES");
        instance = this;
    }

    /** Acceso para los conversores JPA, que Hibernate instancia fuera del contexto de Spring. */
    public static SecretCipher get() {
        SecretCipher c = instance;
        if (c == null) {
            throw new IllegalStateException("SecretCipher no inicializado");
        }
        return c;
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el secreto", e);
        }
    }

    /** @throws DecryptException si el valor no es un sobre válido para la clave actual. */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new DecryptException("Formato de secreto cifrado no reconocido");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DecryptException("No se pudo descifrar el secreto");
        }
    }

    /** Comparación en tiempo constante ({@code hash_equals} de PHP). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static class DecryptException extends RuntimeException {
        public DecryptException(String message) {
            super(message);
        }
    }
}
