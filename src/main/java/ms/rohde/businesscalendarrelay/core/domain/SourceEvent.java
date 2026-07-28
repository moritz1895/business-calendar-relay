package ms.rohde.businesscalendarrelay.core.domain;

import java.time.ZonedDateTime;
import java.util.Objects;
import ms.rohde.hexagonalarch.annotations.DomainValueObject;

/**
 * A CalDAV {@code VEVENT} as read from the private source calendar, before it has any
 * relay identity.
 *
 * <p>{@code sourceUid} is the CalDAV {@code UID} of the source event, scoped to the
 * source calendar and in a distinct namespace from a blocker's own {@code UID}.
 * Deliberately carries nothing else beyond identity, time window, and the four flags
 * below — no summary, description, organizer, or attendee — since blockers are
 * titleless by design.
 *
 * <p>{@code allDay}, {@code busy}, and {@code cancelled} feed {@link RelayDiffPlanner}'s
 * creation-eligibility gate and its change-detection comparison against
 * {@link RelayState}'s {@code lastKnown*} fields. {@code recurring} is informational
 * only: it is consulted by the creation gate's recurring-event horizon check, but never
 * compared for change detection.
 */
@DomainValueObject
public record SourceEvent(
        String sourceUid,
        ZonedDateTime start,
        ZonedDateTime end,
        boolean allDay,
        boolean busy,
        boolean recurring,
        boolean cancelled) {

    public SourceEvent {
        Objects.requireNonNull(sourceUid, "sourceUid must not be null");
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");

        if (sourceUid.isBlank()) {
            throw new IllegalArgumentException("sourceUid must not be blank");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        if (!start.getZone().equals(end.getZone())) {
            throw new IllegalArgumentException("start and end must use the same time zone");
        }
    }

    /**
     * Convenience constructor defaulting {@code allDay} to {@code false}, {@code busy}
     * to {@code true} (RFC 5545's {@code TRANSP} default is {@code OPAQUE}),
     * {@code recurring} to {@code false}, and {@code cancelled} to {@code false} -- a
     * single, non-recurring, busy, non-cancelled timed event, the shape every
     * {@code SourceEvent} had before event filtering introduced the other three flags.
     */
    public SourceEvent(String sourceUid, ZonedDateTime start, ZonedDateTime end) {
        this(sourceUid, start, end, false, true, false, false);
    }
}
