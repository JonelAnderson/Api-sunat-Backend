package pe.facturacion.api.modules.admin.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP de la RFC 6238 (HOTP de la RFC 4226 sobre el tiempo): HMAC-SHA1, 6 dígitos, pasos de 30 s y una ventana
 * de ±1 paso para el desfase del reloj del teléfono. Sin estado: la protección contra reutilización del mismo código
 * la hace quien llama con el paso que devuelve {@link #matchStep}.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    public static final int WINDOW = 1;
    /** 160 bits, el tamaño recomendado por la RFC 4226 para HMAC-SHA1. */
    public static final int SECRET_BYTES = 20;
    public static final String ISSUER = "API SUNAT";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    private Totp() {
    }

    /** Clave nueva en Base32 (sin relleno). */
    public static String newSecret() {
        byte[] key = new byte[SECRET_BYTES];
        RANDOM.nextBytes(key);
        return Base32.encode(key);
    }

    /** Paso de tiempo T = floor(unix / 30). */
    public static long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
    }

    /** Código de {@code digits} cifras para el paso {@code step} (RFC 4226 §5.3, truncado dinámico). */
    public static String code(byte[] key, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % POW10[digits];
            return String.format("%0" + digits + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 no disponible", e);
        }
    }

    public static String code(String base32Secret, Instant now) {
        return code(Base32.decode(base32Secret), step(now), DIGITS);
    }

    /**
     * Paso que corresponde al código dentro de la ventana ±1, o {@code -1} si no coincide. Compara en tiempo constante
     * y recorre siempre los tres pasos.
     */
    public static long matchStep(String base32Secret, String code, Instant now) {
        String c = normalize(code);
        if (c == null) {
            return -1;
        }
        byte[] key = Base32.decode(base32Secret);
        long current = step(now);
        long found = -1;
        for (int d = -WINDOW; d <= WINDOW; d++) {
            byte[] expected = code(key, current + d, DIGITS).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, c.getBytes(StandardCharsets.US_ASCII)) && found < 0) {
                found = current + d;
            }
        }
        return found;
    }

    public static boolean verify(String base32Secret, String code, Instant now) {
        return matchStep(base32Secret, code, now) >= 0;
    }

    /** Quita espacios; null si no son exactamente 6 dígitos. */
    static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String c = code.replace(" ", "").trim();
        return c.matches("\\d{" + DIGITS + "}") ? c : null;
    }

    /** {@code otpauth://totp/API%20SUNAT:{correo}?secret=…&issuer=API%20SUNAT} (formato de Google Authenticator). */
    public static String authenticatorUri(String email, String base32Secret) {
        String issuer = encode(ISSUER);
        return "otpauth://totp/" + issuer + ":" + encode(email) + "?secret=" + base32Secret + "&issuer=" + issuer;
    }

    /** Clave agrupada de 4 en 4 para escribirla a mano. */
    public static String formatSharedKey(String base32Secret) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base32Secret.length(); i += 4) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(base32Secret, i, Math.min(base32Secret.length(), i + 4));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
