package ms.rohde.businesscalendarrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class SourceEventTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, BERLIN);
    private static final ZonedDateTime END = START.plusHours(1);

    @Test
    void create_givenValidData_thenSourceEventIsCreated() {
        var event = new SourceEvent("source-uid-1", START, END);

        assertThat(event.sourceUid()).isEqualTo("source-uid-1");
        assertThat(event.start()).isEqualTo(START);
        assertThat(event.end()).isEqualTo(END);
    }

    @Test
    void create_givenBlankSourceUid_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new SourceEvent("  ", START, END))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenEndNotAfterStart_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new SourceEvent("source-uid-1", START, START))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenMismatchedZones_thenThrowsIllegalArgumentException() {
        var endInDifferentZone = END.withZoneSameInstant(ZoneId.of("UTC"));

        assertThatThrownBy(() -> new SourceEvent("source-uid-1", START, endInDifferentZone))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equals_givenSameComponents_thenSourceEventsAreEqual() {
        var first = new SourceEvent("source-uid-1", START, END);
        var second = new SourceEvent("source-uid-1", START, END);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
