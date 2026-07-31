package ms.rohde.businesscalendarrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayDiffPlannerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 7, 20, 0, 0, 0, 0, BERLIN);
    private static final Period HORIZON = Period.ofMonths(6);
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, BERLIN);
    private static final ZonedDateTime END = START.plusHours(1);

    private final RelayDiffPlanner planner = new RelayDiffPlanner();

    @Test
    void plan_givenNewSourceEvent_thenReturnsCreateActionWithDeterministicBlockerUidAndSequenceZero() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).hasSize(1);
        var action = actions.getFirst();
        assertThat(action).isInstanceOf(RelayAction.Create.class);
        assertThat(action.sourceUid()).isEqualTo("source-1");
        assertThat(action.sequence()).isZero();
        assertThat(action.start()).isEqualTo(START);
        assertThat(action.end()).isEqualTo(END);
        assertThat(UUID.fromString(action.blockerUid())).isNotNull();
    }

    @Test
    void plan_givenNewSourceEvent_thenCreateActionCarriesAllDayBusyAndCancelledFromEvent() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).hasSize(1);
        var create = (RelayAction.Create) actions.getFirst();
        assertThat(create.allDay()).isFalse();
        assertThat(create.busy()).isTrue();
        assertThat(create.cancelled()).isFalse();
    }

    @Test
    void plan_givenTwoNewSourceEvents_thenGeneratesDistinctBlockerUids() {
        var currentEvents = List.of(
                new SourceEvent("source-1", START, END, false, true, false, false),
                new SourceEvent("source-2", START, END, false, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).blockerUid()).isNotEqualTo(actions.get(1).blockerUid());
    }

    @Test
    void plan_givenSameNewSourceEventPlannedTwice_thenGeneratesSameBlockerUidBothTimes() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, true, false, false));

        var firstAttempt = planner.plan(currentEvents, List.of(), NOW, HORIZON);
        var retryAfterUnpersistedState = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(firstAttempt).hasSize(1);
        assertThat(retryAfterUnpersistedState).hasSize(1);
        assertThat(retryAfterUnpersistedState.getFirst().blockerUid())
                .isEqualTo(firstAttempt.getFirst().blockerUid());
    }

    @Test
    void plan_givenChangedWindow_thenReturnsUpdateActionReusingBlockerUidAndIncrementingSequence() {
        var newEnd = END.plusMinutes(30);
        var currentEvents = List.of(new SourceEvent("source-1", START, newEnd, false, true, false, false));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 2, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        var action = actions.getFirst();
        assertThat(action).isInstanceOf(RelayAction.Update.class);
        assertThat(action.sourceUid()).isEqualTo("source-1");
        assertThat(action.blockerUid()).isEqualTo("blocker-1");
        assertThat(action.sequence()).isEqualTo(3);
        assertThat(action.start()).isEqualTo(START);
        assertThat(action.end()).isEqualTo(newEnd);
    }

    @Test
    void plan_givenUnchangedWindowOnActiveState_thenReturnsNoAction() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, true, false, false));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenDisappearedActiveSourceEvent_thenReturnsCancelActionReusingBlockerUidAndIncrementingSequence() {
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false));

        var actions = planner.plan(List.of(), priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        var action = actions.getFirst();
        assertThat(action).isInstanceOf(RelayAction.Cancel.class);
        assertThat(action.sourceUid()).isEqualTo("source-1");
        assertThat(action.blockerUid()).isEqualTo("blocker-1");
        assertThat(action.sequence()).isEqualTo(2);
        assertThat(action.start()).isEqualTo(START);
        assertThat(action.end()).isEqualTo(END);
    }

    @Test
    void plan_givenPreviouslyCancelledEventReappearsWithUnchangedWindow_thenReturnsUpdateActionRegardless() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, true, false, false));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 3, START, END, false, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        var action = actions.getFirst();
        assertThat(action).isInstanceOf(RelayAction.Update.class);
        assertThat(action.blockerUid()).isEqualTo("blocker-1");
        assertThat(action.sequence()).isEqualTo(4);
        assertThat(action.start()).isEqualTo(START);
        assertThat(action.end()).isEqualTo(END);
    }

    @Test
    void plan_givenMixedCreateUpdateNoOpAndCancel_thenReturnsOnlyTheRequiredActions() {
        var currentEvents = List.of(
                new SourceEvent("source-new", START, END, false, true, false, false),
                new SourceEvent("source-changed", START, END.plusMinutes(15), false, true, false, false),
                new SourceEvent("source-unchanged", START, END, false, true, false, false));
        var priorStates = List.of(
                new RelayState("source-changed", "blocker-changed", 0, START, END, true, false, true, false),
                new RelayState("source-unchanged", "blocker-unchanged", 0, START, END, true, false, true, false),
                new RelayState("source-gone", "blocker-gone", 5, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(3);
        assertThat(actions).anySatisfy(action -> {
            assertThat(action).isInstanceOf(RelayAction.Create.class);
            assertThat(action.sourceUid()).isEqualTo("source-new");
        });
        assertThat(actions).anySatisfy(action -> {
            assertThat(action).isInstanceOf(RelayAction.Update.class);
            assertThat(action.sourceUid()).isEqualTo("source-changed");
            assertThat(action.sequence()).isEqualTo(1);
        });
        assertThat(actions).anySatisfy(action -> {
            assertThat(action).isInstanceOf(RelayAction.Cancel.class);
            assertThat(action.sourceUid()).isEqualTo("source-gone");
            assertThat(action.sequence()).isEqualTo(6);
        });
        assertThat(actions)
                .noneMatch(action -> action.sourceUid().equals("source-unchanged"));
    }

    // --- isPastCreationCutoff: standalone, publicly reusable extraction of gate condition 1 ---

    @Test
    void isPastCreationCutoff_givenStartBeforeNow_thenReturnsTrue() {
        var pastStart = NOW.minusDays(1);

        assertThat(planner.isPastCreationCutoff(pastStart, NOW)).isTrue();
    }

    @Test
    void isPastCreationCutoff_givenStartExactlyAtNow_thenReturnsFalse() {
        assertThat(planner.isPastCreationCutoff(NOW, NOW)).isFalse();
    }

    @Test
    void isPastCreationCutoff_givenStartAfterNow_thenReturnsFalse() {
        var futureStart = NOW.plusDays(1);

        assertThat(planner.isPastCreationCutoff(futureStart, NOW)).isFalse();
    }

    // --- Creation-gate: each of the 5 isEligibleForCreation conditions, individually rejecting a would-be-create ---

    @Test
    void plan_givenNewEventWithPastStart_thenNoActionIsEmitted() {
        var pastStart = NOW.minusDays(1);
        var pastEnd = pastStart.plusHours(1);
        var currentEvents = List.of(new SourceEvent("source-1", pastStart, pastEnd, false, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenNewEventStartingExactlyAtNow_thenIsEligibleAndReturnsCreateAction() {
        var currentEvents = List.of(new SourceEvent("source-1", NOW, NOW.plusHours(1), false, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).hasSize(1).allSatisfy(action -> assertThat(action).isInstanceOf(RelayAction.Create.class));
    }

    @Test
    void plan_givenNewAllDayEvent_thenNoActionIsEmitted() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, true, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenNewTransparentEvent_thenNoActionIsEmitted() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, false, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenNewCancelledEvent_thenNoActionIsEmitted() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, true, false, true));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenNewEventStartingOnSaturday_thenNoActionIsEmitted() {
        var saturdayStart = ZonedDateTime.of(2026, 7, 25, 10, 0, 0, 0, BERLIN);
        var currentEvents =
                List.of(new SourceEvent("source-1", saturdayStart, saturdayStart.plusHours(1), false, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenNewEventStartingOnSunday_thenNoActionIsEmitted() {
        var sundayStart = ZonedDateTime.of(2026, 7, 26, 10, 0, 0, 0, BERLIN);
        var currentEvents =
                List.of(new SourceEvent("source-1", sundayStart, sundayStart.plusHours(1), false, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenActiveStateWhoseSourceEventStartMovedToSaturday_thenStillReturnsUpdateAction() {
        var saturdayStart = ZonedDateTime.of(2026, 7, 25, 10, 0, 0, 0, BERLIN);
        var saturdayEnd = saturdayStart.plusHours(1);
        var currentEvents = List.of(new SourceEvent("source-1", saturdayStart, saturdayEnd, false, true, false, false));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst()).isInstanceOf(RelayAction.Update.class);
    }

    @Test
    void plan_givenNewRecurringEventBeyondHorizon_thenNoActionIsEmitted() {
        var farStart = NOW.plus(HORIZON).plusDays(1);
        var currentEvents = List.of(new SourceEvent("source-1", farStart, farStart.plusHours(1), false, true, true, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenNewRecurringEventWithinHorizon_thenReturnsCreateAction() {
        var withinHorizonStart = NOW.plus(HORIZON).minusDays(1);
        var currentEvents = List.of(new SourceEvent(
                "source-1", withinHorizonStart, withinHorizonStart.plusHours(1), false, true, true, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).hasSize(1).allSatisfy(action -> assertThat(action).isInstanceOf(RelayAction.Create.class));
    }

    @Test
    void plan_givenNewNonRecurringEventFarInFuture_thenReturnsCreateActionWithNoUpperHorizonBound() {
        var farStart = NOW.plus(HORIZON).plusYears(5);
        var currentEvents =
                List.of(new SourceEvent("source-1", farStart, farStart.plusHours(1), false, true, false, false));

        var actions = planner.plan(currentEvents, List.of(), NOW, HORIZON);

        assertThat(actions).hasSize(1).allSatisfy(action -> assertThat(action).isInstanceOf(RelayAction.Create.class));
    }

    // --- Regression: the creation gate must never be consulted once a prior RelayState exists ---

    @Test
    void plan_givenEventFailingEveryGateConditionButHasActivePriorState_thenStillReturnsUpdateAction() {
        var pastStart = NOW.minusDays(1);
        var pastEnd = pastStart.plusHours(1);
        var gateFailingEvent = new SourceEvent("source-1", pastStart, pastEnd, true, false, true, true);
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 2, START, END, true, false, true, false));

        var actions = planner.plan(List.of(gateFailingEvent), priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        var action = actions.getFirst();
        assertThat(action).isInstanceOf(RelayAction.Update.class);
        assertThat(action.blockerUid()).isEqualTo("blocker-1");
        assertThat(action.sequence()).isEqualTo(3);
        assertThat(action.start()).isEqualTo(pastStart);
        assertThat(action.end()).isEqualTo(pastEnd);
    }

    @Test
    void plan_givenEventFailingEveryGateConditionButHasInactivePriorState_thenStillReturnsResurrectionUpdateAction() {
        var pastStart = NOW.minusDays(1);
        var pastEnd = pastStart.plusHours(1);
        var gateFailingEvent = new SourceEvent("source-1", pastStart, pastEnd, true, false, true, true);
        var priorStates =
                List.of(new RelayState("source-1", "blocker-1", 2, pastStart, pastEnd, false, false, true, false));

        var actions = planner.plan(List.of(gateFailingEvent), priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        var action = actions.getFirst();
        assertThat(action).isInstanceOf(RelayAction.Update.class);
        assertThat(action.blockerUid()).isEqualTo("blocker-1");
        assertThat(action.sequence()).isEqualTo(3);
    }

    @Test
    void plan_givenEventFailingEveryGateConditionAndUnchangedFromInactivePriorState_thenStillTreatedAsUpdateNotSkipped() {
        var pastStart = NOW.minusDays(1);
        var pastEnd = pastStart.plusHours(1);
        var gateFailingEvent = new SourceEvent("source-1", pastStart, pastEnd, true, false, true, true);
        var priorStates =
                List.of(new RelayState("source-1", "blocker-1", 2, pastStart, pastEnd, false, true, false, true));

        var actions = planner.plan(List.of(gateFailingEvent), priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst()).isInstanceOf(RelayAction.Update.class);
    }

    // --- Extended change detection: allDay/busy/cancelled flips alone (start/end unchanged) trigger an update ---

    @Test
    void plan_givenAllDayFlipAloneWithUnchangedWindow_thenReturnsUpdateAction() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, true, true, false, false));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst()).isInstanceOf(RelayAction.Update.class);
        assertThat(actions.getFirst().sequence()).isEqualTo(2);
    }

    @Test
    void plan_givenBusyFlipAloneWithUnchangedWindow_thenReturnsUpdateAction() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, false, false, false));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst()).isInstanceOf(RelayAction.Update.class);
    }

    @Test
    void plan_givenCancelledFlipAloneWithUnchangedWindow_thenReturnsUpdateAction() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, true, false, true));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst()).isInstanceOf(RelayAction.Update.class);
    }

    @Test
    void plan_givenAllDayBusyAndCancelledAllUnchanged_thenReturnsNoAction() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, false, true, false, false));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenUpdateAction_thenCarriesAllDayBusyAndCancelledFromCurrentEvent() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END, true, false, false, true));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false));

        var actions = planner.plan(currentEvents, priorStates, NOW, HORIZON);

        assertThat(actions).hasSize(1);
        var update = (RelayAction.Update) actions.getFirst();
        assertThat(update.allDay()).isTrue();
        assertThat(update.busy()).isFalse();
        assertThat(update.cancelled()).isTrue();
    }
}
