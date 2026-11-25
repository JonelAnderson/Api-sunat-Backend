package pe.facturacion.api.modules.admin.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Códigos de recuperación del MFA: 10 de un solo uso con la forma {@code xxxxx-xxxxx} (sin caracteres que se
 * confundan). Solo se guarda el SHA-256 en hexadecimal de cada uno, como arreglo JSON en
 * {@code users.two_factor_recovery_codes}.
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    /** Códigos nuevos en claro (se muestran una sola vez). */
    public static List<String> generate() {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < COUNT) {
            StringBuilder sb = new StringBuilder(11);
            for (int i = 0; i < 10; i++) {
                if (i == 5) {
                    sb.append('-');
                }
                sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            codes.add(sb.toString());
        }
        return new ArrayList<>(codes);
    }

    /** Minúsculas, sin espacios ni guiones: el usuario puede escribirlo como quiera. */
    static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
    }

    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalize(code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public static List<String> hashAll(List<String> codes) {
        return codes.stream().map(RecoveryCodes::hash).toList();
    }

    /** Arreglo JSON de hashes para la columna. */
    public static String toJson(List<String> hashes) {
        return PhpJson.encodeUnescaped(hashes);
    }

    /** Hashes guardados (vacío si la columna está vacía o no es un arreglo). */
    public static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Object decoded = PhpJson.decode(json);
        if (!(decoded instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && s.matches("[0-9a-f]{64}")) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Consume el código: devuelve los hashes restantes sin el usado, o vacío si el código no está. Compara todos los
     * hashes en tiempo constante.
     */
    public static Optional<List<String>> consume(List<String> hashes, String code) {
        if (normalize(code).length() != 10) {
            return Optional.empty();
        }
        byte[] candidate = hash(code).getBytes(StandardCharsets.US_ASCII);
        int index = -1;
        for (int i = 0; i < hashes.size(); i++) {
            if (MessageDigest.isEqual(candidate, hashes.get(i).getBytes(StandardCharsets.US_ASCII)) && index < 0) {
                index = i;
            }
        }
        if (index < 0) {
            return Optional.empty();
        }
        List<String> remaining = new ArrayList<>(hashes);
        remaining.remove(index);
        return Optional.of(remaining);
    }
}
