package pe.facturacion.api.shared.http;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.validation.InputSanitizer;

/**
 * Entrada de la petición con la semántica de {@code $request->all()} de Laravel:
 * query string + cuerpo (JSON o formulario) + archivos, saneada con TrimStrings y
 * ConvertEmptyStringsToNull. Un JSON mal formado se trata como cuerpo vacío (como Laravel).
 */
public final class RequestInput {

    public static final String RAW_BODY_ATTR = RequestInput.class.getName() + ".rawBody";

    private RequestInput() {
    }

    public static Map<String, Object> all(HttpServletRequest request) {
        Map<String, Object> input = new LinkedHashMap<>(query(request));
        String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase();
        if (contentType.contains("json")) {
            Map<String, Object> body = PhpJson.decodeObject(rawBody(request));
            if (body != null) {
                input.putAll(body);
            }
        } else if (contentType.startsWith("multipart/") || contentType.startsWith("application/x-www-form-urlencoded")) {
            input.putAll(formFields(request));
            if (request instanceof MultipartHttpServletRequest multipart) {
                for (Map.Entry<String, List<MultipartFile>> e : multipart.getMultiFileMap().entrySet()) {
                    input.put(e.getKey(), e.getValue().size() == 1 ? e.getValue().get(0) : new ArrayList<>(e.getValue()));
                }
            }
        }
        return InputSanitizer.cleanAll(input);
    }

    /** Cuerpo crudo (lo cachea {@link CachedBodyFilter} para que se pueda leer varias veces). */
    public static String rawBody(HttpServletRequest request) {
        Object cached = request.getAttribute(RAW_BODY_ATTR);
        if (cached instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            byte[] bytes = StreamUtils.copyToByteArray(request.getInputStream());
            request.setAttribute(RAW_BODY_ATTR, bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static Map<String, Object> query(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        String qs = request.getQueryString();
        if (qs == null || qs.isEmpty()) {
            return out;
        }
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            putPhpStyle(out, key, value);
        }
        return out;
    }

    private static Map<String, Object> formFields(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        String qs = request.getQueryString() == null ? "" : request.getQueryString();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (qs.contains(name + "=")) {
                continue; // ya viene en la query
            }
            for (String v : request.getParameterValues(name)) {
                putPhpStyle(out, name, v);
            }
        }
        return out;
    }

    /** Claves estilo PHP: {@code a[]=1&a[]=2} y {@code a[b]=1}. */
    @SuppressWarnings("unchecked")
    private static void putPhpStyle(Map<String, Object> out, String key, String value) {
        int br = key.indexOf('[');
        if (br <= 0 || !key.endsWith("]")) {
            out.put(key, value);
            return;
        }
        String base = key.substring(0, br);
        String inner = key.substring(br + 1, key.length() - 1);
        if (inner.isEmpty()) {
            Object existing = out.get(base);
            List<Object> list = existing instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
            list.add(value);
            out.put(base, list);
        } else {
            Object existing = out.get(base);
            Map<String, Object> map = existing instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
            map.put(inner, value);
            out.put(base, map);
        }
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
