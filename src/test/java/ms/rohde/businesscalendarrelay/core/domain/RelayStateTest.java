package ms.rohde.businesscalendarrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class RelayStateTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, BERLIN);
    private static final ZonedDateTime END = START.plusHours(1);

    @Test
    void create_givenValidData_thenRelayStateIsCreated() {
        var state = new RelayState("source-uid-1", "blocker-uid-1", 0, START, END, true);

        assertThat(state.sourceUid()).isEqualTo("source-uid-1");
        assertThat(state.blockerUid()).isEqualTo("blocker-uid-1");
        assertThat(state.sequence()).isZero();
        assertThat(state.lastKnownStart()).isEqualTo(START);
        assertThat(state.lastKnownEnd()).isEqualTo(END);
        assertThat(state.active()).isTrue();
    }

    @Test
    void create_givenBlankSourceUid_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RelayState("  ", "blocker-uid-1", 0, START, END, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenBlankBlockerUid_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RelayState("source-uid-1", "  ", 0, START, END, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenNegativeSequence_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RelayState("source-uid-1", "blocker-uid-1", -1, START, END, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenEndNotAfterStart_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new RelayState("source-uid-1", "blocker-uid-1", 0, START, START, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenMismatchedZones_thenThrowsIllegalArgumentException() {
        var endInDifferentZone = END.withZoneSameInstant(ZoneId.of("UTC"));

        assertThatThrownBy(() -> new RelayState("source-uid-1", "blocker-uid-1", 0, START, endInDifferentZone, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenInactiveState_thenRelayStateIsCreated() {
        var state = new RelayState("source-uid-1", "blocker-uid-1", 3, START, END, false);

        assertThat(state.active()).isFalse();
    }

    @Test
    void equals_givenSameComponents_thenRelayStatesAreEqual() {
        var first = new RelayState("source-uid-1", "blocker-uid-1", 0, START, END, true);
        var second = new RelayState("source-uid-1", "blocker-uid-1", 0, START, END, true);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void create_givenAllLastKnownFlagsExplicitly_thenRelayStateCarriesThem() {
        var state = new RelayState("source-uid-1", "blocker-uid-1", 0, START, END, true, true, false, true);

        assertThat(state.lastKnownAllDay()).isTrue();
        assertThat(state.lastKnownBusy()).isFalse();
        assertThat(state.lastKnownCancelled()).isTrue();
    }

    @Test
    void create_givenSixArgConvenienceConstructor_thenDefaultsToNotAllDayBusyNotCancelled() {
        var state = new RelayState("source-uid-1", "blocker-uid-1", 0, START, END, true);

        assertThat(state.lastKnownAllDay()).isFalse();
        assertThat(state.lastKnownBusy()).isTrue();
        assertThat(state.lastKnownCancelled()).isFalse();
    }
}
