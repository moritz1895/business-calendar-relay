package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key of {@link CalendarReplicaResourceEntity}: {@code (sourceCalendarId,
 * href)}. Field names and types must mirror the {@code @Id} fields on
 * {@link CalendarReplicaResourceEntity} exactly, per the {@code @IdClass} contract.
 */
final class CalendarReplicaResourceEntityId implements Serializable {

    private String sourceCalendarId;
    private String href;

    CalendarReplicaResourceEntityId() {}

    CalendarReplicaResourceEntityId(String sourceCalendarId, String href) {
        this.sourceCalendarId = sourceCalendarId;
        this.href = href;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CalendarReplicaResourceEntityId that)) {
            return false;
        }
        return Objects.equals(sourceCalendarId, that.sourceCalendarId) && Objects.equals(href, that.href);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceCalendarId, href);
    }
}
