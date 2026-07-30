package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * JPA row shape for one raw CalDAV resource cached in the local replica of a source
 * calendar's collection, scoped to one source calendar via {@code sourceCalendarId}.
 * Structurally a direct sibling of {@link PendingCreationEntity} -- see
 * {@code docs/features/delta-sync.md} for why {@code sync-collection} deltas are cached
 * as raw, unexpanded resources rather than already-expanded occurrences.
 *
 * <p>Primary key is the composite {@code (sourceCalendarId, href)}, since {@code
 * sync-collection} reports changes and removals by {@code href}, not by {@code UID}.
 */
@Entity
@Table(
        name = "calendar_replica_resource",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_calendar_id", "href"}))
@IdClass(CalendarReplicaResourceEntityId.class)
class CalendarReplicaResourceEntity {

    @Id
    @Column(name = "source_calendar_id", nullable = false, updatable = false)
    private String sourceCalendarId;

    @Id
    @Column(name = "href", nullable = false, updatable = false)
    private String href;

    @Column(name = "etag", nullable = false)
    private String etag;

    @Lob
    @Column(name = "raw_calendar_data", nullable = false)
    private String rawCalendarData;

    protected CalendarReplicaResourceEntity() {}

    CalendarReplicaResourceEntity(String sourceCalendarId, String href, String etag, String rawCalendarData) {
        this.sourceCalendarId = sourceCalendarId;
        this.href = href;
        this.etag = etag;
        this.rawCalendarData = rawCalendarData;
    }

    String getSourceCalendarId() {
        return sourceCalendarId;
    }

    String getHref() {
        return href;
    }

    String getEtag() {
        return etag;
    }

    void setEtag(String etag) {
        this.etag = etag;
    }

    String getRawCalendarData() {
        return rawCalendarData;
    }

    void setRawCalendarData(String rawCalendarData) {
        this.rawCalendarData = rawCalendarData;
    }
}
