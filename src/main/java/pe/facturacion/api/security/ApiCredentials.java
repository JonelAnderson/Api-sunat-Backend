package pe.facturacion.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Generación de credenciales con el mismo formato que API-PRO. */
public final class ApiCredentials {

    private static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiCredentials() {
    }

    /** {@code Str::random($length)}: alfanumérico. */
    public static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }

    /** api_key: {@code Str::random(64)}. */
    public static String newApiKey() {
        return random(64);
    }

    /** api_secret al crear la empresa: {@code hash('sha256', Str::random(64))} (64 hex). */
    public static String newApiSecret() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(random(64).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
