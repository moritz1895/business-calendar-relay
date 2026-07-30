package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.Objects;

/**
 * One raw CalDAV resource as last reported by the server, keyed by its WebDAV
 * {@code href}. A transport-/protocol-level value type living beside {@link CalendarReplicaStore}
 * rather than in {@code core/domain}, analogous to {@link BlockerMail} -- it carries pure
 * CalDAV protocol knowledge (raw ICS text, {@code href}, {@code etag}), not domain meaning.
 *
 * @param href the WebDAV resource identity, as reported by the server; the key the local
 *     replica is indexed by, since {@code sync-collection} reports changes and removals by
 *     {@code href}, not by {@code UID}
 * @param etag the resource's last known ETag, stored purely for operational diagnosis --
 *     never compared by the adapter itself, since the server's delta response is already
 *     the sole authoritative signal for "changed"
 * @param rawCalendarData the full raw {@code calendar-data} content of this resource, as
 *     delivered by the server, unparsed
 */
public record CachedCalendarResource(String href, String etag, String rawCalendarData) {

    public CachedCalendarResource {
        Objects.requireNonNull(href, "href must not be null");
        Objects.requireNonNull(etag, "etag must not be null");
        Objects.requireNonNull(rawCalendarData, "rawCalendarData must not be null");

        if (href.isBlank()) {
            throw new IllegalArgumentException("href must not be blank");
        }
        if (rawCalendarData.isBlank()) {
            throw new IllegalArgumentException("rawCalendarData must not be blank");
        }
    }
}
