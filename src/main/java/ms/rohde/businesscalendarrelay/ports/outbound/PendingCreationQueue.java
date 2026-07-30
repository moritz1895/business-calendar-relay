package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.List;
import ms.rohde.businesscalendarrelay.core.domain.RelayAction;
import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;

/**
 * Durable storage for one configured source calendar's initialization backlog — the
 * {@link RelayAction.Create} list {@code RelayDiffPlanner.plan(...)} computes in one shot
 * against a virgin {@code StateStore}, captured once and drained across multiple poll
 * cycles under a shared {@link BurstBudget} (see
 * {@code docs/features/burst-filter-initialization.md}).
 *
 * <p>One configured instance per source calendar, exactly like {@link StateStore} and
 * {@code CalendarSource}.
 */
@InfrastructureServicePort
public interface PendingCreationQueue {

    /**
     * Returns every pending {@link RelayAction.Create} for this source calendar,
     * ordered ascending by {@link RelayAction.Create#start()}. The ordering is part of
     * this method's contract, not something the caller must additionally impose.
     */
    List<RelayAction.Create> loadAllOrderedByStart();

    /**
     * Persists {@code pendingCreates} for this source calendar. Insert-only: called
     * exactly once, immediately after the initialization capture, when this calendar's
     * queue is known to be empty — not an upsert, and not intended to merge with any
     * pre-existing row.
     *
     * @throws PendingCreationQueueException if the underlying persistence operation fails
     */
    void saveAll(List<RelayAction.Create> pendingCreates);

    /**
     * Removes the queued entry for {@code sourceUid}, if any. Idempotent: removing an
     * entry that is no longer present (or was never present) is not an error.
     *
     * @throws PendingCreationQueueException if the underlying persistence operation fails
     */
    void remove(String sourceUid);
}
