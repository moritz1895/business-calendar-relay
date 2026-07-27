package ms.rohde.businesscalendarrelay.core.domain;

import java.time.ZonedDateTime;
import java.util.Objects;
import ms.rohde.hexagonalarch.annotations.DomainValueObject;

/**
 * One create/update/cancel decision produced by {@link RelayDiffPlanner} for a single
 * source event, carrying everything the application layer needs to render and send the
 * corresponding iMIP message without having to re-derive the {@code SEQUENCE} or
 * {@code blockerUid} itself.
 *
 * <p>Deliberately does not carry the target {@code BlockerMailMethod} or any port-facing
 * type — this is a pure domain decision, translated to orchestration concepts (rendering,
 * sending, persisting) by the caller.
 */
public sealed interface RelayAction {

    String sourceUid();

    String blockerUid();

    long sequence();

    ZonedDateTime start();

    ZonedDateTime end();

    /**
     * A source event with no prior {@link RelayState}: a brand-new blocker must be
     * created under a freshly generated {@code blockerUid} at {@code sequence} 0.
     */
    @DomainValueObject
    record Create(String sourceUid, String blockerUid, long sequence, ZonedDateTime start, ZonedDateTime end)
            implements RelayAction {

        public Create {
            validate(sourceUid, blockerUid, sequence, start, end);
        }
    }

    /**
     * A source event whose blocker must be (re-)requested: either its time window
     * changed while active, or it is resurrecting from a previously cancelled state.
     * Reuses the prior {@code blockerUid} at {@code prior.sequence() + 1}.
     */
    @DomainValueObject
    record Update(String sourceUid, String blockerUid, long sequence, ZonedDateTime start, ZonedDateTime end)
            implements RelayAction {

        public Update {
            validate(sourceUid, blockerUid, sequence, start, end);
        }
    }

    /**
     * A previously active source event absent from the current poll: its blocker must
     * be cancelled, reusing the prior {@code blockerUid} at {@code prior.sequence() + 1}
     * and the last-known time window (there is no current window to use).
     */
    @DomainValueObject
    record Cancel(String sourceUid, String blockerUid, long sequence, ZonedDateTime start, ZonedDateTime end)
            implements RelayAction {

        public Cancel {
            validate(sourceUid, blockerUid, sequence, start, end);
        }
    }

    private static void validate(
            String sourceUid, String blockerUid, long sequence, ZonedDateTime start, ZonedDateTime end) {
        Objects.requireNonNull(sourceUid, "sourceUid must not be null");
        Objects.requireNonNull(blockerUid, "blockerUid must not be null");
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");

        if (sourceUid.isBlank()) {
            throw new IllegalArgumentException("sourceUid must not be blank");
        }
        if (blockerUid.isBlank()) {
            throw new IllegalArgumentException("blockerUid must not be blank");
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
    }
}
