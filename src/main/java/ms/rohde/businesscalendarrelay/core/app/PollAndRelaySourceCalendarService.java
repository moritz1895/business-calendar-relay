package ms.rohde.businesscalendarrelay.core.app;

import java.time.Clock;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import ms.rohde.businesscalendarrelay.ports.outbound.BurstBudget;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarSource;
import ms.rohde.businesscalendarrelay.ports.outbound.PendingCreationQueue;
import ms.rohde.businesscalendarrelay.ports.outbound.PendingCreationQueueException;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStore;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStoreException;
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
 * the outbound ports. A failed {@link BlockerSink#send(BlockerMail)} call, or a failed
 * {@link StateStore#save(RelayState)}/{@link StateStore#markCancelled(String, long)} call,
 * is isolated to its one source event; the rest of the poll cycle continues and the
 * failure is reported in the returned {@link RelayCycleResult}.
 *
 * <p>Operates in one of two mutually exclusive modes per cycle, driven entirely by
 * whether this calendar's {@link PendingCreationQueue} is currently empty (see
 * {@code docs/features/burst-filter-initialization.md}, issue #16):
 * <ul>
 *   <li><b>Capture-and-drain</b> — the very first cycle for a calendar (both
 *       {@link StateStore#loadAll()} and {@link PendingCreationQueue#loadAllOrderedByStart()}
 *       are empty) captures the entire {@link RelayAction.Create} backlog
 *       {@link RelayDiffPlanner#plan} computes against an empty prior state in one shot,
 *       persists it via {@link PendingCreationQueue#saveAll}, and every cycle thereafter —
 *       for as long as the queue still has entries — drains it a few items at a time,
 *       bounded by the mailbox-wide {@link BurstBudget}.
 *   <li><b>Steady-state</b> — once the queue is empty, exactly today's unmodified
 *       {@link RelayDiffPlanner#plan}-driven create/update/cancel cycle runs.
 * </ul>
 * {@link RelayDiffPlanner#plan} is called at most once per cycle: while this calendar's
 * queue holds any entries, the ordinary cycle does not run at all, so there is never more
 * than one active backlog per calendar.
 */
@ApplicationService
public final class PollAndRelaySourceCalendarService implements PollAndRelaySourceCalendarUseCase {

    private static final Logger LOG = LogManager.getLogger(PollAndRelaySourceCalendarService.class);

    /**
     * No-op {@link PendingCreationQueue} backing the legacy constructor below: never
     * carries anything over between cycles, so a calendar wired through that constructor
     * captures and drains its backlog unbounded within a single cycle, identical to this
     * class's behavior before {@code docs/features/burst-filter-initialization.md}.
     */
    private static final PendingCreationQueue NO_PENDING_CREATION_QUEUE = new PendingCreationQueue() {

        @Override
        public List<RelayAction.Create> loadAllOrderedByStart() {
            return List.of();
        }

        @Override
        public void saveAll(List<RelayAction.Create> pendingCreates) {
        }

        @Override
        public void remove(String sourceUid) {
        }
    };

    /** {@link BurstBudget} backing the legacy constructor below: every slot is granted. */
    private static final BurstBudget UNLIMITED_BURST_BUDGET = () -> true;

    private final CalendarSource calendarSource;
    private final BlockerSink blockerSink;
    private final StateStore stateStore;
    private final PendingCreationQueue pendingCreationQueue;
    private final BurstBudget burstBudget;
    private final String organizerEmail;
    private final String attendeeEmail;
    private final String fromAddress;
    private final String replyToAddress;
    private final Clock clock;
    private final Period recurringEventHorizon;
    private final RelayDiffPlanner planner = new RelayDiffPlanner();
    private final ImipCalendarRenderer renderer = new ImipCalendarRenderer();

    public PollAndRelaySourceCalendarService(
            CalendarSource calendarSource,
            BlockerSink blockerSink,
            StateStore stateStore,
            PendingCreationQueue pendingCreationQueue,
            BurstBudget burstBudget,
            String organizerEmail,
            String attendeeEmail,
            String fromAddress,
            String replyToAddress,
            Clock clock,
            Period recurringEventHorizon) {
        this.calendarSource = calendarSource;
        this.blockerSink = blockerSink;
        this.stateStore = stateStore;
        this.pendingCreationQueue = pendingCreationQueue;
        this.burstBudget = burstBudget;
        this.organizerEmail = organizerEmail;
        this.attendeeEmail = attendeeEmail;
        this.fromAddress = fromAddress;
        this.replyToAddress = replyToAddress;
        this.clock = clock;
        this.recurringEventHorizon = recurringEventHorizon;
    }

    /**
     * Legacy overload predating {@code PendingCreationQueue}/{@code BurstBudget} (issue
     * #16), delegating to the canonical constructor with an always-empty queue and an
     * always-available budget — i.e. capture-and-drain still runs on a virgin
     * {@code StateStore}, but unbounded within that one cycle, reproducing this class's
     * exact pre-issue-#16 behavior. Kept only because {@code RelayWiringConfiguration}
     * still constructs this service positionally with the pre-issue-#16 argument list;
     * a follow-up adapter PR wires the real {@code PendingCreationQueue}/{@code
     * BurstBudget} instances through this constructor and removes this overload.
     */
    public PollAndRelaySourceCalendarService(
            CalendarSource calendarSource,
            BlockerSink blockerSink,
            StateStore stateStore,
            String organizerEmail,
            String attendeeEmail,
            String fromAddress,
            String replyToAddress,
            Clock clock,
            Period recurringEventHorizon) {
        this(
                calendarSource,
                blockerSink,
                stateStore,
                NO_PENDING_CREATION_QUEUE,
                UNLIMITED_BURST_BUDGET,
                organizerEmail,
                attendeeEmail,
                fromAddress,
                replyToAddress,
                clock,
                recurringEventHorizon);
    }

    /**
     * Runs one poll cycle. See the class Javadoc for the capture-and-drain versus
     * steady-state mode decision; whichever mode applies, {@link RelayDiffPlanner#plan}
     * is invoked at most once.
     */
    @Override
    public RelayCycleResult pollAndRelay() {
        var now = ZonedDateTime.now(clock);
        var priorStates = stateStore.loadAll();
        var pendingQueue = pendingCreationQueue.loadAllOrderedByStart();

        if (pendingQueue.isEmpty() && priorStates.isEmpty()) {
            pendingQueue = captureInitializationQueue(now);
        }

        if (!pendingQueue.isEmpty()) {
            return drainPendingQueue(pendingQueue, priorStates, now);
        }

        var currentEvents = calendarSource.readEvents();
        var actions = planner.plan(currentEvents, priorStates, now, recurringEventHorizon);

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

    /**
     * First-ever capture for this calendar: computes the full {@link RelayAction.Create}
     * backlog against an empty prior state — structurally the only action type
     * {@link RelayDiffPlanner#plan} can return when {@code priorStates} is empty — sorts
     * it ascending by {@code start}, and persists it in one shot before anything is sent.
     */
    private List<RelayAction.Create> captureInitializationQueue(ZonedDateTime now) {
        var currentEvents = calendarSource.readEvents();
        var captured = planner.plan(currentEvents, List.of(), now, recurringEventHorizon);
        var sortedCreates = captured.stream()
                .map(RelayAction.Create.class::cast)
                .sorted(Comparator.comparing(RelayAction.Create::start))
                .toList();
        pendingCreationQueue.saveAll(sortedCreates);
        LOG.info("Captured {} pending creation(s) into the initialization queue", sortedCreates.size());
        return sortedCreates;
    }

    /**
     * Drains {@code pendingQueue} ascending by {@code start}, up to whatever
     * {@link BurstBudget#tryAcquireSendSlot()} allows this cycle. See the class Javadoc
     * and {@code docs/features/burst-filter-initialization.md} for the full algorithm.
     */
    private RelayCycleResult drainPendingQueue(
            List<RelayAction.Create> pendingQueue, List<RelayState> priorStates, ZonedDateTime now) {
        var alreadyProcessedSourceUids = indexSourceUids(priorStates);
        var created = new ArrayList<String>();
        var failed = new ArrayList<RelayFailure>();
        var removedCount = 0;
        var staleCount = 0;

        for (var item : pendingQueue) {
            if (alreadyProcessedSourceUids.contains(item.sourceUid())) {
                removePendingQueueEntry(item.sourceUid());
                removedCount++;
                continue;
            }
            if (planner.isPastCreationCutoff(item.start(), now)) {
                removePendingQueueEntry(item.sourceUid());
                removedCount++;
                staleCount++;
                continue;
            }
            if (!burstBudget.tryAcquireSendSlot()) {
                break;
            }
            var failedCountBeforeSend = failed.size();
            processCreate(item, created, failed);
            if (failed.size() == failedCountBeforeSend) {
                removePendingQueueEntry(item.sourceUid());
                removedCount++;
            }
        }

        LOG.debug("Dropped {} stale pending creation(s) during this drain", staleCount);
        LOG.info("{} pending creation(s) remain queued after this cycle", pendingQueue.size() - removedCount);

        return new RelayCycleResult(created, List.of(), List.of(), failed);
    }

    private void removePendingQueueEntry(String sourceUid) {
        try {
            pendingCreationQueue.remove(sourceUid);
        } catch (PendingCreationQueueException e) {
            LOG.warn("Failed to remove pending creation queue entry for sourceUid={}", sourceUid, e);
        }
    }

    private Set<String> indexSourceUids(List<RelayState> states) {
        var sourceUids = new HashSet<String>();
        for (var state : states) {
            sourceUids.add(state.sourceUid());
        }
        return sourceUids;
    }

    private void processCreate(RelayAction.Create action, List<String> created, List<RelayFailure> failed) {
        var blockerEvent = new BlockerEvent(
                action.blockerUid(), action.sequence(), action.start(), action.end(), organizerEmail, attendeeEmail);
        var icsText = renderer.renderRequest(blockerEvent, clock.instant());
        var mail = new BlockerMail(icsText, BlockerMailMethod.REQUEST, fromAddress, replyToAddress, attendeeEmail);

        if (!trySend(mail, action.sourceUid(), "create", failed)) {
            return;
        }

        var state = new RelayState(
                action.sourceUid(),
                action.blockerUid(),
                action.sequence(),
                action.start(),
                action.end(),
                true,
                action.allDay(),
                action.busy(),
                action.cancelled());
        if (!trySaveState(state, action.sourceUid(), failed)) {
            return;
        }
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

        var state = new RelayState(
                action.sourceUid(),
                action.blockerUid(),
                action.sequence(),
                action.start(),
                action.end(),
                true,
                action.allDay(),
                action.busy(),
                action.cancelled());
        if (!trySaveState(state, action.sourceUid(), failed)) {
            return;
        }
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

        if (!tryMarkCancelled(action.sourceUid(), action.sequence(), failed)) {
            return;
        }
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

    private boolean trySaveState(RelayState state, String sourceUid, List<RelayFailure> failed) {
        try {
            stateStore.save(state);
            return true;
        } catch (StateStoreException e) {
            LOG.warn("Failed to save relay state for sourceUid={}", sourceUid, e);
            failed.add(new RelayFailure(sourceUid, e));
            return false;
        }
    }

    private boolean tryMarkCancelled(String sourceUid, long sequence, List<RelayFailure> failed) {
        try {
            stateStore.markCancelled(sourceUid, sequence);
            return true;
        } catch (StateStoreException e) {
            LOG.warn("Failed to mark relay state cancelled for sourceUid={}", sourceUid, e);
            failed.add(new RelayFailure(sourceUid, e));
            return false;
        }
    }
}
