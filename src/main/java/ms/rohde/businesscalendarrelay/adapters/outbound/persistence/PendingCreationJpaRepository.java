package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for {@link PendingCreationEntity}, shared across every
 * per-calendar {@link JpaPendingCreationQueueAdapter} instance. Every finder is explicitly
 * scoped by {@code sourceCalendarId} so no single adapter instance can see another
 * calendar's rows.
 */
public interface PendingCreationJpaRepository extends JpaRepository<PendingCreationEntity, PendingCreationEntityId> {

    List<PendingCreationEntity> findAllBySourceCalendarIdOrderByStartAsc(String sourceCalendarId);

    /**
     * {@code @Transactional} belongs here, on the repository interface method, not on
     * {@link JpaPendingCreationQueueAdapter#remove}: this repository is a genuine
     * Spring-managed proxy bean, but the per-calendar adapter wrapping it is constructed
     * directly via {@code new} (see {@code RelayWiringConfiguration}), never through
     * Spring, so {@code @Transactional} on the adapter's own method is silently inert --
     * there is no proxy around the adapter for Spring's AOP machinery to intercept.
     * {@code @Transactional} on this method, by contrast, wraps every call made through
     * this repository bean itself, regardless of which non-managed adapter instance calls
     * it. Verified against a real deployment: this derived delete failed every time with
     * "No EntityManager with actual transaction available for current thread" when called
     * from the background poll-cycle scheduler thread until this annotation was added
     * here specifically (an earlier attempt that annotated the adapter method instead did
     * not fix it).
     */
    @Transactional
    void deleteBySourceCalendarIdAndSourceUid(String sourceCalendarId, String sourceUid);
}
