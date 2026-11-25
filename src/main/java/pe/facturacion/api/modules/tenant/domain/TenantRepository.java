package pe.facturacion.api.modules.tenant.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByApiKey(String apiKey);

    Optional<Tenant> findByRuc(String ruc);

    boolean existsByRuc(String ruc);
}
