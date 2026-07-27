package ms.rohde.businesscalendarrelay.core.app;

import java.util.List;

/**
 * Summary of one "Poll and Relay Source Calendar" cycle: which source events were
 * successfully created, updated, or cancelled, and which ones failed to send and
 * will be retried on the next poll.
 */
public record RelayCycleResult(
        List<String> created, List<String> updated, List<String> cancelled, List<RelayFailure> failed) {
}
