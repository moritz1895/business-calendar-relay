package ms.rohde.businesscalendarrelay.ports.inbound;

import ms.rohde.businesscalendarrelay.core.app.RelayCycleResult;
import ms.rohde.hexagonalarch.annotations.DrivingPort;

/**
 * Driving port for the "Poll and Relay Source Calendar" use case: brings one configured
 * source calendar's blockers in the business calendar back in sync with that source
 * calendar's current events.
 *
 * <p>One implementation instance per configured source calendar, per
 * {@code docs/features/relay-orchestration.md}. Driving adapters (e.g. the scheduler)
 * must depend on this port, never on the concrete application service.
 */
@DrivingPort
public interface PollAndRelaySourceCalendarUseCase {

    /**
     * Runs one poll cycle for this use case instance's configured source calendar.
     */
    RelayCycleResult pollAndRelay();
}
