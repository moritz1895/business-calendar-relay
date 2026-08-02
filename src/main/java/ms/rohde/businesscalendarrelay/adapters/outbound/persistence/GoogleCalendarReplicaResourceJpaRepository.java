package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link GoogleCalendarReplicaResourceEntity}, shared across
 * every per-calendar {@link JpaGoogleCalendarReplicaStoreAdapter} instance. Every finder is
 * explicitly scoped by {@code sourceCalendarId} so no single adapter instance can see
 * another calendar's rows.
 */
public interface GoogleCalendarReplicaResourceJpaRepository
        extends JpaRepository<GoogleCalendarReplicaResourceEntity, GoogleCalendarReplicaResourceEntityId> {

    List<GoogleCalendarReplicaResourceEntity> findAllBySourceCalendarId(String sourceCalendarId);

    void deleteBySourceCalendarIdAndEventIdIn(String sourceCalendarId, List<String> eventIds);

    void deleteBySourceCalendarId(String sourceCalendarId);
}
