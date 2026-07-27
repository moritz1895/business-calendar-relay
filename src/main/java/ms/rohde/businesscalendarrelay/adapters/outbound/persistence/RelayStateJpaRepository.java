package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link RelayStateEntity}, shared across every
 * per-calendar {@link JpaStateStoreAdapter} instance. Every finder is explicitly scoped
 * by {@code sourceCalendarId} so no single adapter instance can see another calendar's
 * rows.
 */
public interface RelayStateJpaRepository extends JpaRepository<RelayStateEntity, RelayStateEntityId> {

    List<RelayStateEntity> findAllBySourceCalendarId(String sourceCalendarId);

    Optional<RelayStateEntity> findBySourceCalendarIdAndSourceUid(String sourceCalendarId, String sourceUid);
}
