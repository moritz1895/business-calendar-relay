package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link CalendarReplicaResourceEntity}, shared across
 * every per-calendar {@link JpaCalendarReplicaStoreAdapter} instance. Every finder is
 * explicitly scoped by {@code sourceCalendarId} so no single adapter instance can see
 * another calendar's rows.
 */
public interface CalendarReplicaResourceJpaRepository
        extends JpaRepository<CalendarReplicaResourceEntity, CalendarReplicaResourceEntityId> {

    List<CalendarReplicaResourceEntity> findAllBySourceCalendarId(String sourceCalendarId);

    void deleteBySourceCalendarIdAndHrefIn(String sourceCalendarId, List<String> hrefs);

    void deleteBySourceCalendarId(String sourceCalendarId);
}
