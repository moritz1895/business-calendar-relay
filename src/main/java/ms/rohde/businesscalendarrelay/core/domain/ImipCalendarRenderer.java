package ms.rohde.businesscalendarrelay.core.domain;

import java.time.Instant;
import ms.rohde.hexagonalarch.annotations.DomainService;

/**
 * Renders {@link BlockerEvent} instances into raw iCalendar ({@code BEGIN:VCALENDAR
 * ... END:VCALENDAR}) text for the two iMIP methods this service emits.
 *
 * <p>The rendered blocker is deliberately titleless: {@code SUMMARY} is always the
 * fixed literal {@code "Privater Blocker"}, never derived from the source event.
 *
 * <p>{@code generatedAt} drives {@code DTSTAMP} and is supplied by the caller rather
 * than read from a clock here, keeping this domain service pure and deterministic.
 */
@DomainService
public final class ImipCalendarRenderer {

    /**
     * Renders a {@code METHOD:REQUEST} VCALENDAR for a blocker create or update.
     */
    public String renderRequest(BlockerEvent event, Instant generatedAt) {
        throw new UnsupportedOperationException("ICS REQUEST rendering not yet implemented");
    }

    /**
     * Renders a {@code METHOD:CANCEL} VCALENDAR for a blocker cancel.
     */
    public String renderCancel(BlockerEvent event, Instant generatedAt) {
        throw new UnsupportedOperationException("ICS CANCEL rendering not yet implemented");
    }
}
