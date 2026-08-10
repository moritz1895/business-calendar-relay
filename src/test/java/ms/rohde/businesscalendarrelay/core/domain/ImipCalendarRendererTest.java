package ms.rohde.businesscalendarrelay.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact ICS/iMIP text structure {@link ImipCalendarRenderer} must produce,
 * against the structural facts verified from the captured Nextcloud reference mails
 * (see {@code docs/reference/*.eml} and the project {@code CLAUDE.md}).
 *
 * <p>The renderer itself is not yet implemented (methods throw
 * {@link UnsupportedOperationException}), so every test below is expected to fail
 * red until that implementation lands.
 */
class ImipCalendarRendererTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, BERLIN);
    private static final ZonedDateTime END = START.plusHours(1);
    private static final String ORGANIZER_EMAIL = "relay@example.com";
    private static final String ATTENDEE_EMAIL = "business@example.com";
    private static final Instant GENERATED_AT = Instant.parse("2026-07-23T08:00:00Z");

    private static final String EXPECTED_BERLIN_VTIMEZONE = String.join("\r\n",
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

    private final ImipCalendarRenderer renderer = new ImipCalendarRenderer();

    private static BlockerEvent blockerEvent(String uid, long sequence, ZonedDateTime start, ZonedDateTime end,
            String attendeeEmail) {
        return new BlockerEvent(uid, sequence, start, end, ORGANIZER_EMAIL, attendeeEmail);
    }

    private static BlockerEvent blockerEvent(String uid, long sequence) {
        return blockerEvent(uid, sequence, START, END, ATTENDEE_EMAIL);
    }

    /**
     * Splits raw ICS text on CRLF, then rejoins RFC 5545 folded continuation lines
     * (a single leading space) back onto their logical property line.
     */
    private static List<String> logicalLines(String ics) {
        String[] rawLines = ics.split("\r\n", -1);
        List<String> logical = new ArrayList<>();
        for (String raw : rawLines) {
            if (raw.startsWith(" ") && !logical.isEmpty()) {
                logical.set(logical.size() - 1, logical.get(logical.size() - 1) + raw.substring(1));
            } else if (!raw.isEmpty()) {
                logical.add(raw);
            }
        }
        return logical;
    }

    private static String requireLine(List<String> logicalLines, String prefix) {
        return logicalLines.stream()
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No logical line starting with \"" + prefix + "\" found in: " + logicalLines));
    }

    private static Optional<String> findLine(List<String> logicalLines, String prefix) {
        return logicalLines.stream().filter(line -> line.startsWith(prefix)).findFirst();
    }

    @Test
    void renderRequest_givenCreateThenUpdate_thenUidStaysStable() {
        var createEvent = blockerEvent("relay-uid-1", 0);
        var updateEvent = blockerEvent("relay-uid-1", 1, START.plusDays(1), END.plusDays(1), ATTENDEE_EMAIL);

        var createIcs = renderer.renderRequest(createEvent, GENERATED_AT);
        var updateIcs = renderer.renderRequest(updateEvent, GENERATED_AT);

        var createUid = requireLine(logicalLines(createIcs), "UID:");
        var updateUid = requireLine(logicalLines(updateIcs), "UID:");
        assertThat(updateUid).isEqualTo(createUid);
    }

    @Test
    void renderRequest_givenReRenderWithHigherSequence_thenSequenceStrictlyIncreases() {
        var createIcs = renderer.renderRequest(blockerEvent("relay-uid-2", 0), GENERATED_AT);
        var updateIcs = renderer.renderRequest(blockerEvent("relay-uid-2", 1), GENERATED_AT);
        var cancelIcs = renderer.renderCancel(blockerEvent("relay-uid-2", 2), GENERATED_AT);

        assertThat(requireLine(logicalLines(createIcs), "SEQUENCE:")).isEqualTo("SEQUENCE:0");
        assertThat(requireLine(logicalLines(updateIcs), "SEQUENCE:")).isEqualTo("SEQUENCE:1");
        assertThat(requireLine(logicalLines(cancelIcs), "SEQUENCE:")).isEqualTo("SEQUENCE:2");
    }

    @Test
    void renderRequest_givenCreateOrUpdateEvent_thenMethodIsRequestAtCalendarLevel() {
        var lines = logicalLines(renderer.renderRequest(blockerEvent("relay-uid-3", 0), GENERATED_AT));

        var beginVEventIndex = lines.indexOf("BEGIN:VEVENT");
        var methodIndex = lines.indexOf("METHOD:REQUEST");

        assertThat(methodIndex).isGreaterThanOrEqualTo(0);
        assertThat(beginVEventIndex).isGreaterThan(methodIndex);
    }

    @Test
    void renderCancel_givenCancelledEvent_thenMethodIsCancelAtCalendarLevel() {
        var lines = logicalLines(renderer.renderCancel(blockerEvent("relay-uid-4", 3), GENERATED_AT));

        var beginVEventIndex = lines.indexOf("BEGIN:VEVENT");
        var methodIndex = lines.indexOf("METHOD:CANCEL");

        assertThat(methodIndex).isGreaterThanOrEqualTo(0);
        assertThat(beginVEventIndex).isGreaterThan(methodIndex);
        assertThat(lines).doesNotContain("METHOD:REQUEST");
    }

    @Test
    void renderRequest_givenEvent_thenVEventContainsAllRequiredProperties() {
        var event = blockerEvent("relay-uid-5", 0);
        var lines = logicalLines(renderer.renderRequest(event, GENERATED_AT));

        assertThat(requireLine(lines, "UID:")).isEqualTo("UID:relay-uid-5");
        assertThat(requireLine(lines, "DTSTAMP:")).isEqualTo("DTSTAMP:20260723T080000Z");
        assertThat(requireLine(lines, "DTSTART;TZID=")).isEqualTo("DTSTART;TZID=Europe/Berlin:20260723T100000");
        assertThat(requireLine(lines, "DTEND;TZID=")).isEqualTo("DTEND;TZID=Europe/Berlin:20260723T110000");
        assertThat(requireLine(lines, "STATUS:")).isEqualTo("STATUS:CONFIRMED");
        assertThat(requireLine(lines, "SUMMARY:")).isEqualTo("SUMMARY:Privater Blocker");
        assertThat(requireLine(lines, "ORGANIZER")).contains("mailto:" + ORGANIZER_EMAIL);
        assertThat(requireLine(lines, "ATTENDEE")).contains("mailto:" + ATTENDEE_EMAIL);
        assertThat(requireLine(lines, "SEQUENCE:")).isEqualTo("SEQUENCE:0");
    }

    @Test
    void renderRequest_givenEvent_thenSummaryIsFixedTitlelessLiteralRegardlessOfInput() {
        var lines = logicalLines(renderer.renderRequest(blockerEvent("relay-uid-6", 0), GENERATED_AT));

        assertThat(requireLine(lines, "SUMMARY:")).isEqualTo("SUMMARY:Privater Blocker");
    }

    @Test
    void renderRequest_givenEvent_thenAttendeeLineHasFullParticipationParameters() {
        var lines = logicalLines(renderer.renderRequest(blockerEvent("relay-uid-7", 0), GENERATED_AT));

        assertThat(requireLine(lines, "ATTENDEE"))
                .isEqualTo("ATTENDEE;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:" + ATTENDEE_EMAIL);
    }

    @Test
    void renderCancel_givenEvent_thenAttendeeLineDropsParticipationParameters() {
        var lines = logicalLines(renderer.renderCancel(blockerEvent("relay-uid-8", 1), GENERATED_AT));

        var attendeeLine = requireLine(lines, "ATTENDEE");
        assertThat(attendeeLine).contains("mailto:" + ATTENDEE_EMAIL);
        assertThat(attendeeLine).doesNotContain("PARTSTAT");
        assertThat(attendeeLine).doesNotContain("ROLE=");
        assertThat(attendeeLine).doesNotContain("RSVP");
    }

    @Test
    void renderCancel_givenEvent_thenStatusRemainsConfirmedNotCancelled() {
        var ics = renderer.renderCancel(blockerEvent("relay-uid-9", 1), GENERATED_AT);
        var lines = logicalLines(ics);

        assertThat(requireLine(lines, "STATUS:")).isEqualTo("STATUS:CONFIRMED");
        assertThat(ics).doesNotContain("STATUS:CANCELLED");
    }

    @Test
    void renderRequest_givenEuropeBerlinEvent_thenIncludesVerbatimVTimezoneBlock() {
        var ics = renderer.renderRequest(blockerEvent("relay-uid-10", 0), GENERATED_AT);

        assertThat(ics).contains(EXPECTED_BERLIN_VTIMEZONE);
    }

    @Test
    void renderCancel_givenEuropeBerlinEvent_thenIncludesVerbatimVTimezoneBlock() {
        var ics = renderer.renderCancel(blockerEvent("relay-uid-11", 2), GENERATED_AT);

        assertThat(ics).contains(EXPECTED_BERLIN_VTIMEZONE);
    }

    @Test
    void renderRequest_givenLongAttendeeAddress_thenAttendeeLineIsFoldedAt75OctetsWithSingleSpaceContinuation() {
        var longAttendeeEmail = "very-long-department-distribution-list-alias-for-outlook-blocker@example.com";
        var event = blockerEvent("relay-uid-12", 0, START, END, longAttendeeEmail);

        var ics = renderer.renderRequest(event, GENERATED_AT);
        var physicalLines = ics.split("\r\n", -1);

        for (String physicalLine : physicalLines) {
            assertThat(physicalLine.getBytes(StandardCharsets.UTF_8).length)
                    .as("physical line exceeds 75 octets: \"%s\"", physicalLine)
                    .isLessThanOrEqualTo(75);
        }

        var foldedAttendeeLine = new StringBuilder();
        var inAttendeeProperty = false;
        for (String physicalLine : physicalLines) {
            if (physicalLine.startsWith("ATTENDEE")) {
                inAttendeeProperty = true;
                foldedAttendeeLine.append(physicalLine);
            } else if (inAttendeeProperty && physicalLine.startsWith(" ")) {
                assertThat(physicalLine).startsWith(" ").doesNotStartWith("  ");
                foldedAttendeeLine.append(physicalLine.substring(1));
            } else {
                inAttendeeProperty = false;
            }
        }

        assertThat(foldedAttendeeLine.toString())
                .isEqualTo("ATTENDEE;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:" + longAttendeeEmail);

        var attendeePhysicalLineCount = 0;
        var seenAttendee = false;
        for (String physicalLine : physicalLines) {
            if (physicalLine.startsWith("ATTENDEE")) {
                seenAttendee = true;
                attendeePhysicalLineCount++;
            } else if (seenAttendee && physicalLine.startsWith(" ")) {
                attendeePhysicalLineCount++;
            } else if (seenAttendee) {
                break;
            }
        }
        assertThat(attendeePhysicalLineCount)
                .as("expected the long ATTENDEE line to actually be folded across multiple physical lines")
                .isGreaterThan(1);
    }

    @Test
    void renderRequest_givenEvent_thenLineEndingsAreCrlfOnlyWithNoBareLineFeed() {
        var ics = renderer.renderRequest(blockerEvent("relay-uid-13", 0), GENERATED_AT);

        assertThat(ics).contains("\r\n");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    void renderCancel_givenEvent_thenLineEndingsAreCrlfOnlyWithNoBareLineFeed() {
        var ics = renderer.renderCancel(blockerEvent("relay-uid-14", 1), GENERATED_AT);

        assertThat(ics).contains("\r\n");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    void renderRequest_givenEvent_thenVCalendarEnvelopeStructureIsCorrect() {
        var ics = renderer.renderRequest(blockerEvent("relay-uid-15", 0), GENERATED_AT);
        var lines = logicalLines(ics);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n");
        assertThat(lines).contains("VERSION:2.0");
        assertThat(findLine(lines, "PRODID:")).isPresent();
        assertThat(lines).contains("CALSCALE:GREGORIAN");
        assertThat(lines.getLast()).isEqualTo("END:VCALENDAR");

        var beginVTimezoneIndex = lines.indexOf("BEGIN:VTIMEZONE");
        var beginVEventIndex = lines.indexOf("BEGIN:VEVENT");
        var endVEventIndex = lines.indexOf("END:VEVENT");
        var endVCalendarIndex = lines.indexOf("END:VCALENDAR");

        assertThat(beginVTimezoneIndex).isGreaterThanOrEqualTo(0);
        assertThat(beginVEventIndex).isGreaterThan(beginVTimezoneIndex);
        assertThat(endVEventIndex).isGreaterThan(beginVEventIndex);
        assertThat(endVCalendarIndex).isGreaterThan(endVEventIndex);
    }
}
