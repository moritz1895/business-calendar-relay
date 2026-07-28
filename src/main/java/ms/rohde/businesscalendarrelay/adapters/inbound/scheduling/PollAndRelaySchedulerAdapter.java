package ms.rohde.businesscalendarrelay.adapters.inbound.scheduling;

import java.time.Duration;
import java.util.List;
import ms.rohde.businesscalendarrelay.core.app.RelayCycleResult;
import ms.rohde.businesscalendarrelay.core.app.RelayFailure;
import ms.rohde.businesscalendarrelay.ports.inbound.PollAndRelaySourceCalendarUseCase;
import ms.rohde.hexagonalarch.annotations.DrivingAdapter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

/**
 * The scheduler: the only actor driving {@link PollAndRelaySourceCalendarUseCase}, per
 * {@code docs/features/relay-orchestration.md}. Schedules one fixed-delay poll cycle per
 * configured source calendar, once per {@code relay.poll-interval}.
 *
 * <p>Use-case instances are built dynamically from a runtime-sized calendar list (see
 * {@code RelayWiringConfiguration}), so a fixed set of {@code @Scheduled} methods does
 * not fit -- scheduling is programmatic instead, driven by an injected
 * {@link TaskScheduler}.
 *
 * <p>Scheduling starts on {@link ApplicationReadyEvent}, not during bean construction or
 * {@code @PostConstruct}, so the very first poll cycle only ever runs once the whole
 * application context -- including every other bean it might depend on indirectly -- is
 * fully up.
 *
 * <p>This is the only place with visibility into poll outcomes, so every cycle's
 * {@link RelayCycleResult} is logged here: INFO for a clean cycle, WARN naming every
 * failed {@code sourceUid} when {@link RelayCycleResult#failed()} is non-empty.
 */
@DrivingAdapter
public class PollAndRelaySchedulerAdapter {

    private static final Logger LOG = LogManager.getLogger(PollAndRelaySchedulerAdapter.class);

    private final TaskScheduler taskScheduler;
    private final List<PollAndRelaySourceCalendarUseCase> useCases;
    private final Duration pollInterval;

    public PollAndRelaySchedulerAdapter(
            TaskScheduler taskScheduler,
            List<PollAndRelaySourceCalendarUseCase> useCases,
            @Value("${relay.poll-interval}") Duration pollInterval) {
        this.taskScheduler = taskScheduler;
        this.useCases = useCases;
        this.pollInterval = pollInterval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void schedulePollCycles() {
        for (var useCase : useCases) {
            taskScheduler.scheduleWithFixedDelay(() -> runPollCycle(useCase), pollInterval);
        }
        LOG.info("Scheduled {} source calendar(s) for polling every {}", useCases.size(), pollInterval);
    }

    private void runPollCycle(PollAndRelaySourceCalendarUseCase useCase) {
        try {
            logResult(useCase.pollAndRelay());
        } catch (RuntimeException e) {
            LOG.warn("Poll cycle aborted unexpectedly", e);
        }
    }

    private void logResult(RelayCycleResult result) {
        if (result.failed().isEmpty()) {
            LOG.info(
                    "Poll cycle completed: created={}, updated={}, cancelled={}",
                    result.created().size(),
                    result.updated().size(),
                    result.cancelled().size());
            return;
        }

        LOG.warn(
                "Poll cycle completed with failures: created={}, updated={}, cancelled={}, failedSourceUids={}",
                result.created().size(),
                result.updated().size(),
                result.cancelled().size(),
                result.failed().stream().map(RelayFailure::sourceUid).toList());
    }
}
