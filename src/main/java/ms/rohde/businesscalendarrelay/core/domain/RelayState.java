package ms.rohde.businesscalendarrelay.core.domain;

import java.time.ZonedDateTime;
import java.util.Objects;
import ms.rohde.hexagonalarch.annotations.DomainValueObject;

/**
 * One source event's last-known relay state, as persisted by {@code StateStore}.
 *
 * <p>{@code blockerUid} stays stable across a source event's whole lifecycle
 * (create → updates → cancel), independent of {@code sourceUid}. {@code sequence} is
 * the last {@code SEQUENCE} value sent for {@code blockerUid} and is the single source
 * of truth the application layer derives the next {@code SEQUENCE} from.
 *
 * <p>{@code active} is {@code true} while the source event is still present and
 * un-cancelled, and {@code false} once a {@code CANCEL} has been sent for it. A
 * cancelled entry is kept, not deleted, so that a source event reappearing later
 * resumes the same {@code blockerUid} and continues the {@code sequence} count rather
 * than starting a duplicate blocker.
 *
 * <p>{@code lastKnownAllDay}, {@code lastKnownBusy}, and {@code lastKnownCancelled}
 * mirror {@link SourceEvent#allDay()}, {@link SourceEvent#busy()}, and
 * {@link SourceEvent#cancelled()} as of the last successful send, alongside
 * {@code lastKnownStart}/{@code lastKnownEnd} -- {@link RelayDiffPlanner} compares the
 * current {@link SourceEvent} against all five of these fields to decide whether an
 * update is needed.
 */
@DomainValueObject
public record RelayState(
        String sourceUid,
        String blockerUid,
        long sequence,
        ZonedDateTime lastKnownStart,
        ZonedDateTime lastKnownEnd,
        boolean active,
        boolean lastKnownAllDay,
        boolean lastKnownBusy,
        boolean lastKnownCancelled) {

    public RelayState {
        Objects.requireNonNull(sourceUid, "sourceUid must not be null");
        Objects.requireNonNull(blockerUid, "blockerUid must not be null");
        Objects.requireNonNull(lastKnownStart, "lastKnownStart must not be null");
        Objects.requireNonNull(lastKnownEnd, "lastKnownEnd must not be null");

        if (sourceUid.isBlank()) {
            throw new IllegalArgumentException("sourceUid must not be blank");
        }
        if (blockerUid.isBlank()) {
            throw new IllegalArgumentException("blockerUid must not be blank");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (!lastKnownEnd.isAfter(lastKnownStart)) {
            throw new IllegalArgumentException("lastKnownEnd must be after lastKnownStart");
        }
        if (!lastKnownStart.getZone().equals(lastKnownEnd.getZone())) {
            throw new IllegalArgumentException("lastKnownStart and lastKnownEnd must use the same time zone");
        }
    }
}
