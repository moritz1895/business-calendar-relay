package ms.rohde.businesscalendarrelay.ports.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CachedCalendarResourceTest {

    private static final String HREF = "/remote.php/dav/calendars/user/personal/event1.ics";
    private static final String ETAG = "\"abc123\"";
    private static final String RAW_CALENDAR_DATA = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n";

    @Test
    void create_givenValidData_thenCachedCalendarResourceIsCreated() {
        var resource = new CachedCalendarResource(HREF, ETAG, RAW_CALENDAR_DATA);

        assertThat(resource.href()).isEqualTo(HREF);
        assertThat(resource.etag()).isEqualTo(ETAG);
        assertThat(resource.rawCalendarData()).isEqualTo(RAW_CALENDAR_DATA);
    }

    @Test
    void create_givenNullHref_thenThrowsNullPointerException() {
        assertThatThrownBy(() -> new CachedCalendarResource(null, ETAG, RAW_CALENDAR_DATA))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_givenBlankHref_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CachedCalendarResource("   ", ETAG, RAW_CALENDAR_DATA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_givenNullEtag_thenThrowsNullPointerException() {
        assertThatThrownBy(() -> new CachedCalendarResource(HREF, null, RAW_CALENDAR_DATA))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_givenNullRawCalendarData_thenThrowsNullPointerException() {
        assertThatThrownBy(() -> new CachedCalendarResource(HREF, ETAG, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_givenBlankRawCalendarData_thenThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new CachedCalendarResource(HREF, ETAG, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
