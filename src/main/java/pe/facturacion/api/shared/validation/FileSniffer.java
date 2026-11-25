package pe.facturacion.api.shared.validation;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

/** Extensión según la firma del contenido (como {@code UploadedFile::guessExtension()}). */
public final class FileSniffer {

    private FileSniffer() {
    }

    public static String guessExtension(MultipartFile f) {
        byte[] h = new byte[16];
        int n;
        try (InputStream in = f.getInputStream()) {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            n = 0;
        }
        if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "png";
        }
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (n >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') {
            return "gif";
        }
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "webp";
        }
        if (n >= 2 && h[0] == 'B' && h[1] == 'M') {
            return "bmp";
        }
        if (n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') {
            return "pdf";
        }
        String head = new String(h, 0, Math.max(n, 0), java.nio.charset.StandardCharsets.ISO_8859_1).trim();
        if (head.startsWith("<svg") || head.startsWith("<?xml")) {
            return "svg";
        }
        String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** Extensión del nombre original ({@code getClientOriginalExtension()}). */
    public static String clientExtension(MultipartFile f) {
        String name = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
