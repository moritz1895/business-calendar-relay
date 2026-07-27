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
 * Deliberately carries nothing else — no summary, description, organizer, or
 * attendee — since blockers are titleless by design and filtering logic is
 * explicitly deferred.
 */
@DomainValueObject
public record SourceEvent(String sourceUid, ZonedDateTime start, ZonedDateTime end) {

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
}
