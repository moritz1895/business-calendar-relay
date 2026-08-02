package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * JPA row shape for one raw Google Calendar event resource cached in the local replica of a
 * source calendar, scoped to one source calendar via {@code sourceCalendarId}. Structurally
 * a direct sibling of {@link CalendarReplicaResourceEntity}, but keyed by Google's own
 * per-occurrence {@code eventId} rather than a CalDAV {@code href} -- see {@code
 * docs/features/google-calendar-integration.md}'s Design-Entscheidung 4 for why Google's
 * replica granularity is per already-expanded occurrence, not per series.
 *
 * <p>Primary key is the composite {@code (sourceCalendarId, eventId)}, since Google's
 * {@code events.list(syncToken=...)} delta reports changes and removals by {@code eventId}.
 */
@Entity
@Table(
        name = "google_calendar_replica_event",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_calendar_id", "event_id"}))
@IdClass(GoogleCalendarReplicaResourceEntityId.class)
class GoogleCalendarReplicaResourceEntity {

    @Id
    @Column(name = "source_calendar_id", nullable = false, updatable = false)
    private String sourceCalendarId;

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "etag", nullable = false)
    private String etag;

    @Lob
    @Column(name = "raw_event_json", nullable = false)
    private String rawEventJson;

    protected GoogleCalendarReplicaResourceEntity() {}

    GoogleCalendarReplicaResourceEntity(String sourceCalendarId, String eventId, String etag, String rawEventJson) {
        this.sourceCalendarId = sourceCalendarId;
        this.eventId = eventId;
        this.etag = etag;
        this.rawEventJson = rawEventJson;
    }

    String getSourceCalendarId() {
        return sourceCalendarId;
    }

    String getEventId() {
        return eventId;
    }

    String getEtag() {
        return etag;
    }

    void setEtag(String etag) {
        this.etag = etag;
    }

    String getRawEventJson() {
        return rawEventJson;
    }

    void setRawEventJson(String rawEventJson) {
        this.rawEventJson = rawEventJson;
    }
}
