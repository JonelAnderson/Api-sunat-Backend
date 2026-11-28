package pe.facturacion.api.modules.consulta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Los nombres de departamento / provincia / distrito del proveedor se buscan en las tablas de ubigeo sin tildes ni mayúsculas. */
class DocumentLookupNamesTest {

    @Test
    void normaliza_tildes_mayusculas_y_espacios() {
        assertThat(DocumentLookupService.normalize("  San  Martín de Porres ")).isEqualTo("SAN MARTIN DE PORRES");
        assertThat(DocumentLookupService.normalize("Ñuñoa")).isEqualTo(DocumentLookupService.normalize("NUNOA"));
        assertThat(DocumentLookupService.normalize(null)).isEmpty();
    }
}
