package ms.rohde.businesscalendarrelay.core.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import ms.rohde.businesscalendarrelay.core.domain.RelayDiffPlanner;
import ms.rohde.businesscalendarrelay.core.domain.RelayState;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMail;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMailMethod;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSinkException;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarSource;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Orchestration wiring for {@link PollAndRelaySourceCalendarService}: given a
 * {@link RelayDiffPlanner}-driven decision, verifies rendering, sending, and
 * persistence happen through the right ports. Fine-grained create/update/no-op/cancel/
 * resurrection decision-rule coverage lives in {@code RelayDiffPlannerTest}, since that
 * decision is now pure domain logic independent of these mocked ports.
 */
@ExtendWith(MockitoExtension.class)
class PollAndRelaySourceCalendarServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, BERLIN);
    private static final ZonedDateTime END = START.plusHours(1);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-27T09:00:00Z"), ZoneOffset.UTC);
    private static final String ORGANIZER = "organizer@example.com";
    private static final String ATTENDEE = "business@example.com";
    private static final String FROM = "relay@example.com";
    private static final String REPLY_TO = "organizer@example.com";

    @Mock
    private CalendarSource calendarSource;

    @Mock
    private BlockerSink blockerSink;

    @Mock
    private StateStore stateStore;

    private PollAndRelaySourceCalendarService service;

    @BeforeEach
    void setUp() {
        service = new PollAndRelaySourceCalendarService(
                calendarSource, blockerSink, stateStore, ORGANIZER, ATTENDEE, FROM, REPLY_TO, CLOCK);
    }

    @Test
    void pollAndRelay_givenNewSourceEvent_thenCreatesBlockerAndSavesState() {
        given(calendarSource.readEvents()).willReturn(List.of(new SourceEvent("source-1", START, END)));
        given(stateStore.loadAll()).willReturn(List.of());

        var result = service.pollAndRelay();

        assertThat(result.created()).containsExactly("source-1");
        assertThat(result.updated()).isEmpty();
        assertThat(result.cancelled()).isEmpty();
        assertThat(result.failed()).isEmpty();

        var mailCaptor = ArgumentCaptor.forClass(BlockerMail.class);
        then(blockerSink).should().send(mailCaptor.capture());
        var mail = mailCaptor.getValue();
        assertThat(mail.method()).isEqualTo(BlockerMailMethod.REQUEST);
        assertThat(mail.fromAddress()).isEqualTo(FROM);
        assertThat(mail.replyToAddress()).isEqualTo(REPLY_TO);
        assertThat(mail.toAddress()).isEqualTo(ATTENDEE);
        assertThat(mail.icsText()).contains("METHOD:REQUEST").contains("SEQUENCE:0");

        var stateCaptor = ArgumentCaptor.forClass(RelayState.class);
        then(stateStore).should().save(stateCaptor.capture());
        var saved = stateCaptor.getValue();
        assertThat(saved.sourceUid()).isEqualTo("source-1");
        assertThat(saved.sequence()).isZero();
        assertThat(saved.active()).isTrue();
        assertThat(saved.lastKnownStart()).isEqualTo(START);
        assertThat(saved.lastKnownEnd()).isEqualTo(END);
        assertThat(mail.icsText()).contains("UID:" + saved.blockerUid());
    }

    @Test
    void pollAndRelay_givenChangedWindow_thenUpdatesBlockerAndIncrementsSequence() {
        var newEnd = END.plusMinutes(30);
        given(calendarSource.readEvents()).willReturn(List.of(new SourceEvent("source-1", START, newEnd)));
        given(stateStore.loadAll()).willReturn(List.of(new RelayState("source-1", "blocker-1", 2, START, END, true)));

        var result = service.pollAndRelay();

        assertThat(result.updated()).containsExactly("source-1");
        assertThat(result.created()).isEmpty();
        assertThat(result.cancelled()).isEmpty();
        assertThat(result.failed()).isEmpty();

        var mailCaptor = ArgumentCaptor.forClass(BlockerMail.class);
        then(blockerSink).should().send(mailCaptor.capture());
        assertThat(mailCaptor.getValue().method()).isEqualTo(BlockerMailMethod.REQUEST);
        assertThat(mailCaptor.getValue().icsText()).contains("UID:blocker-1").contains("SEQUENCE:3");

        var stateCaptor = ArgumentCaptor.forClass(RelayState.class);
        then(stateStore).should().save(stateCaptor.capture());
        var saved = stateCaptor.getValue();
        assertThat(saved.blockerUid()).isEqualTo("blocker-1");
        assertThat(saved.sequence()).isEqualTo(3);
        assertThat(saved.lastKnownEnd()).isEqualTo(newEnd);
        assertThat(saved.active()).isTrue();
    }

    @Test
    void pollAndRelay_givenUnchangedWindow_thenNoOp() {
        given(calendarSource.readEvents()).willReturn(List.of(new SourceEvent("source-1", START, END)));
        given(stateStore.loadAll()).willReturn(List.of(new RelayState("source-1", "blocker-1", 1, START, END, true)));

        var result = service.pollAndRelay();

        assertThat(result.created()).isEmpty();
        assertThat(result.updated()).isEmpty();
        assertThat(result.cancelled()).isEmpty();
        assertThat(result.failed()).isEmpty();

        then(blockerSink).shouldHaveNoInteractions();
        then(stateStore).should(never()).save(any());
        then(stateStore).should(never()).markCancelled(any(), anyLong());
    }

    @Test
    void pollAndRelay_givenDisappearedSourceEvent_thenCancelsBlocker() {
        given(calendarSource.readEvents()).willReturn(List.of());
        given(stateStore.loadAll()).willReturn(List.of(new RelayState("source-1", "blocker-1", 1, START, END, true)));

        var result = service.pollAndRelay();

        assertThat(result.cancelled()).containsExactly("source-1");
        assertThat(result.created()).isEmpty();
        assertThat(result.updated()).isEmpty();
        assertThat(result.failed()).isEmpty();

        var mailCaptor = ArgumentCaptor.forClass(BlockerMail.class);
        then(blockerSink).should().send(mailCaptor.capture());
        assertThat(mailCaptor.getValue().method()).isEqualTo(BlockerMailMethod.CANCEL);
        assertThat(mailCaptor.getValue().icsText())
                .contains("UID:blocker-1")
                .contains("SEQUENCE:2")
                .contains("METHOD:CANCEL");

        then(stateStore).should().markCancelled("source-1", 2);
        then(stateStore).should(never()).save(any());
    }

    @Test
    void pollAndRelay_givenOneSendFailureAmongSeveral_thenContinuesCycleAndReportsFailureWithoutUpdatingState() {
        given(calendarSource.readEvents())
                .willReturn(List.of(
                        new SourceEvent("source-fail", START, END), new SourceEvent("source-ok", START, END)));
        given(stateStore.loadAll()).willReturn(List.of());

        var failure = new BlockerSinkException("smtp down");
        var callCount = new AtomicInteger();
        willAnswer(invocation -> {
                    if (callCount.incrementAndGet() == 1) {
                        throw failure;
                    }
                    return null;
                })
                .given(blockerSink)
                .send(any());

        var result = service.pollAndRelay();

        assertThat(result.created()).containsExactly("source-ok");
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().getFirst().sourceUid()).isEqualTo("source-fail");
        assertThat(result.failed().getFirst().cause()).isEqualTo(failure);

        then(stateStore).should(times(1)).save(any());
        then(stateStore).should(never()).markCancelled(any(), anyLong());
    }
}
