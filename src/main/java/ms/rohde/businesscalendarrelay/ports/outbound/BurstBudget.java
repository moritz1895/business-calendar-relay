package ms.rohde.businesscalendarrelay.ports.outbound;

import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;

/**
 * Mailbox-wide send budget throttling how many initialization-backlog
 * {@code RelayAction.Create} entries may be sent per time window, across all configured
 * source calendars combined (see {@code docs/features/burst-filter-initialization.md}).
 *
 * <p>One configured instance, shared across all source calendars — no instance knows
 * about any other, all share this same port.
 */
@InfrastructureServicePort
public interface BurstBudget {

    /**
     * Attempts to claim one send slot for the current time window. Returns {@code true}
     * if a slot was available and has now been consumed, {@code false} if the current
     * window's budget is already exhausted.
     *
     * <p>A pure, thread-safe in-memory decision — no I/O, and deliberately no declared
     * exception.
     */
    boolean tryAcquireSendSlot();
}
