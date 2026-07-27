package ms.rohde.businesscalendarrelay.core.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    private static final String CRLF = "\r\n";

    private static final String PRODID = "-//business-calendar-relay//iMIP Relay//EN";

    private static final int MAX_LINE_OCTETS = 75;

    private static final DateTimeFormatter UTC_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter LOCAL_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private static final String BERLIN_VTIMEZONE = String.join(CRLF,
            "BEGIN:VTIMEZONE",
            "TZID:Europe/Berlin",
            "BEGIN:DAYLIGHT",
            "TZNAME:CEST",
            "TZOFFSETFROM:+0100",
            "TZOFFSETTO:+0200",
            "DTSTART:19700329T020000",
            "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
            "END:DAYLIGHT",
            "BEGIN:STANDARD",
            "TZNAME:CET",
            "TZOFFSETFROM:+0200",
            "TZOFFSETTO:+0100",
            "DTSTART:19701025T030000",
            "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
            "END:STANDARD",
            "END:VTIMEZONE");

    /**
     * Renders a {@code METHOD:REQUEST} VCALENDAR for a blocker create or update.
     */
    public String renderRequest(BlockerEvent event, Instant generatedAt) {
        return render(event, generatedAt, "REQUEST",
                "ATTENDEE;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:"
                        + event.attendeeEmail());
    }

    /**
     * Renders a {@code METHOD:CANCEL} VCALENDAR for a blocker cancel.
     */
    public String renderCancel(BlockerEvent event, Instant generatedAt) {
        return render(event, generatedAt, "CANCEL",
                "ATTENDEE;CUTYPE=INDIVIDUAL:mailto:" + event.attendeeEmail());
    }

    private String render(BlockerEvent event, Instant generatedAt, String method, String attendeeLine) {
        var ics = new StringBuilder();
        appendFolded(ics, "BEGIN:VCALENDAR");
        appendFolded(ics, "VERSION:2.0");
        appendFolded(ics, "PRODID:" + PRODID);
        appendFolded(ics, "CALSCALE:GREGORIAN");
        appendFolded(ics, "METHOD:" + method);
        ics.append(BERLIN_VTIMEZONE).append(CRLF);
        appendFolded(ics, "BEGIN:VEVENT");
        appendFolded(ics, "UID:" + event.uid());
        appendFolded(ics, "DTSTAMP:" + UTC_TIMESTAMP_FORMAT.format(generatedAt));
        appendFolded(ics, "DTSTART;TZID=" + event.zone() + ":" + LOCAL_TIMESTAMP_FORMAT.format(event.start()));
        appendFolded(ics, "DTEND;TZID=" + event.zone() + ":" + LOCAL_TIMESTAMP_FORMAT.format(event.end()));
        appendFolded(ics, "STATUS:CONFIRMED");
        appendFolded(ics, "SUMMARY:Privater Blocker");
        appendFolded(ics, "ORGANIZER:mailto:" + event.organizerEmail());
        appendFolded(ics, "SEQUENCE:" + event.sequence());
        appendFolded(ics, attendeeLine);
        appendFolded(ics, "END:VEVENT");
        appendFolded(ics, "END:VCALENDAR");
        return ics.toString();
    }

    private void appendFolded(StringBuilder target, String logicalLine) {
        target.append(fold(logicalLine)).append(CRLF);
    }

    private String fold(String logicalLine) {
        if (logicalLine.getBytes(StandardCharsets.UTF_8).length <= MAX_LINE_OCTETS) {
            return logicalLine;
        }

        var folded = new StringBuilder();
        var currentLineOctets = 0;
        var codePointIndex = 0;
        while (codePointIndex < logicalLine.length()) {
            var codePoint = logicalLine.codePointAt(codePointIndex);
            var chunk = new String(Character.toChars(codePoint));
            var chunkOctets = chunk.getBytes(StandardCharsets.UTF_8).length;

            if (currentLineOctets + chunkOctets > MAX_LINE_OCTETS) {
                folded.append(CRLF).append(' ');
                currentLineOctets = 1;
            }

            folded.append(chunk);
            currentLineOctets += chunkOctets;
            codePointIndex += Character.charCount(codePoint);
        }

        return folded.toString();
    }
}
