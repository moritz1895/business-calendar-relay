package ms.rohde.businesscalendarrelay.adapters.outbound.throttling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import ms.rohde.businesscalendarrelay.ports.outbound.BurstBudget;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;

/**
 * {@link BurstBudget} backed by an in-memory fixed-window counter: at most
 * {@code burstSize} send slots may be claimed within any {@code burstInterval}-long
 * window, mailbox-wide across all configured source calendars combined (see
 * {@code docs/features/burst-filter-initialization.md}, issue #16).
 *
 * <p>Exactly one instance is constructed by {@code RelayWiringConfiguration}, using the
 * already-shared {@code relayClock} bean, and injected into every
 * {@code PollAndRelaySourceCalendarService} instance -- see {@link BurstBudget}'s own
 * Javadoc for why this must stay a single, shared bean rather than one instance per
 * calendar.
 *
 * <p>{@code synchronized} is sufficient here: {@link #tryAcquireSendSlot()} is called at
 * most {@code burstSize} times per {@code burstInterval} across all calendars combined
 * (default: 5 times per hour), so contention is structurally negligible -- a lock-free
 * mechanism (e.g. {@code AtomicInteger} with a CAS loop) would be unnecessary complexity
 * for this access pattern. State lives purely in process memory and resets to a fresh
 * window on restart, a deliberately accepted trade-off (see the spec's "Weitere
 * Entscheidungen").
 */
@InfrastructureServiceAdapter
public final class InMemoryBurstBudgetAdapter implements BurstBudget {

    private final Clock clock;
    private final int burstSize;
    private final Duration burstInterval;

    private Instant windowStart;
    private int sentInWindow;

    public InMemoryBurstBudgetAdapter(Clock clock, int burstSize, Duration burstInterval) {
        this.clock = clock;
        this.burstSize = burstSize;
        this.burstInterval = burstInterval;
        this.windowStart = clock.instant();
        this.sentInWindow = 0;
    }

    @Override
    public synchronized boolean tryAcquireSendSlot() {
        var now = clock.instant();
        if (!now.isBefore(windowStart.plus(burstInterval))) {
            windowStart = now;
            sentInWindow = 0;
        }
        if (sentInWindow >= burstSize) {
            return false;
        }
        sentInWindow++;
        return true;
    }
}
