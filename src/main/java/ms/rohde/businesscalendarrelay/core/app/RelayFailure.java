package ms.rohde.businesscalendarrelay.core.app;

/**
 * One source event whose {@code BlockerSink.send} call failed during a poll cycle.
 * Left untouched in {@code StateStore}; will be retried as the same create/update/
 * cancel decision on the next poll.
 */
public record RelayFailure(String sourceUid, Throwable cause) {
}
