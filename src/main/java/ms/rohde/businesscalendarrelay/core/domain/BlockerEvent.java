package ms.rohde.businesscalendarrelay.core.domain;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import ms.rohde.hexagonalarch.annotations.DomainValueObject;
import org.jspecify.annotations.Nullable;

/**
 * A single blocker occurrence to be rendered into iMIP/ICS text.
 *
 * <p>{@code uid} must stay stable across create/update/cancel renders of the same
 * source event so Outlook treats them as the same appointment. {@code sequence} is
 * owned and incremented by the caller (e.g. the application layer / state store) and
 * must strictly increase on every re-render of the same logical revision.
 *
 * <p>{@code sourceTitle} is a pure transport container carried through from
 * {@link RelayAction.Create}/{@link RelayAction.Update} to the application layer's mail
 * construction step — it exists so a downstream mail-adapter step can surface it as a
 * human-readable hint in the iMIP mail body. It is deliberately <b>never</b> read by
 * {@link ImipCalendarRenderer}: the rendered ICS text always emits the fixed
 * {@code SUMMARY:Privater Blocker} literal regardless of this field's value, keeping the
 * business calendar's blocker titleless exactly as before.
 */
@DomainValueObject
public record BlockerEvent(
        String uid,
        long sequence,
        ZonedDateTime start,
        ZonedDateTime end,
        String organizerEmail,
        String attendeeEmail,
        @Nullable String sourceTitle) {

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
