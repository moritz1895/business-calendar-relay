package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.List;
import ms.rohde.businesscalendarrelay.core.domain.RelayState;
import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;

/**
 * Durable storage of relay bookkeeping for one configured source calendar.
 *
 * <p>One configured instance per source calendar.
 */
@InfrastructureServicePort
public interface StateStore {

    /**
     * Returns every known {@link RelayState} for this source calendar, active and
     * cancelled alike. Read once at the start of a poll cycle and used as the diff
     * baseline.
     */
    List<RelayState> loadAll();

    /**
     * Upserts one relay state, keyed by {@link RelayState#sourceUid()}. Used after a
     * successful create or update send, with {@code active = true}.
     */
    void save(RelayState state);

    /**
     * Records that a {@code CANCEL} was sent at {@code sequence} for {@code sourceUid}.
     * The stored {@code blockerUid} is unchanged; {@code active} becomes {@code false}.
     * Deliberately not a delete.
     */
    void markCancelled(String sourceUid, long sequence);
}
