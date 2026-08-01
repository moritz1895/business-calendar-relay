package ms.rohde.businesscalendarrelay.ports.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CachedGoogleCalendarEventTest {

    private static final String EVENT_ID = "abc123def456";
    private static final String ETAG = "\"abc123\"";
    private static final String RAW_EVENT_JSON = "{\"id\":\"abc123def456\",\"status\":\"confirmed\"}";

    @Test
    void create_givenValidData_thenCachedGoogleCalendarEventIsCreated() {
        var event = new CachedGoogleCalendarEvent(EVENT_ID, ETAG, RAW_EVENT_JSON);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.etag()).isEqualTo(ETAG);
        assertThat(event.rawEventJson()).isEqualTo(RAW_EVENT_JSON);
    }

    @Test
    void create_givenNullEventId_thenThrowsNullPointerException() {
        assertThatThrownBy(() -> new CachedGoogleCalendarEvent(null, ETAG, RAW_EVENT_JSON))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_givenBlankEventId_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CachedGoogleCalendarEvent("   ", ETAG, RAW_EVENT_JSON))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenNullEtag_thenThrowsNullPointerException() {
        assertThatThrownBy(() -> new CachedGoogleCalendarEvent(EVENT_ID, null, RAW_EVENT_JSON))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_givenNullRawEventJson_thenThrowsNullPointerException() {
        assertThatThrownBy(() -> new CachedGoogleCalendarEvent(EVENT_ID, ETAG, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_givenBlankRawEventJson_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CachedGoogleCalendarEvent(EVENT_ID, ETAG, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenBlankEtag_thenStillCreatedSinceEtagIsInformationalOnly() {
        var event = new CachedGoogleCalendarEvent(EVENT_ID, "", RAW_EVENT_JSON);

        assertThat(event.etag()).isEmpty();
    }
}
