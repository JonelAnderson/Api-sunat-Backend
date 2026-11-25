package pe.facturacion.api.modules.serie;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Todo acceso exige el {@code tenantId}. */
public interface SerieRepository extends JpaRepository<Serie, Long> {

    Optional<Serie> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Serie> findByTenantIdAndTipoDocumentoAndSerie(Long tenantId, String tipoDocumento, String serie);

    @Query("SELECT s FROM Serie s WHERE s.tenantId = :tenantId "
            + "AND (:soloActivas = false OR s.active = true) "
            + "AND (:sucursalId IS NULL OR s.sucursalId = :sucursalId) "
            + "AND (:tipo IS NULL OR s.tipoDocumento = :tipo) "
            + "ORDER BY s.tipoDocumento, s.serie")
    List<Serie> list(@Param("tenantId") Long tenantId, @Param("soloActivas") boolean soloActivas,
            @Param("sucursalId") Long sucursalId, @Param("tipo") String tipo);

    /**
     * {@code Serie::resolveCorrelativo()}: {@code SELECT ... FOR UPDATE} dentro de la transacción
     * del comprobante, para que dos emisiones simultáneas nunca obtengan el mismo número.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Serie s WHERE s.id = :id")
    Optional<Serie> lockById(@Param("id") Long id);
}
