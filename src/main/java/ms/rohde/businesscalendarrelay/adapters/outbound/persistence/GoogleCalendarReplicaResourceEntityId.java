package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key of {@link GoogleCalendarReplicaResourceEntity}: {@code
 * (sourceCalendarId, eventId)}. Field names and types must mirror the {@code @Id} fields on
 * {@link GoogleCalendarReplicaResourceEntity} exactly, per the {@code @IdClass} contract.
 */
final class GoogleCalendarReplicaResourceEntityId implements Serializable {

    private String sourceCalendarId;
    private String eventId;

    GoogleCalendarReplicaResourceEntityId() {}

    GoogleCalendarReplicaResourceEntityId(String sourceCalendarId, String eventId) {
        this.sourceCalendarId = sourceCalendarId;
        this.eventId = eventId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GoogleCalendarReplicaResourceEntityId that)) {
            return false;
        }
        return Objects.equals(sourceCalendarId, that.sourceCalendarId) && Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceCalendarId, eventId);
    }
}
