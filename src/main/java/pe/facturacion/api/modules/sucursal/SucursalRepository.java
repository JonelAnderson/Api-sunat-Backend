package pe.facturacion.api.modules.sucursal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Todo acceso exige el {@code tenantId}: aislamiento por empresa en la capa de repositorio. */
public interface SucursalRepository extends JpaRepository<Sucursal, Long> {

    Optional<Sucursal> findByIdAndTenantId(Long id, Long tenantId);

    List<Sucursal> findByTenantIdAndIdIn(Long tenantId, Collection<Long> ids);

    boolean existsByTenantIdAndCodLocal(Long tenantId, String codLocal);

    long countByTenantId(Long tenantId);

    /** Orden {@code is_principal desc, nombre} con la colación de API-PRO (sin distinguir mayúsculas ni tildes). */
    @Query(value = "SELECT * FROM sucursales WHERE tenant_id = :tenantId ORDER BY is_principal DESC, nombre COLLATE \"es_ci\", id",
            nativeQuery = true)
    List<Sucursal> listForTenant(@Param("tenantId") Long tenantId);

    @Modifying
    @Query("UPDATE Sucursal s SET s.principal = false WHERE s.tenantId = :tenantId AND s.id <> :exceptId")
    void clearPrincipal(@Param("tenantId") Long tenantId, @Param("exceptId") Long exceptId);
}
