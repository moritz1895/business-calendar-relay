package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA row shape for the single RFC 6578 {@code sync-collection} sync-token of one source
 * calendar. One row per source calendar, kept in a table of its own separate from
 * {@link CalendarReplicaResourceEntity} -- see {@code docs/features/delta-sync.md} for why:
 * no join is needed on the frequent {@code loadSyncToken()} read path, and a forced full
 * resync via {@code resetTo(...)} writes both tables independently anyway.
 *
 * <p>{@code syncToken} is nullable: {@code null} means "no initial sync has completed
 * successfully yet for this source calendar".
 */
@Entity
@Table(name = "calendar_sync_token")
class CalendarSyncTokenEntity {

    @Id
    @Column(name = "source_calendar_id", nullable = false, updatable = false)
    private String sourceCalendarId;

    @Column(name = "sync_token")
    private String syncToken;

    protected CalendarSyncTokenEntity() {}

    CalendarSyncTokenEntity(String sourceCalendarId, String syncToken) {
        this.sourceCalendarId = sourceCalendarId;
        this.syncToken = syncToken;
    }

    String getSourceCalendarId() {
        return sourceCalendarId;
    }

    String getSyncToken() {
        return syncToken;
    }

    void setSyncToken(String syncToken) {
        this.syncToken = syncToken;
    }
}
