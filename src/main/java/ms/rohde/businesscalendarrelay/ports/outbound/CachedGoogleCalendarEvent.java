package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.Objects;

/**
 * One Google Calendar event instance as last reported by {@code events.list}, keyed by its
 * Google-assigned {@code eventId} -- either a genuinely single event, or one already-expanded
 * occurrence of a recurring series ({@code singleEvents=true}). A transport-/protocol-level
 * value type living beside {@link GoogleCalendarReplicaStore}, analogous to
 * {@code CachedCalendarResource} beside {@link CalendarReplicaStore} -- it carries pure Google
 * Calendar API protocol knowledge (raw event JSON, event ID, ETag), not domain meaning.
 *
 * @param eventId Google's own, stable identifier for this event or event instance -- the key
 *     the local replica is indexed by
 * @param etag the resource's last known ETag, stored purely for operational diagnosis, never
 *     compared by the adapter itself, mirroring {@code CachedCalendarResource#etag}
 * @param rawEventJson the full raw Event resource JSON, as delivered by Google, unparsed
 */
public record CachedGoogleCalendarEvent(String eventId, String etag, String rawEventJson) {

    public CachedGoogleCalendarEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(etag, "etag must not be null");
        Objects.requireNonNull(rawEventJson, "rawEventJson must not be null");

        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (rawEventJson.isBlank()) {
            throw new IllegalArgumentException("rawEventJson must not be blank");
        }
    }
}
