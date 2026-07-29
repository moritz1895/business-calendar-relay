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
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import ms.rohde.businesscalendarrelay.core.domain.RelayAction;
import ms.rohde.businesscalendarrelay.core.domain.RelayDiffPlanner;
import ms.rohde.businesscalendarrelay.core.domain.RelayState;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMail;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerMailMethod;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSinkException;
import ms.rohde.businesscalendarrelay.ports.outbound.BurstBudget;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarSource;
import ms.rohde.businesscalendarrelay.ports.outbound.PendingCreationQueue;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStore;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T09:00:00Z"), ZoneOffset.UTC);
    private static final Period RECURRING_EVENT_HORIZON = Period.ofMonths(6);
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

    @Mock
    private PendingCreationQueue pendingCreationQueue;

    @Mock
    private BurstBudget burstBudget;

    @Captor
    private ArgumentCaptor<List<RelayAction.Create>> pendingQueueCaptor;

    private PollAndRelaySourceCalendarService service;

    @BeforeEach
    void setUp() {
        service = new PollAndRelaySourceCalendarService(
                calendarSource,
                blockerSink,
                stateStore,
                pendingCreationQueue,
                burstBudget,
                ORGANIZER,
                ATTENDEE,
                FROM,
                REPLY_TO,
                CLOCK,
                RECURRING_EVENT_HORIZON);
    }

    private static RelayAction.Create createAction(String sourceUid, ZonedDateTime start) {
        return new RelayAction.Create(
                sourceUid, "blocker-" + sourceUid, 0, start, start.plusHours(1), false, true, false);
    }

    @Test
    void pollAndRelay_givenNewSourceEvent_thenCreatesBlockerAndSavesState() {
        given(calendarSource.readEvents())
                .willReturn(List.of(new SourceEvent("source-1", START, END, false, true, false, false)));
        given(stateStore.loadAll()).willReturn(List.of());
        given(burstBudget.tryAcquireSendSlot()).willReturn(true);

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
        assertThat(saved.lastKnownAllDay()).isFalse();
        assertThat(saved.lastKnownBusy()).isTrue();
        assertThat(saved.lastKnownCancelled()).isFalse();
        assertThat(mail.icsText()).contains("UID:" + saved.blockerUid());
    }

    @Test
    void pollAndRelay_givenNewEventWithStartBeforeClockNow_thenNoActionIsTakenAndNothingIsSaved() {
        var pastStart = ZonedDateTime.of(2026, 7, 15, 10, 0, 0, 0, BERLIN);
        given(calendarSource.readEvents())
                .willReturn(List.of(new SourceEvent(
                        "source-1", pastStart, pastStart.plusHours(1), false, true, false, false)));
        given(stateStore.loadAll()).willReturn(List.of());

        var result = service.pollAndRelay();

        assertThat(result.created()).isEmpty();
        assertThat(result.updated()).isEmpty();
        assertThat(result.cancelled()).isEmpty();
        assertThat(result.failed()).isEmpty();
        then(blockerSink).shouldHaveNoInteractions();
        then(stateStore).should(never()).save(any());
    }

    @Test
    void pollAndRelay_givenNewRecurringEventBeyondConfiguredHorizon_thenNoActionIsTakenAndNothingIsSaved() {
        var beyondHorizonStart = ZonedDateTime.of(2027, 6, 1, 10, 0, 0, 0, BERLIN);
        var recurringEvent =
                new SourceEvent("source-1", beyondHorizonStart, beyondHorizonStart.plusHours(1), false, true, true, false);
        given(calendarSource.readEvents()).willReturn(List.of(recurringEvent));
        given(stateStore.loadAll()).willReturn(List.of());

        var result = service.pollAndRelay();

        assertThat(result.created()).isEmpty();
        then(blockerSink).shouldHaveNoInteractions();
        then(stateStore).should(never()).save(any());
    }

    @Test
    void pollAndRelay_givenChangedWindow_thenUpdatesBlockerAndIncrementsSequence() {
        var newEnd = END.plusMinutes(30);
        given(calendarSource.readEvents())
                .willReturn(List.of(new SourceEvent("source-1", START, newEnd, false, true, false, false)));
        given(stateStore.loadAll())
                .willReturn(List.of(new RelayState("source-1", "blocker-1", 2, START, END, true, false, true, false)));

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
    void pollAndRelay_givenFlagOnlyChangeOnActiveState_thenSavesRelayStateWithCurrentAllDayBusyCancelledFlags() {
        var flaggedEvent = new SourceEvent("source-1", START, END, true, false, false, true);
        given(calendarSource.readEvents()).willReturn(List.of(flaggedEvent));
        given(stateStore.loadAll())
                .willReturn(
                        List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false)));

        var result = service.pollAndRelay();

        assertThat(result.updated()).containsExactly("source-1");

        var stateCaptor = ArgumentCaptor.forClass(RelayState.class);
        then(stateStore).should().save(stateCaptor.capture());
        var saved = stateCaptor.getValue();
        assertThat(saved.lastKnownAllDay()).isTrue();
        assertThat(saved.lastKnownBusy()).isFalse();
        assertThat(saved.lastKnownCancelled()).isTrue();
    }

    @Test
    void pollAndRelay_givenUnchangedWindow_thenNoOp() {
        given(calendarSource.readEvents())
                .willReturn(List.of(new SourceEvent("source-1", START, END, false, true, false, false)));
        given(stateStore.loadAll())
                .willReturn(List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false)));

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
        given(stateStore.loadAll())
                .willReturn(List.of(new RelayState("source-1", "blocker-1", 1, START, END, true, false, true, false)));

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
                        new SourceEvent("source-fail", START, END, false, true, false, false),
                        new SourceEvent("source-ok", START, END, false, true, false, false)));
        given(stateStore.loadAll()).willReturn(List.of());
        given(burstBudget.tryAcquireSendSlot()).willReturn(true);

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

    @Test
    void pollAndRelay_givenOneStateSaveFailureAmongSeveral_thenContinuesCycleAndReportsFailureWithoutThrowing() {
        given(calendarSource.readEvents())
                .willReturn(List.of(
                        new SourceEvent("source-fail", START, END, false, true, false, false),
                        new SourceEvent("source-ok", START, END, false, true, false, false)));
        given(stateStore.loadAll()).willReturn(List.of());
        given(burstBudget.tryAcquireSendSlot()).willReturn(true);

        var failure = new StateStoreException("db unavailable");
        var callCount = new AtomicInteger();
        willAnswer(invocation -> {
                    if (callCount.incrementAndGet() == 1) {
                        throw failure;
                    }
                    return null;
                })
                .given(stateStore)
                .save(any());

        var result = service.pollAndRelay();

        assertThat(result.created()).containsExactly("source-ok");
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().getFirst().sourceUid()).isEqualTo("source-fail");
        assertThat(result.failed().getFirst().cause()).isEqualTo(failure);

        then(blockerSink).should(times(2)).send(any());
        then(stateStore).should(times(2)).save(any());
    }

    // --- Burst-filter initialization (issue #16): capture-and-drain of a fresh calendar's backlog ---

    @Test
    void pollAndRelay_givenFirstEverCycleWithMultipleEligibleEvents_thenCapturesQueueAndDrainsUpToBudget() {
        var laterStart = START.plusDays(1);
        given(calendarSource.readEvents())
                .willReturn(List.of(
                        new SourceEvent("source-later", laterStart, laterStart.plusHours(1), false, true, false, false),
                        new SourceEvent("source-earlier", START, END, false, true, false, false)));
        given(stateStore.loadAll()).willReturn(List.of());
        given(pendingCreationQueue.loadAllOrderedByStart()).willReturn(List.of());
        given(burstBudget.tryAcquireSendSlot()).willReturn(true, false);

        var result = service.pollAndRelay();

        assertThat(result.created()).containsExactly("source-earlier");
        assertThat(result.failed()).isEmpty();

        then(pendingCreationQueue).should().saveAll(pendingQueueCaptor.capture());
        assertThat(pendingQueueCaptor.getValue())
                .extracting(RelayAction.Create::sourceUid)
                .containsExactly("source-earlier", "source-later");

        then(blockerSink).should(times(1)).send(any());
        then(stateStore).should(times(1)).save(any());
        then(pendingCreationQueue).should().remove("source-earlier");
        then(pendingCreationQueue).should(never()).remove("source-later");
    }

    @Test
    void pollAndRelay_givenBudgetExhaustedMidDrain_thenStopsImmediatelyLeavingRemainingItemsUntouched() {
        var item1 = createAction("source-1", START);
        var item2 = createAction("source-2", START.plusDays(1));
        var item3 = createAction("source-3", START.plusDays(2));
        given(pendingCreationQueue.loadAllOrderedByStart()).willReturn(List.of(item1, item2, item3));
        given(stateStore.loadAll()).willReturn(List.of());
        given(burstBudget.tryAcquireSendSlot()).willReturn(true, false);

        var result = service.pollAndRelay();

        assertThat(result.created()).containsExactly("source-1");
        then(blockerSink).should(times(1)).send(any());
        then(pendingCreationQueue).should().remove("source-1");
        then(pendingCreationQueue).should(never()).remove("source-2");
        then(pendingCreationQueue).should(never()).remove("source-3");
    }

    @Test
    void pollAndRelay_givenNonEmptyPendingQueue_thenDoesNotInvokeCalendarSourceReadEvents() {
        given(pendingCreationQueue.loadAllOrderedByStart()).willReturn(List.of(createAction("source-1", START)));
        given(stateStore.loadAll()).willReturn(List.of());
        given(burstBudget.tryAcquireSendSlot()).willReturn(true);

        service.pollAndRelay();

        then(calendarSource).shouldHaveNoInteractions();
    }

    @Test
    void pollAndRelay_givenQueueItemAlreadyHasRelayState_thenRemovesWithoutResendingAndProcessesRestNormally() {
        var alreadySentItem = createAction("source-1", START);
        var pendingItem = createAction("source-2", START.plusDays(1));
        given(pendingCreationQueue.loadAllOrderedByStart()).willReturn(List.of(alreadySentItem, pendingItem));
        given(stateStore.loadAll())
                .willReturn(List.of(
                        new RelayState("source-1", "blocker-source-1", 0, START, END, true, false, true, false)));
        given(burstBudget.tryAcquireSendSlot()).willReturn(true);

        var result = service.pollAndRelay();

        assertThat(result.created()).containsExactly("source-2");
        then(blockerSink).should(times(1)).send(any());
        then(stateStore).should(times(1)).save(any());
        then(pendingCreationQueue).should().remove("source-1");
        then(pendingCreationQueue).should().remove("source-2");
    }

    @Test
    void pollAndRelay_givenQueueItemNowPastCreationCutoff_thenRemovesWithoutSendingAndWithoutConsumingBudget() {
        var staleStart = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, BERLIN);
        var staleItem = createAction("source-stale", staleStart);
        var freshItem = createAction("source-fresh", START);
        given(pendingCreationQueue.loadAllOrderedByStart()).willReturn(List.of(staleItem, freshItem));
        given(stateStore.loadAll()).willReturn(List.of());
        given(burstBudget.tryAcquireSendSlot()).willReturn(true);

        var result = service.pollAndRelay();

        assertThat(result.created()).containsExactly("source-fresh");
        assertThat(result.failed()).isEmpty();
        then(blockerSink).should(times(1)).send(any());
        then(burstBudget).should(times(1)).tryAcquireSendSlot();
        then(pendingCreationQueue).should().remove("source-stale");
        then(pendingCreationQueue).should().remove("source-fresh");
    }
}
