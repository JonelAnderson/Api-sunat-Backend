package pe.facturacion.api.modules.portal.security;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.facturacion.api.modules.portal.user.PortalUserStore;
import pe.facturacion.api.modules.portal.user.PortalUserStore.SessionState;

/**
 * Estado de sesión de cada persona del portal (session_version, habilitado) con caché de 30 s por instancia. Lo que
 * cambia en esta instancia (logout, contraseña, MFA, deshabilitar, reinvitar) invalida la entrada al momento; en otras
 * instancias tarda como mucho 30 s.
 */
@Component
public class PortalSessionCache {

    static final long TTL_NANOS = 30_000_000_000L;

    private record Entry(Optional<SessionState> state, long loadedAt) {
    }

    private final PortalUserStore users;
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    public PortalSessionCache(PortalUserStore users) {
        this.users = users;
    }

    public Optional<SessionState> get(long userId) {
        long now = System.nanoTime();
        Entry e = cache.get(userId);
        if (e == null || now - e.loadedAt() > TTL_NANOS) {
            e = new Entry(users.sessionState(userId), now);
            if (cache.size() > 10_000) {
                cache.clear();
            }
            cache.put(userId, e);
        }
        return e.state();
    }

    /** Olvida la entrada ya y, si hay una transacción en curso, otra vez al confirmarla (nadie se queda con el estado viejo). */
    public void invalidate(long userId) {
        cache.remove(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cache.remove(userId);
                }
            });
        }
    }
}
