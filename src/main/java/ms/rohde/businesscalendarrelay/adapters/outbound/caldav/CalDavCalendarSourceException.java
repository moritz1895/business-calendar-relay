package ms.rohde.businesscalendarrelay.adapters.outbound.caldav;

/**
 * Thrown by {@link CalDavCalendarSourceAdapter#readEvents()} when the configured CalDAV
 * collection could not be read or its {@code calendar-query} response could not be
 * turned into {@link ms.rohde.businesscalendarrelay.core.domain.SourceEvent}s: network
 * failure, an unexpected HTTP status, malformed multistatus XML, or malformed
 * {@code calendar-data}. For all of these, {@link CalDavCalendarSourceAdapter} does not
 * catch and continue — the whole read fails as one unit, matching {@code
 * CalendarSource.readEvents()}'s own "nothing sent or stored yet, abort the whole cycle"
 * contract, since none of that response's data can be trusted at all.
 *
 * <p>The one exception: a single {@code VEVENT} UID group within an otherwise
 * successfully-parsed response that turns out to be semantically incomplete (missing
 * {@code UID}, {@code DTSTART}, or {@code DTEND}, or a {@code RECURRENCE-ID} override
 * without a master) is caught, logged at {@code WARN}, and skipped by {@link
 * CalDavCalendarSourceAdapter#expandAll} rather than propagating — see {@code
 * docs/adr/012-skip-malformed-vevent-groups-instead-of-aborting-cycle.md}.
 */
public class CalDavCalendarSourceException extends RuntimeException {

    public CalDavCalendarSourceException(String message) {
        super(message);
    }

    public CalDavCalendarSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
