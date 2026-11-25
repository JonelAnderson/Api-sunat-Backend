package pe.facturacion.api.modules.client;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Todo acceso exige el {@code tenantId}. */
public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Client> findByTenantIdAndTipoDocumentoAndNumeroDocumento(Long tenantId, String tipo, String numero);

    /** LIKE sin distinguir mayúsculas ni tildes (como utf8mb4_unicode_ci) y orden por razón social. */
    @Query(value = "SELECT * FROM clients WHERE tenant_id = :tenantId AND (CAST(:pattern AS text) IS NULL "
            + "OR f_unaccent_lower(razon_social) LIKE f_unaccent_lower(CAST(:pattern AS text)) "
            + "OR f_unaccent_lower(numero_documento) LIKE f_unaccent_lower(CAST(:pattern AS text))) "
            + "ORDER BY razon_social COLLATE \"es_ci\", id LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Client> search(@Param("tenantId") Long tenantId, @Param("pattern") String pattern,
            @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = "SELECT count(*) FROM clients WHERE tenant_id = :tenantId AND (CAST(:pattern AS text) IS NULL "
            + "OR f_unaccent_lower(razon_social) LIKE f_unaccent_lower(CAST(:pattern AS text)) "
            + "OR f_unaccent_lower(numero_documento) LIKE f_unaccent_lower(CAST(:pattern AS text)))", nativeQuery = true)
    long countSearch(@Param("tenantId") Long tenantId, @Param("pattern") String pattern);
}
