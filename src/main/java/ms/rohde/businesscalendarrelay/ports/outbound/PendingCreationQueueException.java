package ms.rohde.businesscalendarrelay.ports.outbound;

/**
 * Thrown by {@link PendingCreationQueue#saveAll(java.util.List)} and
 * {@link PendingCreationQueue#remove(String)} when the underlying persistence operation
 * fails. A single {@code saveAll}/{@code remove} call either fully succeeds or throws —
 * there is no partial-success return value to inspect.
 */
public class PendingCreationQueueException extends RuntimeException {

    public PendingCreationQueueException(String message) {
        super(message);
    }

    public PendingCreationQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
