package ms.rohde.businesscalendarrelay.core.domain;

import java.time.ZonedDateTime;
import java.util.Objects;
import ms.rohde.hexagonalarch.annotations.DomainValueObject;
import org.jspecify.annotations.Nullable;

/**
 * A CalDAV {@code VEVENT} as read from the private source calendar, before it has any
 * relay identity.
 *
 * <p>{@code sourceUid} is the CalDAV {@code UID} of the source event, scoped to the
 * source calendar and in a distinct namespace from a blocker's own {@code UID}.
 * Beyond identity, time window, and the flags below, this record carries exactly one
 * further piece of source-calendar content: {@code sourceTitle}. This does <b>not</b>
 * mean the blocker itself gains a title — the rendered blocker in the business calendar
 * stays titleless by design, exactly as before (see {@link ImipCalendarRenderer}, which
 * never reads this field and always emits the fixed {@code SUMMARY:Privater Blocker}
 * literal). {@code sourceTitle} exists solely so a later step in the mail-sending
 * pipeline can surface the original source-event title as a human-readable hint in the
 * iMIP mail body — never in the rendered ICS text and never in any Outlook-visible
 * calendar field.
 *
 * <p>{@code allDay}, {@code busy}, and {@code cancelled} feed {@link RelayDiffPlanner}'s
 * creation-eligibility gate and its change-detection comparison against
 * {@link RelayState}'s {@code lastKnown*} fields. {@code recurring} and
 * {@code sourceTitle} are both informational only: {@code recurring} is consulted by the
 * creation gate's recurring-event horizon check, and {@code sourceTitle} is carried
 * purely as mail-text payload — neither is ever compared for change detection.
 */
@DomainValueObject
public record SourceEvent(
        String sourceUid,
        ZonedDateTime start,
        ZonedDateTime end,
        boolean allDay,
        boolean busy,
        boolean recurring,
        boolean cancelled,
        @Nullable String sourceTitle) {

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
