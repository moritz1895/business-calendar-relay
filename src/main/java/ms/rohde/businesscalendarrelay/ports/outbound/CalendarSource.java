package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.List;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;

/**
 * Read-only access to one configured private source calendar.
 *
 * <p>One configured instance per source calendar.
 */
@InfrastructureServicePort
public interface CalendarSource {

    /**
     * Returns the full current set of {@link SourceEvent}s in the configured calendar,
     * as of now. Always a full snapshot, never a delta.
     */
    List<SourceEvent> readEvents();
}
