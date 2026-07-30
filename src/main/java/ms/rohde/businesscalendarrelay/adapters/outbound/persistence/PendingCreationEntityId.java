package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key of {@link PendingCreationEntity}: {@code (sourceCalendarId,
 * sourceUid)}. Field names and types must mirror the {@code @Id} fields on
 * {@link PendingCreationEntity} exactly, per the {@code @IdClass} contract.
 */
final class PendingCreationEntityId implements Serializable {

    private String sourceCalendarId;
    private String sourceUid;

    PendingCreationEntityId() {}

    PendingCreationEntityId(String sourceCalendarId, String sourceUid) {
        this.sourceCalendarId = sourceCalendarId;
        this.sourceUid = sourceUid;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PendingCreationEntityId that)) {
            return false;
        }
        return Objects.equals(sourceCalendarId, that.sourceCalendarId) && Objects.equals(sourceUid, that.sourceUid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceCalendarId, sourceUid);
    }
}
