package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import ms.rohde.businesscalendarrelay.core.domain.RelayAction;
import ms.rohde.businesscalendarrelay.ports.outbound.PendingCreationQueue;
import ms.rohde.businesscalendarrelay.ports.outbound.PendingCreationQueueException;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;

/**
 * {@link PendingCreationQueue} backed by the same embedded, file-mode H2 database as
 * {@link JpaStateStoreAdapter}, via Spring Data JPA.
 *
 * <p>One instance per configured source calendar, per {@link PendingCreationQueue}'s own
 * contract: the {@code sourceCalendarId} passed to the constructor scopes every
 * repository call this instance makes, so a {@link PendingCreationJpaRepository} can
 * safely be shared across every calendar's adapter instance while each instance only ever
 * sees, returns, or mutates its own rows. The composite business key per row is
 * {@code (sourceCalendarId, sourceUid)}.
 *
 * <p>Deliberately not wired as an auto-scanned, no-arg Spring singleton bean, for the same
 * reason as {@link JpaStateStoreAdapter} -- see that class's Javadoc for the full
 * explanation.
 */
@InfrastructureServiceAdapter
public final class JpaPendingCreationQueueAdapter implements PendingCreationQueue {

    private final PendingCreationJpaRepository repository;
    private final String sourceCalendarId;

    public JpaPendingCreationQueueAdapter(PendingCreationJpaRepository repository, String sourceCalendarId) {
        this.repository = repository;
        this.sourceCalendarId = sourceCalendarId;
    }

    @Override
    public List<RelayAction.Create> loadAllOrderedByStart() {
        return repository.findAllBySourceCalendarIdOrderByStartAsc(sourceCalendarId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void saveAll(List<RelayAction.Create> pendingCreates) {
        var entities = pendingCreates.stream().map(this::toEntity).toList();
        try {
            repository.saveAll(entities);
        } catch (RuntimeException e) {
            throw new PendingCreationQueueException(
                    "Failed to persist pending creation queue for sourceCalendarId=" + sourceCalendarId, e);
        }
    }

    @Override
    public void remove(String sourceUid) {
        try {
            repository.deleteBySourceCalendarIdAndSourceUid(sourceCalendarId, sourceUid);
        } catch (RuntimeException e) {
            throw new PendingCreationQueueException(
                    "Failed to remove pending creation queue entry for sourceCalendarId=" + sourceCalendarId
                            + ", sourceUid=" + sourceUid,
                    e);
        }
    }

    private RelayAction.Create toDomain(PendingCreationEntity entity) {
        return new RelayAction.Create(
                entity.getSourceUid(),
                entity.getBlockerUid(),
                0,
                entity.getStart(),
                entity.getEnd(),
                entity.isAllDay(),
                entity.isBusy(),
                entity.isCancelled());
    }

    private PendingCreationEntity toEntity(RelayAction.Create action) {
        return new PendingCreationEntity(
                sourceCalendarId,
                action.sourceUid(),
                action.blockerUid(),
                action.start(),
                action.end(),
                action.allDay(),
                action.busy(),
                action.cancelled());
    }
}
