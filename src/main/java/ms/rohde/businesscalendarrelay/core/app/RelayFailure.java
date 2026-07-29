package ms.rohde.businesscalendarrelay.core.app;

/**
 * One source event for which either the {@code BlockerSink.send} call, or the
 * subsequent {@code StateStore.save}/{@code StateStore.markCancelled} call, failed
 * during a poll cycle. Left untouched (or only partially updated) in {@code StateStore};
 * will be retried as the same create/update/cancel decision on the next poll.
 */
public record RelayFailure(String sourceUid, Throwable cause) {
}
