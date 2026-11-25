package pe.facturacion.api.shared.crypto;

/**
 * Secreto cifrado en BD que se descifra solo bajo demanda (como el cast {@code encrypted} de
 * Laravel, que lanza {@code DecryptException} al leer el atributo y no al cargar el modelo).
 */
public final class EncryptedString {

    private final String stored;

    private EncryptedString(String stored) {
        this.stored = stored;
    }

    public static EncryptedString ofStored(String stored) {
        return stored == null ? null : new EncryptedString(stored);
    }

    public static EncryptedString ofPlain(String plain) {
        return plain == null ? null : new EncryptedString(SecretCipher.get().encrypt(plain));
    }

    /** Valor en claro; lanza {@link SecretCipher.DecryptException} si no se puede descifrar. */
    public String reveal() {
        return SecretCipher.get().decrypt(stored);
    }

    public String stored() {
        return stored;
    }

    @Override
    public String toString() {
        return "***";
    }
}
