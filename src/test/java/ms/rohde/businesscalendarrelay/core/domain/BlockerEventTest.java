package ms.rohde.businesscalendarrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class BlockerEventTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, BERLIN);
    private static final ZonedDateTime END = START.plusHours(1);

    @Test
    void create_givenValidData_thenBlockerEventIsCreated() {
        var event = new BlockerEvent("uid-1", 0, START, END, "relay@example.com", "business@example.com");

        assertThat(event.uid()).isEqualTo("uid-1");
        assertThat(event.sequence()).isZero();
        assertThat(event.zone()).isEqualTo(BERLIN);
    }

    @Test
    void create_givenBlankUid_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerEvent("  ", 0, START, END, "relay@example.com", "business@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenNegativeSequence_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerEvent("uid-1", -1, START, END, "relay@example.com", "business@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenEndNotAfterStart_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerEvent("uid-1", 0, START, START, "relay@example.com", "business@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenMismatchedZones_thenThrowsIllegalArgumentException() {
        var endInDifferentZone = END.withZoneSameInstant(ZoneId.of("UTC"));

        assertThatThrownBy(() -> new BlockerEvent("uid-1", 0, START, endInDifferentZone, "relay@example.com", "business@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenOrganizerEmailWithoutAtSign_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerEvent("uid-1", 0, START, END, "not-an-email", "business@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenAttendeeEmailWithoutAtSign_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new BlockerEvent("uid-1", 0, START, END, "relay@example.com", "not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equals_givenSameComponents_thenBlockerEventsAreEqual() {
        var first = new BlockerEvent("uid-1", 0, START, END, "relay@example.com", "business@example.com");
        var second = new BlockerEvent("uid-1", 0, START, END, "relay@example.com", "business@example.com");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
