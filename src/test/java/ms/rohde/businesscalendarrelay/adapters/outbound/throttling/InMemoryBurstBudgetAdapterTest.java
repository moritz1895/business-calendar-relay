package ms.rohde.businesscalendarrelay.adapters.outbound.throttling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link InMemoryBurstBudgetAdapter}'s fixed-window counter behavior: denial once
 * the window's {@code burstSize} is exhausted, a fresh window's slots becoming available
 * again once {@code burstInterval} has elapsed, and that the window only resets when
 * queried (not on a background timer). Thread-safety is provided by the {@code
 * synchronized} keyword itself and is not separately exercised here, per the spec's own
 * reasoning that contention under this access pattern is structurally negligible.
 */
class InMemoryBurstBudgetAdapterTest {

    private static final Instant START = Instant.parse("2026-07-29T08:00:00Z");
    private static final Duration BURST_INTERVAL = Duration.ofHours(1);
    private static final int BURST_SIZE = 3;

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryBurstBudgetAdapter budget =
            new InMemoryBurstBudgetAdapter(clock, BURST_SIZE, BURST_INTERVAL);

    @Test
    void tryAcquireSendSlot_givenSlotsAvailableWithinWindow_thenReturnsTrue() {
        assertThat(budget.tryAcquireSendSlot()).isTrue();
        assertThat(budget.tryAcquireSendSlot()).isTrue();
        assertThat(budget.tryAcquireSendSlot()).isTrue();
    }

    @Test
    void tryAcquireSendSlot_givenBurstSizeAlreadyReachedWithinWindow_thenReturnsFalse() {
        budget.tryAcquireSendSlot();
        budget.tryAcquireSendSlot();
        budget.tryAcquireSendSlot();

        assertThat(budget.tryAcquireSendSlot()).isFalse();
    }

    @Test
    void tryAcquireSendSlot_givenWindowNotYetElapsed_thenStaysDenied() {
        budget.tryAcquireSendSlot();
        budget.tryAcquireSendSlot();
        budget.tryAcquireSendSlot();
        clock.advance(BURST_INTERVAL.minusSeconds(1));

        assertThat(budget.tryAcquireSendSlot()).isFalse();
    }

    @Test
    void tryAcquireSendSlot_givenBurstIntervalElapsed_thenResetsWindowAndAllowsAgain() {
        budget.tryAcquireSendSlot();
        budget.tryAcquireSendSlot();
        budget.tryAcquireSendSlot();
        assertThat(budget.tryAcquireSendSlot()).isFalse();

        clock.advance(BURST_INTERVAL);

        assertThat(budget.tryAcquireSendSlot()).isTrue();
    }

    @Test
    void tryAcquireSendSlot_givenNewWindowAfterReset_thenAgainDeniesOnceBurstSizeReached() {
        budget.tryAcquireSendSlot();
        budget.tryAcquireSendSlot();
        budget.tryAcquireSendSlot();
        clock.advance(BURST_INTERVAL);

        assertThat(budget.tryAcquireSendSlot()).isTrue();
        assertThat(budget.tryAcquireSendSlot()).isTrue();
        assertThat(budget.tryAcquireSendSlot()).isTrue();
        assertThat(budget.tryAcquireSendSlot()).isFalse();
    }

    /** Minimal mutable {@link Clock} test double so window elapsing can be controlled explicitly. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
