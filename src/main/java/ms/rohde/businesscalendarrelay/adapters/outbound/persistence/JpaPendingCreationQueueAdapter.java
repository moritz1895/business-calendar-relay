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

    /**
     * Transactionality for the underlying delete lives on {@link
     * PendingCreationJpaRepository#deleteBySourceCalendarIdAndSourceUid}, not here --
     * this adapter is constructed via plain {@code new}, never through Spring, so it has
     * no proxy for {@code @Transactional} on this method to attach to. See that
     * repository method's Javadoc for the full explanation.
     */
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

    /**
     * {@code sourceTitle} is deliberately dropped on this round-trip: {@link
     * PendingCreationEntity} carries no column for it, matching {@code
     * docs/features/source-title-hint-in-imip-mail-body.md}'s decision to add no new
     * persistence for the title anywhere in this feature. Practical consequence: a
     * {@link RelayAction.Create} that spent time queued here (the burst-filter
     * initialization backlog for a brand-new calendar, see {@code
     * docs/features/burst-filter-initialization.md}) sends its eventual create mail
     * without the mail-text title hint, even if the originating source event had one --
     * an accepted degradation, not a bug, since the hint is a convenience, not a data
     * contract.
     */
    private RelayAction.Create toDomain(PendingCreationEntity entity) {
        return new RelayAction.Create(
                entity.getSourceUid(),
                entity.getBlockerUid(),
                0,
                entity.getStart(),
                entity.getEnd(),
                entity.isAllDay(),
                entity.isBusy(),
                entity.isCancelled(),
                null);
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
