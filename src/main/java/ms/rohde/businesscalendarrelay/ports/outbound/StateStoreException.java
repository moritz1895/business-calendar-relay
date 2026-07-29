package ms.rohde.businesscalendarrelay.ports.outbound;

/**
 * Thrown by {@link StateStore#save(ms.rohde.businesscalendarrelay.core.domain.RelayState)}
 * and {@link StateStore#markCancelled(String, long)} when the underlying persistence
 * operation fails. A single {@code save}/{@code markCancelled} call either fully
 * succeeds or throws — there is no partial-success return value to inspect.
 */
public class StateStoreException extends RuntimeException {

    public StateStoreException(String message) {
        super(message);
    }

    public StateStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
