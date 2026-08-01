package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link GoogleCalendarSyncTokenEntity}, shared across every
 * per-calendar {@link JpaGoogleCalendarReplicaStoreAdapter} instance. One row per source
 * calendar, keyed by {@code sourceCalendarId}.
 */
public interface GoogleCalendarSyncTokenJpaRepository extends JpaRepository<GoogleCalendarSyncTokenEntity, String> {

    Optional<GoogleCalendarSyncTokenEntity> findBySourceCalendarId(String sourceCalendarId);
}
