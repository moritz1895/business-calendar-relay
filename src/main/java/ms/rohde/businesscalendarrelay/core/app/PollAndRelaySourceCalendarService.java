package ms.rohde.businesscalendarrelay.core.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import ms.rohde.businesscalendarrelay.core.domain.BlockerEvent;
import ms.rohde.businesscalendarrelay.core.domain.ImipCalendarRenderer;
import ms.rohde.businesscalendarrelay.core.domain.RelayAction;
import ms.rohde.businesscalendarrelay.core.domain.RelayDiffPlanner;
import ms.rohde.businesscalendarrelay.core.domain.RelayState;
import ms.rohde.businesscalendarrelay.ports.inbound.PollAndRelaySourceCalendarUseCase;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMail;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMailMethod;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSinkException;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarSource;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStore;
import ms.rohde.hexagonalarch.annotations.ApplicationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Use case "Poll and Relay Source Calendar": brings the business calendar's blockers
 * for one configured source calendar back in sync with that source calendar's current
 * events, sending only the iMIP messages needed to reflect what changed since the last
 * poll.
 *
 * <p>Delegates the create/update/cancel decision and {@code SEQUENCE} bookkeeping to
 * {@link RelayDiffPlanner}; this service only orchestrates the resulting plan against
 * the outbound ports. A failed {@link BlockerSink#send(BlockerMail)} call is isolated to
 * its one source event; the rest of the poll cycle continues and the failure is reported
 * in the returned {@link RelayCycleResult}.
 */
@ApplicationService
public final class PollAndRelaySourceCalendarService implements PollAndRelaySourceCalendarUseCase {

    private static final Logger LOG = LogManager.getLogger(PollAndRelaySourceCalendarService.class);

    private final CalendarSource calendarSource;
    private final BlockerSink blockerSink;
    private final StateStore stateStore;
    private final String organizerEmail;
    private final String attendeeEmail;
    private final String fromAddress;
    private final String replyToAddress;
    private final Clock clock;
    private final RelayDiffPlanner planner = new RelayDiffPlanner();
    private final ImipCalendarRenderer renderer = new ImipCalendarRenderer();

    public PollAndRelaySourceCalendarService(
            CalendarSource calendarSource,
            BlockerSink blockerSink,
            StateStore stateStore,
            String organizerEmail,
            String attendeeEmail,
            String fromAddress,
            String replyToAddress,
            Clock clock) {
        this.calendarSource = calendarSource;
        this.blockerSink = blockerSink;
        this.stateStore = stateStore;
        this.organizerEmail = organizerEmail;
        this.attendeeEmail = attendeeEmail;
        this.fromAddress = fromAddress;
        this.replyToAddress = replyToAddress;
        this.clock = clock;
    }

    /**
     * Runs one poll cycle: reads the current source events and the prior relay state,
     * asks {@link RelayDiffPlanner} for the resulting create/update/cancel plan, sends
     * the corresponding iMIP messages, and updates {@link StateStore} for every action
     * whose send succeeded.
     */
    @Override
    public RelayCycleResult pollAndRelay() {
        var currentEvents = calendarSource.readEvents();
        var priorStates = stateStore.loadAll();
        var actions = planner.plan(currentEvents, priorStates);

        var created = new ArrayList<String>();
        var updated = new ArrayList<String>();
        var cancelled = new ArrayList<String>();
        var failed = new ArrayList<RelayFailure>();

        for (var action : actions) {
            switch (action) {
                case RelayAction.Create create -> processCreate(create, created, failed);
                case RelayAction.Update update -> processUpdate(update, updated, failed);
                case RelayAction.Cancel cancel -> processCancel(cancel, cancelled, failed);
            }
        }

        return new RelayCycleResult(created, updated, cancelled, failed);
    }

    private void processCreate(RelayAction.Create action, List<String> created, List<RelayFailure> failed) {
        var blockerEvent = new BlockerEvent(
                action.blockerUid(), action.sequence(), action.start(), action.end(), organizerEmail, attendeeEmail);
        var icsText = renderer.renderRequest(blockerEvent, clock.instant());
        var mail = new BlockerMail(icsText, BlockerMailMethod.REQUEST, fromAddress, replyToAddress, attendeeEmail);

        if (!trySend(mail, action.sourceUid(), "create", failed)) {
            return;
        }

        stateStore.save(new RelayState(
                action.sourceUid(), action.blockerUid(), action.sequence(), action.start(), action.end(), true));
        created.add(action.sourceUid());
    }

    private void processUpdate(RelayAction.Update action, List<String> updated, List<RelayFailure> failed) {
        var blockerEvent = new BlockerEvent(
                action.blockerUid(), action.sequence(), action.start(), action.end(), organizerEmail, attendeeEmail);
        var icsText = renderer.renderRequest(blockerEvent, clock.instant());
        var mail = new BlockerMail(icsText, BlockerMailMethod.REQUEST, fromAddress, replyToAddress, attendeeEmail);

        if (!trySend(mail, action.sourceUid(), "update", failed)) {
            return;
        }

        stateStore.save(new RelayState(
                action.sourceUid(), action.blockerUid(), action.sequence(), action.start(), action.end(), true));
        updated.add(action.sourceUid());
    }

    private void processCancel(RelayAction.Cancel action, List<String> cancelled, List<RelayFailure> failed) {
        var blockerEvent = new BlockerEvent(
                action.blockerUid(), action.sequence(), action.start(), action.end(), organizerEmail, attendeeEmail);
        var icsText = renderer.renderCancel(blockerEvent, clock.instant());
        var mail = new BlockerMail(icsText, BlockerMailMethod.CANCEL, fromAddress, replyToAddress, attendeeEmail);

        if (!trySend(mail, action.sourceUid(), "cancel", failed)) {
            return;
        }

        stateStore.markCancelled(action.sourceUid(), action.sequence());
        cancelled.add(action.sourceUid());
    }

    private boolean trySend(BlockerMail mail, String sourceUid, String actionKind, List<RelayFailure> failed) {
        try {
            blockerSink.send(mail);
            return true;
        } catch (BlockerSinkException e) {
            LOG.warn("Failed to send {} blocker for sourceUid={}", actionKind, sourceUid, e);
            failed.add(new RelayFailure(sourceUid, e));
            return false;
        }
    }
}
