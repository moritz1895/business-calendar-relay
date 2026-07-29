package ms.rohde.businesscalendarrelay.core.domain;

import java.nio.charset.StandardCharsets;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ms.rohde.hexagonalarch.annotations.DomainService;

/**
 * Decides, for one poll cycle, which source events need a blocker created, updated, or
 * cancelled, and what {@code SEQUENCE} that action gets — the pure decision logic behind
 * the "Poll and Relay Source Calendar" use case, independent of any port or I/O.
 *
 * <p>Decision rules, given {@code currentEvents}, {@code priorStates}, {@code now}, and
 * {@code recurringEventHorizon}:
 * <ul>
 *   <li>A source event with no prior {@link RelayState}, and eligible for creation per
 *       {@link #isEligibleForCreation} → {@link RelayAction.Create} with a {@code
 *       blockerUid} deterministically derived from {@code sourceUid} (see
 *       {@link #deriveBlockerUid}) at {@code sequence} 0.
 *   <li>A source event with no prior {@link RelayState}, but <b>not</b> eligible for
 *       creation → no action at all for this cycle; it is re-evaluated against the gate
 *       fresh on every subsequent poll.
 *   <li>A source event with an {@code active} prior state whose {@code start},
 *       {@code end}, {@code allDay}, {@code busy}, or {@code cancelled} changed relative
 *       to the prior's {@code lastKnown*} fields → {@link RelayAction.Update} reusing
 *       {@code blockerUid} at {@code prior.sequence() + 1}.
 *   <li>A source event with an {@code active} prior state that has not changed by any of
 *       those five fields → no action (resending an identical request would be a no-op
 *       in Outlook).
 *   <li>A source event with a non-{@code active} (previously cancelled) prior state →
 *       always {@link RelayAction.Update}, regardless of whether anything changed — this
 *       is the resurrection case, which falls out of treating it identically to an
 *       active update once cancelled entries are kept rather than deleted.
 *   <li>A prior state that is still {@code active} but whose {@code sourceUid} is absent
 *       from {@code currentEvents} → {@link RelayAction.Cancel} reusing {@code blockerUid}
 *       at {@code prior.sequence() + 1}, using the prior's last-known window.
 * </ul>
 *
 * <p><b>The creation-eligibility gate ({@link #isEligibleForCreation}) is consulted
 * exclusively for the "no prior {@link RelayState}" branch above.</b> It is never
 * consulted for a source event that already has a {@link RelayState} entry, active or
 * not — that source event always follows the ordinary update/no-op/cancel/resurrection
 * rules regardless of its current {@code start}, {@code allDay}, {@code busy}, or
 * {@code cancelled} values. Getting this wrong would mean an already-active blocker
 * silently disappears from the business calendar the moment its source event ages past
 * the cutoff or its flags change — the single most important invariant of this class.
 */
@DomainService
public final class RelayDiffPlanner {

    /**
     * Computes the create/update/cancel plan for one poll cycle. Pure function of its
     * four inputs; performs no I/O and calls no port. {@code now} and
     * {@code recurringEventHorizon} are passed in fresh on every call rather than held
     * as state, since the creation gate they feed must be evaluated against the current
     * moment on every poll cycle.
     */
    public List<RelayAction> plan(
            List<SourceEvent> currentEvents,
            List<RelayState> priorStates,
            ZonedDateTime now,
            Period recurringEventHorizon) {
        var priorByUid = indexBySourceUid(priorStates);
        var actions = new ArrayList<RelayAction>();
        var seenSourceUids = new HashSet<String>();

        for (var event : currentEvents) {
            seenSourceUids.add(event.sourceUid());
            var prior = priorByUid.get(event.sourceUid());
            if (prior == null) {
                if (isEligibleForCreation(event, now, recurringEventHorizon)) {
                    actions.add(new RelayAction.Create(
                            event.sourceUid(),
                            deriveBlockerUid(event.sourceUid()),
                            0,
                            event.start(),
                            event.end(),
                            event.allDay(),
                            event.busy(),
                            event.cancelled()));
                }
            } else if (!prior.active() || relayStateChanged(event, prior)) {
                actions.add(new RelayAction.Update(
                        event.sourceUid(),
                        prior.blockerUid(),
                        prior.sequence() + 1,
                        event.start(),
                        event.end(),
                        event.allDay(),
                        event.busy(),
                        event.cancelled()));
            }
        }

        for (var prior : priorByUid.values()) {
            if (prior.active() && !seenSourceUids.contains(prior.sourceUid())) {
                actions.add(new RelayAction.Cancel(
                        prior.sourceUid(),
                        prior.blockerUid(),
                        prior.sequence() + 1,
                        prior.lastKnownStart(),
                        prior.lastKnownEnd()));
            }
        }

        return actions;
    }

    /**
     * A source event is eligible to have a brand-new blocker created for it only when
     * all of the following hold: its {@code start} is not in the past, it is not an
     * all-day event, it is marked busy, it is not marked cancelled, and — only if it
     * originates from a recurring series — its {@code start} does not lie beyond
     * {@code now.plus(recurringEventHorizon)}. Non-recurring events have no upper bound
     * beyond the past-start cutoff.
     */
    private boolean isEligibleForCreation(SourceEvent event, ZonedDateTime now, Period recurringEventHorizon) {
        if (event.start().isBefore(now)) {
            return false;
        }
        if (event.allDay()) {
            return false;
        }
        if (!event.busy()) {
            return false;
        }
        if (event.cancelled()) {
            return false;
        }
        return !event.recurring() || !event.start().isAfter(now.plus(recurringEventHorizon));
    }

    /**
     * Derives a stable {@code blockerUid} from {@code sourceUid} alone, rather than
     * generating a fresh random one, so that a Create retried for the same source event
     * — because a prior attempt's iMIP mail sent successfully but its {@link RelayState}
     * never made it into the {@code StateStore} — produces the identical {@code
     * blockerUid} (and, since sequence is always 0 for a Create, an identical resend)
     * instead of a second, independent blocker appointment in Outlook.
     */
    private String deriveBlockerUid(String sourceUid) {
        return UUID.nameUUIDFromBytes(sourceUid.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean relayStateChanged(SourceEvent event, RelayState prior) {
        return !event.start().equals(prior.lastKnownStart())
                || !event.end().equals(prior.lastKnownEnd())
                || event.allDay() != prior.lastKnownAllDay()
                || event.busy() != prior.lastKnownBusy()
                || event.cancelled() != prior.lastKnownCancelled();
    }

    private Map<String, RelayState> indexBySourceUid(List<RelayState> states) {
        var index = new HashMap<String, RelayState>();
        for (var state : states) {
            index.put(state.sourceUid(), state);
        }
        return index;
    }
}
