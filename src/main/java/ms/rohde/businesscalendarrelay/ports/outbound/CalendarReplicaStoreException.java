package ms.rohde.businesscalendarrelay.ports.outbound;

/**
 * Thrown by {@link CalendarReplicaStore#applyDelta(String, java.util.List, java.util.List)}
 * and {@link CalendarReplicaStore#resetTo(String, java.util.List)} when the underlying
 * persistence operation fails. A single {@code applyDelta}/{@code resetTo} call either
 * fully succeeds or throws -- there is no partial-success return value to inspect.
 */
public class CalendarReplicaStoreException extends RuntimeException {

    public CalendarReplicaStoreException(String message) {
        super(message);
    }

    public CalendarReplicaStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
