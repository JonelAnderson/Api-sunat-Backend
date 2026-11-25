package pe.facturacion.api.shared.json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

/**
 * Escribe las respuestas JSON con el formato exacto de Laravel ({@link PhpJson}) y
 * {@code Content-Type: application/json} sin charset, como API-PRO.
 * Solo se usa para escritura: la lectura de cuerpos la hace {@code RequestInput}.
 */
public class PhpJsonHttpMessageConverter extends AbstractHttpMessageConverter<Object> {

    public PhpJsonHttpMessageConverter() {
        super(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return Map.class.isAssignableFrom(clazz) || clazz.isRecord() || java.util.Collection.class.isAssignableFrom(clazz);
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return false;
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) throws HttpMessageNotReadableException {
        throw new HttpMessageNotReadableException("Lectura no soportada", inputMessage);
    }

    @Override
    protected void addDefaultHeaders(HttpHeaders headers, Object o, MediaType contentType) throws IOException {
        headers.setContentType(MediaType.APPLICATION_JSON);
    }

    @Override
    protected void writeInternal(Object value, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        byte[] bytes = PhpJson.encode(value).getBytes(StandardCharsets.UTF_8);
        outputMessage.getHeaders().setContentLength(bytes.length);
        try (OutputStream os = outputMessage.getBody()) {
            os.write(bytes);
        }
    }
}
