package pe.facturacion.api.modules.plan.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** {@code activeSubscription}: la más reciente en estado active/trialing ({@code latestOfMany} = mayor id). */
    Optional<Subscription> findFirstByTenantIdAndStatusInOrderByIdDesc(Long tenantId, Collection<String> statuses);

    /** {@code ->latest()->first()}: por {@code created_at} descendente. */
    Optional<Subscription> findFirstByTenantIdAndStatusInOrderByCreatedAtDescIdDesc(Long tenantId, Collection<String> statuses);

    List<Subscription> findByTenantIdAndStatusIn(Long tenantId, Collection<String> statuses);
}
