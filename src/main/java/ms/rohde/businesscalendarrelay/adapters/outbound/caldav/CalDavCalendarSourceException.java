package ms.rohde.businesscalendarrelay.adapters.outbound.caldav;

/**
 * Thrown by {@link CalDavCalendarSourceAdapter#readEvents()} when the configured CalDAV
 * collection could not be read or its {@code calendar-query} response could not be
 * turned into {@link ms.rohde.businesscalendarrelay.core.domain.SourceEvent}s: network
 * failure, an unexpected HTTP status, malformed multistatus XML, or malformed
 * {@code calendar-data}. {@link CalDavCalendarSourceAdapter} does not catch and continue
 * on any of these — the whole read fails as one unit, matching
 * {@code CalendarSource.readEvents()}'s own "nothing sent or stored yet, abort the whole
 * cycle" contract.
 */
public class CalDavCalendarSourceException extends RuntimeException {

    public CalDavCalendarSourceException(String message) {
        super(message);
    }

    public CalDavCalendarSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
