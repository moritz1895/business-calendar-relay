package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link PendingCreationEntity}, shared across every
 * per-calendar {@link JpaPendingCreationQueueAdapter} instance. Every finder is explicitly
 * scoped by {@code sourceCalendarId} so no single adapter instance can see another
 * calendar's rows.
 */
public interface PendingCreationJpaRepository extends JpaRepository<PendingCreationEntity, PendingCreationEntityId> {

    List<PendingCreationEntity> findAllBySourceCalendarIdOrderByStartAsc(String sourceCalendarId);

    void deleteBySourceCalendarIdAndSourceUid(String sourceCalendarId, String sourceUid);
}
