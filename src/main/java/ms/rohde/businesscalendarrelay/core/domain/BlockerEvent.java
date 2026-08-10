package ms.rohde.businesscalendarrelay.core.domain;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import ms.rohde.hexagonalarch.annotations.DomainValueObject;

/**
 * A single blocker occurrence to be rendered into iMIP/ICS text.
 *
 * <p>{@code uid} must stay stable across create/update/cancel renders of the same
 * source event so Outlook treats them as the same appointment. {@code sequence} is
 * owned and incremented by the caller (e.g. the application layer / state store) and
 * must strictly increase on every re-render of the same logical revision.
 */
@DomainValueObject
public record BlockerEvent(
        String uid,
        long sequence,
        ZonedDateTime start,
        ZonedDateTime end,
        String organizerEmail,
        String attendeeEmail) {

    public BlockerEvent {
        Objects.requireNonNull(uid, "uid must not be null");
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        Objects.requireNonNull(organizerEmail, "organizerEmail must not be null");
        Objects.requireNonNull(attendeeEmail, "attendeeEmail must not be null");

        if (uid.isBlank()) {
            throw new IllegalArgumentException("uid must not be blank");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        if (!start.getZone().equals(end.getZone())) {
            throw new IllegalArgumentException("start and end must use the same time zone");
        }
        if (!organizerEmail.contains("@")) {
            throw new IllegalArgumentException("organizerEmail must be a valid mailto address");
        }
        if (!attendeeEmail.contains("@")) {
            throw new IllegalArgumentException("attendeeEmail must be a valid mailto address");
        }
    }

    public ZoneId zone() {
        return start.getZone();
    }
}
