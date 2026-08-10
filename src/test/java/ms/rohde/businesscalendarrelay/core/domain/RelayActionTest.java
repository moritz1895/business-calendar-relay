package ms.rohde.businesscalendarrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link RelayAction.Create} and {@link RelayAction.Update} construction,
 * focused on the {@code sourceTitle} field they carry (unlike {@link RelayAction.Cancel},
 * which has no such component at all — see its Javadoc). Decision-rule coverage for
 * which action type {@link RelayDiffPlanner#plan} produces lives in
 * {@code RelayDiffPlannerTest}.
 */
class RelayActionTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, BERLIN);
    private static final ZonedDateTime END = START.plusHours(1);

    @Test
    void create_givenNonNullSourceTitle_thenCreateActionCarriesIt() {
        var action = new RelayAction.Create("source-1", "blocker-1", 0, START, END, false, true, false, "Zahnarzt");

        assertThat(action.sourceTitle()).isEqualTo("Zahnarzt");
    }

    @Test
    void create_givenNullSourceTitle_thenCreateActionIsCreatedWithNullSourceTitle() {
        var action = new RelayAction.Create("source-1", "blocker-1", 0, START, END, false, true, false, null);

        assertThat(action.sourceTitle()).isNull();
    }

    @Test
    void update_givenNonNullSourceTitle_thenUpdateActionCarriesIt() {
        var action = new RelayAction.Update("source-1", "blocker-1", 1, START, END, false, true, false, "Zahnarzt");

        assertThat(action.sourceTitle()).isEqualTo("Zahnarzt");
    }

    @Test
    void update_givenNullSourceTitle_thenUpdateActionIsCreatedWithNullSourceTitle() {
        var action = new RelayAction.Update("source-1", "blocker-1", 1, START, END, false, true, false, null);

        assertThat(action.sourceTitle()).isNull();
    }

    @Test
    void cancel_givenValidData_thenCancelActionHasNoSourceTitleComponent() {
        var action = new RelayAction.Cancel("source-1", "blocker-1", 1, START, END);

        assertThat(action.sourceUid()).isEqualTo("source-1");
        assertThat(action.blockerUid()).isEqualTo("blocker-1");
    }
}
