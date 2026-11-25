package pe.facturacion.api.shared.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Mapea columnas {@code text} cifradas a {@link EncryptedString}. */
@Converter
public class EncryptedStringConverter implements AttributeConverter<EncryptedString, String> {

    @Override
    public String convertToDatabaseColumn(EncryptedString attribute) {
        return attribute == null ? null : attribute.stored();
    }

    @Override
    public EncryptedString convertToEntityAttribute(String dbData) {
        return EncryptedString.ofStored(dbData);
    }
}
