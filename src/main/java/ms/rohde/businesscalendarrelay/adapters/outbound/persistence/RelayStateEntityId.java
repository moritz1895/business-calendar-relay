package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key of {@link RelayStateEntity}: {@code (sourceCalendarId,
 * sourceUid)}. Field names and types must mirror the {@code @Id} fields on
 * {@link RelayStateEntity} exactly, per the {@code @IdClass} contract.
 */
final class RelayStateEntityId implements Serializable {

    private String sourceCalendarId;
    private String sourceUid;

    RelayStateEntityId() {}

    RelayStateEntityId(String sourceCalendarId, String sourceUid) {
        this.sourceCalendarId = sourceCalendarId;
        this.sourceUid = sourceUid;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RelayStateEntityId that)) {
            return false;
        }
        return Objects.equals(sourceCalendarId, that.sourceCalendarId) && Objects.equals(sourceUid, that.sourceUid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceCalendarId, sourceUid);
    }
}
