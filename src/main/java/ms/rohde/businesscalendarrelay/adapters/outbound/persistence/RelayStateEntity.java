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
 * JPA row shape for one {@link ms.rohde.businesscalendarrelay.core.domain.RelayState},
 * scoped to one source calendar via {@code sourceCalendarId}. Plain persistence
 * mapping; deliberately not the domain record itself, per {@code CLAUDE.md}'s rule that
 * {@code core/domain} must never depend on JPA.
 *
 * <p>Never deleted once created — a cancelled entry is kept with {@code active = false}.
 */
@Entity
@Table(
        name = "relay_state",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_calendar_id", "source_uid"}))
@IdClass(RelayStateEntityId.class)
class RelayStateEntity {

    @Id
    @Column(name = "source_calendar_id", nullable = false, updatable = false)
    private String sourceCalendarId;

    @Id
    @Column(name = "source_uid", nullable = false, updatable = false)
    private String sourceUid;

    @Column(name = "blocker_uid", nullable = false)
    private String blockerUid;

    @Column(name = "sequence_number", nullable = false)
    private long sequence;

    @Convert(converter = ZonedDateTimeStringConverter.class)
    @Column(name = "last_known_start", nullable = false, length = 64)
    private ZonedDateTime lastKnownStart;

    @Convert(converter = ZonedDateTimeStringConverter.class)
    @Column(name = "last_known_end", nullable = false, length = 64)
    private ZonedDateTime lastKnownEnd;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "last_known_all_day", nullable = false)
    private boolean lastKnownAllDay;

    @Column(name = "last_known_busy", nullable = false)
    private boolean lastKnownBusy;

    @Column(name = "last_known_cancelled", nullable = false)
    private boolean lastKnownCancelled;

    protected RelayStateEntity() {}

    RelayStateEntity(
            String sourceCalendarId,
            String sourceUid,
            String blockerUid,
            long sequence,
            ZonedDateTime lastKnownStart,
            ZonedDateTime lastKnownEnd,
            boolean active,
            boolean lastKnownAllDay,
            boolean lastKnownBusy,
            boolean lastKnownCancelled) {
        this.sourceCalendarId = sourceCalendarId;
        this.sourceUid = sourceUid;
        this.blockerUid = blockerUid;
        this.sequence = sequence;
        this.lastKnownStart = lastKnownStart;
        this.lastKnownEnd = lastKnownEnd;
        this.active = active;
        this.lastKnownAllDay = lastKnownAllDay;
        this.lastKnownBusy = lastKnownBusy;
        this.lastKnownCancelled = lastKnownCancelled;
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

    void setBlockerUid(String blockerUid) {
        this.blockerUid = blockerUid;
    }

    long getSequence() {
        return sequence;
    }

    void setSequence(long sequence) {
        this.sequence = sequence;
    }

    ZonedDateTime getLastKnownStart() {
        return lastKnownStart;
    }

    void setLastKnownStart(ZonedDateTime lastKnownStart) {
        this.lastKnownStart = lastKnownStart;
    }

    ZonedDateTime getLastKnownEnd() {
        return lastKnownEnd;
    }

    void setLastKnownEnd(ZonedDateTime lastKnownEnd) {
        this.lastKnownEnd = lastKnownEnd;
    }

    boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    boolean isLastKnownAllDay() {
        return lastKnownAllDay;
    }

    void setLastKnownAllDay(boolean lastKnownAllDay) {
        this.lastKnownAllDay = lastKnownAllDay;
    }

    boolean isLastKnownBusy() {
        return lastKnownBusy;
    }

    void setLastKnownBusy(boolean lastKnownBusy) {
        this.lastKnownBusy = lastKnownBusy;
    }

    boolean isLastKnownCancelled() {
        return lastKnownCancelled;
    }

    void setLastKnownCancelled(boolean lastKnownCancelled) {
        this.lastKnownCancelled = lastKnownCancelled;
    }
}
