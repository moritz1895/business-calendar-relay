package ms.rohde.businesscalendarrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayDiffPlannerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, BERLIN);
    private static final ZonedDateTime END = START.plusHours(1);

    private final RelayDiffPlanner planner = new RelayDiffPlanner();

    @Test
    void plan_givenNewSourceEvent_thenReturnsCreateActionWithFreshBlockerUidAndSequenceZero() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END));

        var actions = planner.plan(currentEvents, List.of());

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
    void plan_givenTwoNewSourceEvents_thenGeneratesDistinctBlockerUids() {
        var currentEvents = List.of(new SourceEvent("source-1", START, END), new SourceEvent("source-2", START, END));

        var actions = planner.plan(currentEvents, List.of());

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).blockerUid()).isNotEqualTo(actions.get(1).blockerUid());
    }

    @Test
    void plan_givenChangedWindow_thenReturnsUpdateActionReusingBlockerUidAndIncrementingSequence() {
        var newEnd = END.plusMinutes(30);
        var currentEvents = List.of(new SourceEvent("source-1", START, newEnd));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 2, START, END, true));

        var actions = planner.plan(currentEvents, priorStates);

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
        var currentEvents = List.of(new SourceEvent("source-1", START, END));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true));

        var actions = planner.plan(currentEvents, priorStates);

        assertThat(actions).isEmpty();
    }

    @Test
    void plan_givenDisappearedActiveSourceEvent_thenReturnsCancelActionReusingBlockerUidAndIncrementingSequence() {
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 1, START, END, true));

        var actions = planner.plan(List.of(), priorStates);

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
        var currentEvents = List.of(new SourceEvent("source-1", START, END));
        var priorStates = List.of(new RelayState("source-1", "blocker-1", 3, START, END, false));

        var actions = planner.plan(currentEvents, priorStates);

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
                new SourceEvent("source-new", START, END),
                new SourceEvent("source-changed", START, END.plusMinutes(15)),
                new SourceEvent("source-unchanged", START, END));
        var priorStates = List.of(
                new RelayState("source-changed", "blocker-changed", 0, START, END, true),
                new RelayState("source-unchanged", "blocker-unchanged", 0, START, END, true),
                new RelayState("source-gone", "blocker-gone", 5, START, END, true));

        var actions = planner.plan(currentEvents, priorStates);

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
}
