package ms.rohde.businesscalendarrelay.adapters.outbound.google;

/**
 * Thrown by {@link GoogleCalendarSourceAdapter#readEvents()} when the configured Google
 * Calendar could not be read: an OAuth token exchange failure (e.g. {@code 400
 * invalid_grant} for a revoked/expired refresh token), an unexpected {@code events.list}
 * HTTP status, malformed JSON, or a failure of the underlying
 * {@code GoogleCalendarReplicaStore}. For all of these, {@link GoogleCalendarSourceAdapter}
 * does not catch and continue -- the whole read fails as one unit, matching {@code
 * CalendarSource.readEvents()}'s own "nothing sent or stored yet, abort the whole cycle"
 * contract, since none of that response's data can be trusted at all.
 *
 * <p>The one exception: a single Google event item within an otherwise successfully-parsed
 * {@code events.list} response that turns out to be semantically incomplete (missing
 * {@code start}/{@code end}) is caught, logged at {@code WARN}, and skipped rather than
 * propagating -- mirroring {@code CalDavCalendarSourceAdapter}'s per-{@code VEVENT}-group
 * skip behaviour (see {@code docs/adr/012-skip-malformed-vevent-groups-instead-of-aborting-cycle.md}).
 */
public class GoogleCalendarSourceException extends RuntimeException {

    public GoogleCalendarSourceException(String message) {
        super(message);
    }

    public GoogleCalendarSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
