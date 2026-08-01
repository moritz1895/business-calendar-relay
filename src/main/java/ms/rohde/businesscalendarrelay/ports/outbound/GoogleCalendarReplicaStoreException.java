package ms.rohde.businesscalendarrelay.ports.outbound;

/**
 * Thrown by {@link GoogleCalendarReplicaStore#applyDelta(String, java.util.List, java.util.List)}
 * and {@link GoogleCalendarReplicaStore#resetTo(String, java.util.List)} when the underlying
 * persistence operation fails. A single {@code applyDelta}/{@code resetTo} call either fully
 * succeeds or throws -- there is no partial-success return value to inspect.
 */
public class GoogleCalendarReplicaStoreException extends RuntimeException {

    public GoogleCalendarReplicaStoreException(String message) {
        super(message);
    }

    public GoogleCalendarReplicaStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
