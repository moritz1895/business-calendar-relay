package ms.rohde.businesscalendarrelay.core.domain;

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
 * <p>Decision rules, given {@code currentEvents} and {@code priorStates}:
 * <ul>
 *   <li>A source event with no prior {@link RelayState} → {@link RelayAction.Create}
 *       with a freshly generated {@code blockerUid} at {@code sequence} 0.
 *   <li>A source event with an {@code active} prior state whose {@code start}/{@code end}
 *       changed → {@link RelayAction.Update} reusing {@code blockerUid} at
 *       {@code prior.sequence() + 1}.
 *   <li>A source event with an {@code active} prior state whose window is unchanged →
 *       no action (resending an identical request would be a no-op in Outlook).
 *   <li>A source event with a non-{@code active} (previously cancelled) prior state →
 *       always {@link RelayAction.Update}, regardless of whether the window changed —
 *       this is the resurrection case, which falls out of treating it identically to an
 *       active update once cancelled entries are kept rather than deleted.
 *   <li>A prior state that is still {@code active} but whose {@code sourceUid} is absent
 *       from {@code currentEvents} → {@link RelayAction.Cancel} reusing {@code blockerUid}
 *       at {@code prior.sequence() + 1}, using the prior's last-known window.
 * </ul>
 */
@DomainService
public final class RelayDiffPlanner {

    /**
     * Computes the create/update/cancel plan for one poll cycle. Pure function of its
     * two inputs; performs no I/O and calls no port.
     */
    public List<RelayAction> plan(List<SourceEvent> currentEvents, List<RelayState> priorStates) {
        var priorByUid = indexBySourceUid(priorStates);
        var actions = new ArrayList<RelayAction>();
        var seenSourceUids = new HashSet<String>();

        for (var event : currentEvents) {
            seenSourceUids.add(event.sourceUid());
            var prior = priorByUid.get(event.sourceUid());
            if (prior == null) {
                actions.add(new RelayAction.Create(
                        event.sourceUid(), UUID.randomUUID().toString(), 0, event.start(), event.end()));
            } else if (!prior.active() || windowChanged(event, prior)) {
                actions.add(new RelayAction.Update(
                        event.sourceUid(), prior.blockerUid(), prior.sequence() + 1, event.start(), event.end()));
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

    private boolean windowChanged(SourceEvent event, RelayState prior) {
        return !event.start().equals(prior.lastKnownStart()) || !event.end().equals(prior.lastKnownEnd());
    }

    private Map<String, RelayState> indexBySourceUid(List<RelayState> states) {
        var index = new HashMap<String, RelayState>();
        for (var state : states) {
            index.put(state.sourceUid(), state);
        }
        return index;
    }
}
