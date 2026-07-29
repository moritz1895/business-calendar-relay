package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.ZonedDateTime;

/**
 * JPA row shape for one queued
 * {@link ms.rohde.businesscalendarrelay.core.domain.RelayAction.Create}, scoped to one
 * source calendar via {@code sourceCalendarId}. Structurally a direct sibling of
 * {@link RelayStateEntity} -- see {@code docs/features/burst-filter-initialization.md}
 * for why this feature gets its own table rather than extending {@code relay_state}.
 *
 * <p>Unlike {@link RelayStateEntity}, a row here is deleted once drained: mere presence
 * in this table means "pending". There is no {@code sequence} or {@code active} column --
 * both are meaningless for this table, since a queued creation is always {@code sequence}
 * 0 by construction and an entry either exists (pending) or does not (drained/dropped).
 */
@Entity
@Table(
        name = "pending_creation",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_calendar_id", "source_uid"}))
@IdClass(PendingCreationEntityId.class)
class PendingCreationEntity {

    @Id
    @Column(name = "source_calendar_id", nullable = false, updatable = false)
    private String sourceCalendarId;

    @Id
    @Column(name = "source_uid", nullable = false, updatable = false)
    private String sourceUid;

    @Column(name = "blocker_uid", nullable = false)
    private String blockerUid;

    @Convert(converter = ZonedDateTimeStringConverter.class)
    @Column(name = "`start`", nullable = false, length = 64)
    private ZonedDateTime start;

    @Convert(converter = ZonedDateTimeStringConverter.class)
    @Column(name = "`end`", nullable = false, length = 64)
    private ZonedDateTime end;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(name = "busy", nullable = false)
    private boolean busy;

    @Column(name = "cancelled", nullable = false)
    private boolean cancelled;

    protected PendingCreationEntity() {}

    PendingCreationEntity(
            String sourceCalendarId,
            String sourceUid,
            String blockerUid,
            ZonedDateTime start,
            ZonedDateTime end,
            boolean allDay,
            boolean busy,
            boolean cancelled) {
        this.sourceCalendarId = sourceCalendarId;
        this.sourceUid = sourceUid;
        this.blockerUid = blockerUid;
        this.start = start;
        this.end = end;
        this.allDay = allDay;
        this.busy = busy;
        this.cancelled = cancelled;
    }

    String getSourceCalendarId() {
        return sourceCalendarId;
    }

    String getSourceUid() {
        return sourceUid;
    }

    String getBlockerUid() {
        return blockerUid;
    }

    ZonedDateTime getStart() {
        return start;
    }

    ZonedDateTime getEnd() {
        return end;
    }

    boolean isAllDay() {
        return allDay;
    }

    boolean isBusy() {
        return busy;
    }

    boolean isCancelled() {
        return cancelled;
    }
}
