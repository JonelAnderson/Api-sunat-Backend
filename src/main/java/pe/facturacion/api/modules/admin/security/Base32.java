package pe.facturacion.api.modules.admin.security;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Base32 de la RFC 4648 (alfabeto {@code A-Z2-7}), el formato de las claves TOTP de las apps autenticadoras.
 * {@link #encode} no rellena con {@code =} (como lo esperan las apps); {@link #decode} acepta minúsculas, espacios,
 * guiones y relleno.
 */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32() {
    }

    public static String encode(byte[] data) {
        return encode(data, false);
    }

    /** Codifica; con {@code padding} completa con {@code =} hasta múltiplo de 8 (vectores de la RFC). */
    public static String encode(byte[] data, boolean padding) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5 + 8);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        if (padding) {
            while (out.length() % 8 != 0) {
                out.append('=');
            }
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException si hay un carácter fuera del alfabeto. */
    public static byte[] decode(String text) {
        String clean = text == null ? "" : text.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= '2' && c <= '7') {
                v = c - '2' + 26;
            } else {
                throw new IllegalArgumentException("Carácter no válido en Base32");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
