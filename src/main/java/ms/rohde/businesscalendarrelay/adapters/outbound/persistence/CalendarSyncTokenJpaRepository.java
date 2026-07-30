package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link CalendarSyncTokenEntity}, shared across every
 * per-calendar {@link JpaCalendarReplicaStoreAdapter} instance. One row per source
 * calendar, keyed by {@code sourceCalendarId}.
 */
public interface CalendarSyncTokenJpaRepository extends JpaRepository<CalendarSyncTokenEntity, String> {

    Optional<CalendarSyncTokenEntity> findBySourceCalendarId(String sourceCalendarId);
}
