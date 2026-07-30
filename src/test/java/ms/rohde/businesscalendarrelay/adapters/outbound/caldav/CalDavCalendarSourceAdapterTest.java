package ms.rohde.businesscalendarrelay.adapters.outbound.caldav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedCalendarResource;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarReplicaStore;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarReplicaStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link CalDavCalendarSourceAdapter} against a real local HTTP server, since
 * the CalDAV {@code REPORT} verb, header shape, and namespace-URI-based multistatus
 * parsing are protocol-level details a mock cannot meaningfully stand in for.
 */
class CalDavCalendarSourceAdapterTest {

    private static final String USERNAME = "relay-user";
    private static final String PASSWORD = "s3cr3t";
    private static final String COLLECTION_PATH = "/remote.php/dav/calendars/user/personal/";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /**
     * Default clock/horizon for tests whose fixtures carry no {@code RRULE}: a
     * non-recurring event's mapping never consults {@code now} or the horizon (only the
     * creation-eligibility gate in {@code RelayDiffPlanner} does), so any generous,
     * far-future-reaching values are equivalent here.
     */
    private static final Clock DEFAULT_CLOCK = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final Period DEFAULT_HORIZON = Period.ofYears(5);

    private static final String VTIMEZONE_EUROPE_BERLIN =
            """
            BEGIN:VTIMEZONE
            TZID:Europe/Berlin
            BEGIN:DAYLIGHT
            TZOFFSETFROM:+0100
            TZOFFSETTO:+0200
            TZNAME:CEST
            DTSTART:19700329T020000
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
            END:DAYLIGHT
            BEGIN:STANDARD
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            TZNAME:CET
            DTSTART:19701025T030000
            RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
            END:STANDARD
            END:VTIMEZONE
            """;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record CapturedRequest(
            String method, String authorizationHeader, String depthHeader, String contentTypeHeader, String body) {}

    private URI startServer(int statusCode, String responseBody, AtomicReference<CapturedRequest> capture)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(COLLECTION_PATH, exchange -> {
            var requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (capture != null) {
                capture.set(new CapturedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Depth"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        requestBody));
            }
            var payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(statusCode, payload.length);
            try (OutputStream responseStream = exchange.getResponseBody()) {
                responseStream.write(payload);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + COLLECTION_PATH);
    }

    /**
     * A {@link CalendarReplicaStore} mock that fails the test the instant any method on it
     * is invoked -- used by every legacy/{@code calendar-query}-path test (delta sync
     * disabled) to prove {@code CalDavCalendarSourceAdapter} never touches it, per {@code
     * docs/features/delta-sync.md}: "Ist deltaSyncEnabled == false, ... calendarReplicaStore
     * wird in diesem Fall nie berührt."
     */
    private static CalendarReplicaStore neverTouchedReplicaStore() {
        return mock(CalendarReplicaStore.class, invocation -> {
            throw new AssertionError(
                    "CalendarReplicaStore must not be touched when delta sync is disabled, but "
                            + invocation.getMethod().getName() + " was called");
        });
    }

    private CalDavCalendarSourceAdapter adapter(URI calendarCollectionUri) {
        return adapter(calendarCollectionUri, DEFAULT_CLOCK, DEFAULT_HORIZON);
    }

    private CalDavCalendarSourceAdapter adapter(URI calendarCollectionUri, Clock clock, Period recurringEventHorizon) {
        return new CalDavCalendarSourceAdapter(
                HttpClient.newHttpClient(),
                calendarCollectionUri,
                USERNAME,
                PASSWORD,
                clock,
                recurringEventHorizon,
                neverTouchedReplicaStore(),
                false);
    }

    private CalDavCalendarSourceAdapter deltaSyncAdapter(URI calendarCollectionUri, CalendarReplicaStore replicaStore) {
        return deltaSyncAdapter(calendarCollectionUri, replicaStore, DEFAULT_CLOCK, DEFAULT_HORIZON);
    }

    private CalDavCalendarSourceAdapter deltaSyncAdapter(
            URI calendarCollectionUri, CalendarReplicaStore replicaStore, Clock clock, Period recurringEventHorizon) {
        return new CalDavCalendarSourceAdapter(
                HttpClient.newHttpClient(),
                calendarCollectionUri,
                USERNAME,
                PASSWORD,
                clock,
                recurringEventHorizon,
                replicaStore,
                true);
    }

    private record StubResponse(int statusCode, String body) {}

    /**
     * Starts a local HTTP server whose response depends on the incoming REPORT request
     * body, so a test can simulate the CalDAV server behaving differently across
     * successive requests within a single {@code readEvents()} call (e.g. an invalid
     * sync-token forcing a second, empty-token request) or across successive
     * {@code readEvents()} calls (e.g. permanent fallback).
     */
    private URI startServer(Function<String, StubResponse> responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(COLLECTION_PATH, exchange -> {
            var requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var stub = responder.apply(requestBody);
            var payload = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(stub.statusCode(), payload.length);
            try (OutputStream responseStream = exchange.getResponseBody()) {
                responseStream.write(payload);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + COLLECTION_PATH);
    }

    private static String icsCalendar(String... veventBlocks) {
        var sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:-//Nextcloud//Test\r\n")
                .append(VTIMEZONE_EUROPE_BERLIN.replace("\n", "\r\n"));
        for (var block : veventBlocks) {
            sb.append(block.replace("\n", "\r\n"));
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static String multiStatusWithCalendarData(String... calendarDataBlobs) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<d:multistatus xmlns:d=\"DAV:\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\">\n");
        for (int i = 0; i < calendarDataBlobs.length; i++) {
            sb.append("  <d:response>\n")
                    .append("    <d:href>")
                    .append(COLLECTION_PATH)
                    .append("resource")
                    .append(i)
                    .append(".ics</d:href>\n")
                    .append("    <d:propstat>\n")
                    .append("      <d:prop>\n")
                    .append("        <d:getetag>&quot;etag")
                    .append(i)
                    .append("&quot;</d:getetag>\n")
                    .append("        <cal:calendar-data>")
                    .append(calendarDataBlobs[i])
                    .append("</cal:calendar-data>\n")
                    .append("      </d:prop>\n")
                    .append("      <d:status>HTTP/1.1 200 OK</d:status>\n")
                    .append("    </d:propstat>\n")
                    .append("  </d:response>\n");
        }
        sb.append("</d:multistatus>\n");
        return sb.toString();
    }

    private static String simpleVEvent(String uid, String dtStartLocal, String dtEndLocal) {
        return "BEGIN:VEVENT\n"
                + "UID:" + uid + "\n"
                + "DTSTAMP:20260101T000000Z\n"
                + "DTSTART;TZID=Europe/Berlin:" + dtStartLocal + "\n"
                + "DTEND;TZID=Europe/Berlin:" + dtEndLocal + "\n"
                + "SUMMARY:Some private thing\n"
                + "END:VEVENT\n";
    }

    private static String icsWithSingleVEvent(String uid, String dtStartLocal, String dtEndLocal) {
        return icsCalendar(simpleVEvent(uid, dtStartLocal, dtEndLocal));
    }

    private static String cleanSingleEventMultiStatus() {
        return multiStatusWithCalendarData(icsWithSingleVEvent("event1-uid", "20260201T100000", "20260201T110000"));
    }

    private static String multiStatusWithAlternatePrefixesAndOneStaleResponse() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<a:multistatus xmlns:a=\"DAV:\" xmlns:b=\"urn:ietf:params:xml:ns:caldav\">\n"
                + "  <a:response>\n"
                + "    <a:href>" + COLLECTION_PATH + "event2.ics</a:href>\n"
                + "    <a:propstat>\n"
                + "      <a:prop>\n"
                + "        <a:getetag>&quot;def456&quot;</a:getetag>\n"
                + "        <b:calendar-data>"
                + icsWithSingleVEvent("event2-uid", "20260301T090000", "20260301T093000")
                + "</b:calendar-data>\n"
                + "      </a:prop>\n"
                + "      <a:status>HTTP/1.1 200 OK</a:status>\n"
                + "    </a:propstat>\n"
                + "  </a:response>\n"
                + "  <a:response>\n"
                + "    <a:href>" + COLLECTION_PATH + "stale.ics</a:href>\n"
                + "    <a:propstat>\n"
                + "      <a:prop>\n"
                + "        <a:getetag/>\n"
                + "      </a:prop>\n"
                + "      <a:status>HTTP/1.1 404 Not Found</a:status>\n"
                + "    </a:propstat>\n"
                + "  </a:response>\n"
                + "</a:multistatus>\n";
    }

    private static String multiStatusWithMalformedCalendarData() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<d:multistatus xmlns:d=\"DAV:\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\">\n"
                + "  <d:response>\n"
                + "    <d:href>" + COLLECTION_PATH + "broken.ics</d:href>\n"
                + "    <d:propstat>\n"
                + "      <d:prop>\n"
                + "        <cal:calendar-data>THIS IS NOT VALID ICS TEXT</cal:calendar-data>\n"
                + "      </d:prop>\n"
                + "      <d:status>HTTP/1.1 200 OK</d:status>\n"
                + "    </d:propstat>\n"
                + "  </d:response>\n"
                + "</d:multistatus>\n";
    }

    private static String multiStatusWithVEventMissingDtEnd() {
        var ics = "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Nextcloud//Test\r\n"
                + VTIMEZONE_EUROPE_BERLIN.replace("\n", "\r\n")
                + "BEGIN:VEVENT\r\n"
                + "UID:no-dtend-uid\r\n"
                + "DTSTAMP:20260101T000000Z\r\n"
                + "DTSTART;TZID=Europe/Berlin:20260201T100000\r\n"
                + "SUMMARY:Missing DTEND\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
        return multiStatusWithCalendarData(ics);
    }

    @Test
    void readEvents_givenCollectionUrl_thenIssuesReportRequestWithDepthAuthAndCalendarQueryBody() throws IOException {
        var capture = new AtomicReference<CapturedRequest>();
        var uri = startServer(207, cleanSingleEventMultiStatus(), capture);

        adapter(uri).readEvents();

        var captured = capture.get();
        assertThat(captured.method()).isEqualTo("REPORT");
        assertThat(captured.depthHeader()).isEqualTo("1");
        assertThat(captured.contentTypeHeader()).startsWith("application/xml");
        assertThat(captured.authorizationHeader())
                .isEqualTo("Basic "
                        + Base64.getEncoder()
                                .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        assertThat(captured.body()).contains("calendar-query").contains("VEVENT");
    }

    @Test
    void readEvents_givenCleanSingleEventMultiStatus_thenReturnsMappedSourceEvent() throws IOException {
        var uri = startServer(207, cleanSingleEventMultiStatus(), null);

        var events = adapter(uri).readEvents();

        assertThat(events)
                .containsExactly(new SourceEvent(
                        "event1-uid",
                        ZonedDateTime.of(2026, 2, 1, 10, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 2, 1, 11, 0, 0, 0, BERLIN),
                        false,
                        true,
                        false,
                        false));
    }

    @Test
    void readEvents_givenResponseWithDifferentNamespacePrefixesAndOneStaleEntry_thenSkipsStaleAndMapsRemaining()
            throws IOException {
        var uri = startServer(207, multiStatusWithAlternatePrefixesAndOneStaleResponse(), null);

        var events = adapter(uri).readEvents();

        assertThat(events)
                .containsExactly(new SourceEvent(
                        "event2-uid",
                        ZonedDateTime.of(2026, 3, 1, 9, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 3, 1, 9, 30, 0, 0, BERLIN),
                        false,
                        true,
                        false,
                        false));
    }

    @Test
    void readEvents_givenNonMultiStatusHttpResponse_thenThrowsCalDavCalendarSourceException() throws IOException {
        var uri = startServer(500, "internal server error", null);

        assertThatThrownBy(() -> adapter(uri).readEvents())
                .isInstanceOf(CalDavCalendarSourceException.class)
                .hasMessageContaining("500");
    }

    @Test
    void readEvents_givenMalformedCalendarData_thenThrowsCalDavCalendarSourceException() throws IOException {
        var uri = startServer(207, multiStatusWithMalformedCalendarData(), null);

        assertThatThrownBy(() -> adapter(uri).readEvents()).isInstanceOf(CalDavCalendarSourceException.class);
    }

    @Test
    void readEvents_givenVEventMissingDtEnd_thenThrowsCalDavCalendarSourceException() throws IOException {
        var uri = startServer(207, multiStatusWithVEventMissingDtEnd(), null);

        assertThatThrownBy(() -> adapter(uri).readEvents())
                .isInstanceOf(CalDavCalendarSourceException.class)
                .hasMessageContaining("no-dtend-uid");
    }

    @Test
    void readEvents_givenMalformedMultiStatusXml_thenThrowsCalDavCalendarSourceException() throws IOException {
        var uri = startServer(207, "not even xml <<<", null);

        assertThatThrownBy(() -> adapter(uri).readEvents()).isInstanceOf(CalDavCalendarSourceException.class);
    }

    @Test
    void readEvents_givenAllDayVEvent_thenMapsToAllDaySourceEventInsteadOfThrowing() throws IOException {
        var allDayVEvent = "BEGIN:VEVENT\n"
                + "UID:allday-uid\n"
                + "DTSTAMP:20260101T000000Z\n"
                + "DTSTART;VALUE=DATE:20260315\n"
                + "DTEND;VALUE=DATE:20260316\n"
                + "SUMMARY:All day thing\n"
                + "END:VEVENT\n";
        var uri = startServer(207, multiStatusWithCalendarData(icsCalendar(allDayVEvent)), null);

        var events = adapter(uri).readEvents();

        assertThat(events)
                .containsExactly(new SourceEvent(
                        "allday-uid",
                        ZonedDateTime.of(2026, 3, 15, 0, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 3, 16, 0, 0, 0, 0, BERLIN),
                        true,
                        true,
                        false,
                        false));
    }

    @Test
    void readEvents_givenTransparentVEvent_thenMapsToBusyFalse() throws IOException {
        var transparentVEvent = "BEGIN:VEVENT\n"
                + "UID:transparent-uid\n"
                + "DTSTAMP:20260101T000000Z\n"
                + "DTSTART;TZID=Europe/Berlin:20260201T100000\n"
                + "DTEND;TZID=Europe/Berlin:20260201T110000\n"
                + "TRANSP:TRANSPARENT\n"
                + "SUMMARY:Transparent thing\n"
                + "END:VEVENT\n";
        var uri = startServer(207, multiStatusWithCalendarData(icsCalendar(transparentVEvent)), null);

        var events = adapter(uri).readEvents();

        assertThat(events).singleElement().satisfies(event -> assertThat(event.busy()).isFalse());
    }

    // --- Recurring series expansion ---

    private static final String SERIES_UID = "weekly-series-uid";

    private static String weeklyMasterVEvent(boolean cancelled) {
        return "BEGIN:VEVENT\n"
                + "UID:" + SERIES_UID + "\n"
                + "DTSTAMP:20260101T000000Z\n"
                + "DTSTART;TZID=Europe/Berlin:20260202T100000\n"
                + "DTEND;TZID=Europe/Berlin:20260202T110000\n"
                + "RRULE:FREQ=WEEKLY;BYDAY=MO\n"
                + (cancelled ? "STATUS:CANCELLED\n" : "")
                + "SUMMARY:Weekly thing\n"
                + "END:VEVENT\n";
    }

    private static String weeklyMasterVEventWithExdate(String exDateLocal) {
        return "BEGIN:VEVENT\n"
                + "UID:" + SERIES_UID + "\n"
                + "DTSTAMP:20260101T000000Z\n"
                + "DTSTART;TZID=Europe/Berlin:20260202T100000\n"
                + "DTEND;TZID=Europe/Berlin:20260202T110000\n"
                + "RRULE:FREQ=WEEKLY;BYDAY=MO\n"
                + "EXDATE;TZID=Europe/Berlin:" + exDateLocal + "\n"
                + "SUMMARY:Weekly thing\n"
                + "END:VEVENT\n";
    }

    private static String overrideVEvent(
            String recurrenceIdLocal, String dtStartLocal, String dtEndLocal, boolean cancelled) {
        return "BEGIN:VEVENT\n"
                + "UID:" + SERIES_UID + "\n"
                + "DTSTAMP:20260101T000000Z\n"
                + "RECURRENCE-ID;TZID=Europe/Berlin:" + recurrenceIdLocal + "\n"
                + "DTSTART;TZID=Europe/Berlin:" + dtStartLocal + "\n"
                + "DTEND;TZID=Europe/Berlin:" + dtEndLocal + "\n"
                + (cancelled ? "STATUS:CANCELLED\n" : "")
                + "SUMMARY:Occurrence override\n"
                + "END:VEVENT\n";
    }

    /**
     * now = 2026-02-01T00:00:00Z, horizon = 20 days -&gt; horizon end = 2026-02-21T00:00:00Z.
     * Weekly Mondays from the 2026-02-02T10:00 Europe/Berlin master land at Feb 2, Feb 9,
     * and Feb 16 within the horizon; Feb 23 (09:00Z, since CET is UTC+1) falls after the
     * 2026-02-21T00:00:00Z cutoff and must not appear.
     */
    private static Clock seriesClock() {
        return Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
    }

    private static Period seriesHorizon() {
        return Period.ofDays(20);
    }

    @Test
    void readEvents_givenWeeklyRecurringSeries_thenExpandsOccurrencesWithinHorizonAndNoneBeyond() throws IOException {
        var uri = startServer(207, multiStatusWithCalendarData(icsCalendar(weeklyMasterVEvent(false))), null);

        var events = adapter(uri, seriesClock(), seriesHorizon()).readEvents();

        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.recurring()).isTrue();
            assertThat(event.sourceUid()).startsWith(SERIES_UID + "#");
        });
        assertThat(events).extracting(SourceEvent::start)
                .containsExactlyInAnyOrder(
                        ZonedDateTime.of(2026, 2, 2, 10, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 2, 9, 10, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 2, 16, 10, 0, 0, 0, BERLIN));
        assertThat(events)
                .noneMatch(event -> event.start().equals(ZonedDateTime.of(2026, 2, 23, 10, 0, 0, 0, BERLIN)));
    }

    @Test
    void readEvents_givenRecurringSeries_thenCompositeSourceUidIsSeriesUidHashOriginalOccurrenceInstant()
            throws IOException {
        var uri = startServer(207, multiStatusWithCalendarData(icsCalendar(weeklyMasterVEvent(false))), null);

        var events = adapter(uri, seriesClock(), seriesHorizon()).readEvents();

        var expectedInstant =
                ZonedDateTime.of(2026, 2, 9, 10, 0, 0, 0, BERLIN).toInstant();
        assertThat(events)
                .anySatisfy(event -> assertThat(event.sourceUid()).isEqualTo(SERIES_UID + "#" + expectedInstant));
    }

    @Test
    void readEvents_givenExdateOnSeries_thenExcludedOccurrenceIsNotEmitted() throws IOException {
        var uri = startServer(
                207,
                multiStatusWithCalendarData(icsCalendar(weeklyMasterVEventWithExdate("20260209T100000"))),
                null);

        var events = adapter(uri, seriesClock(), seriesHorizon()).readEvents();

        assertThat(events).hasSize(2);
        assertThat(events)
                .noneMatch(event -> event.start().equals(ZonedDateTime.of(2026, 2, 9, 10, 0, 0, 0, BERLIN)));
    }

    @Test
    void readEvents_givenRecurrenceIdOverride_thenReplacesSlotWithOverrideWindowWithoutDuplicating() throws IOException {
        var override = overrideVEvent("20260209T100000", "20260210T140000", "20260210T150000", false);
        var uri = startServer(
                207,
                multiStatusWithCalendarData(icsCalendar(weeklyMasterVEvent(false), override)),
                null);

        var events = adapter(uri, seriesClock(), seriesHorizon()).readEvents();

        assertThat(events).hasSize(3);
        var expectedSourceUid = SERIES_UID
                + "#"
                + ZonedDateTime.of(2026, 2, 9, 10, 0, 0, 0, BERLIN).toInstant();
        assertThat(events).filteredOn(event -> event.sourceUid().equals(expectedSourceUid))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.start()).isEqualTo(ZonedDateTime.of(2026, 2, 10, 14, 0, 0, 0, BERLIN));
                    assertThat(event.end()).isEqualTo(ZonedDateTime.of(2026, 2, 10, 15, 0, 0, 0, BERLIN));
                    assertThat(event.recurring()).isTrue();
                });
        assertThat(events)
                .noneMatch(event -> event.start().equals(ZonedDateTime.of(2026, 2, 9, 10, 0, 0, 0, BERLIN)));
    }

    @Test
    void readEvents_givenOverrideAcrossSeparateCalendarDataBlobs_thenStillMergedIntoOneSeries() throws IOException {
        var override = overrideVEvent("20260209T100000", "20260210T140000", "20260210T150000", false);
        var uri = startServer(
                207,
                multiStatusWithCalendarData(icsCalendar(weeklyMasterVEvent(false)), icsCalendar(override)),
                null);

        var events = adapter(uri, seriesClock(), seriesHorizon()).readEvents();

        assertThat(events).hasSize(3);
        assertThat(events)
                .anySatisfy(event -> assertThat(event.start()).isEqualTo(ZonedDateTime.of(2026, 2, 10, 14, 0, 0, 0, BERLIN)));
    }

    @Test
    void readEvents_givenCancelledOverride_thenThatOccurrenceIsDroppedEntirely() throws IOException {
        var cancelledOverride = overrideVEvent("20260209T100000", "20260209T100000", "20260209T110000", true);
        var uri = startServer(
                207,
                multiStatusWithCalendarData(icsCalendar(weeklyMasterVEvent(false), cancelledOverride)),
                null);

        var events = adapter(uri, seriesClock(), seriesHorizon()).readEvents();

        assertThat(events).hasSize(2);
        assertThat(events)
                .noneMatch(event -> event.start().equals(ZonedDateTime.of(2026, 2, 9, 10, 0, 0, 0, BERLIN)));
    }

    @Test
    void readEvents_givenCancelledMaster_thenEveryOccurrenceIsCancelledButNoneAreDropped() throws IOException {
        var uri = startServer(207, multiStatusWithCalendarData(icsCalendar(weeklyMasterVEvent(true))), null);

        var events = adapter(uri, seriesClock(), seriesHorizon()).readEvents();

        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(event -> assertThat(event.cancelled()).isTrue());
    }

    // --- Delta sync (sync-collection, RFC 6578) ---

    private record SyncChangedEntry(String href, String etag, String rawCalendarData) {}

    private static String syncCollectionMultiStatus(
            String syncToken, List<SyncChangedEntry> changed, List<String> removedHrefs) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<d:multistatus xmlns:d=\"DAV:\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\">\n");
        for (var entry : changed) {
            sb.append("  <d:response>\n")
                    .append("    <d:href>")
                    .append(entry.href())
                    .append("</d:href>\n")
                    .append("    <d:propstat>\n")
                    .append("      <d:prop>\n")
                    .append("        <d:getetag>")
                    .append(entry.etag())
                    .append("</d:getetag>\n")
                    .append("        <cal:calendar-data>")
                    .append(entry.rawCalendarData())
                    .append("</cal:calendar-data>\n")
                    .append("      </d:prop>\n")
                    .append("      <d:status>HTTP/1.1 200 OK</d:status>\n")
                    .append("    </d:propstat>\n")
                    .append("  </d:response>\n");
        }
        for (var href : removedHrefs) {
            sb.append("  <d:response>\n")
                    .append("    <d:href>")
                    .append(href)
                    .append("</d:href>\n")
                    .append("    <d:status>HTTP/1.1 404 Not Found</d:status>\n")
                    .append("  </d:response>\n");
        }
        sb.append("  <d:sync-token>").append(syncToken).append("</d:sync-token>\n");
        sb.append("</d:multistatus>\n");
        return sb.toString();
    }

    private static String invalidSyncTokenErrorBody() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<d:error xmlns:d=\"DAV:\">\n"
                + "  <d:valid-sync-token/>\n"
                + "</d:error>\n";
    }

    /**
     * Per the XML 1.0 end-of-line handling rule, any XML processor normalizes {@code
     * \r\n} (and lone {@code \r}) to a single {@code \n} in parsed character data. A
     * {@code CachedCalendarResource} built directly from {@link #icsWithSingleVEvent}
     * (which uses {@code \r\n}) must go through this same normalization before being
     * compared against a {@code rawCalendarData} value that actually travelled through the
     * multistatus XML response, or the comparison spuriously fails on line-ending style
     * alone despite identical content.
     */
    private static String xmlNormalized(String text) {
        return text.replace("\r\n", "\n");
    }

    @Test
    void readEvents_givenDeltaSyncEnabledAndNoStoredToken_thenIssuesSyncCollectionWithEmptyTokenAndResetsReplica()
            throws IOException {
        var capture = new AtomicReference<String>();
        var responseBody = syncCollectionMultiStatus(
                "sync-token-1",
                List.of(new SyncChangedEntry("/cal/event1.ics", "\"etag-1\"", icsWithSingleVEvent(
                        "event1-uid", "20260201T100000", "20260201T110000"))),
                List.of());
        var uri = startServer(requestBody -> {
            capture.set(requestBody);
            return new StubResponse(207, responseBody);
        });

        var replicaStore = mock(CalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn(null);
        var storedResource = new CachedCalendarResource(
                "/cal/event1.ics",
                "\"etag-1\"",
                xmlNormalized(icsWithSingleVEvent("event1-uid", "20260201T100000", "20260201T110000")));
        given(replicaStore.loadAllResources()).willReturn(List.of(storedResource));

        var events = deltaSyncAdapter(uri, replicaStore).readEvents();

        assertThat(capture.get()).contains("sync-collection").contains("<D:sync-token></D:sync-token>");
        then(replicaStore).should().resetTo(eq("sync-token-1"), eq(List.of(storedResource)));
        then(replicaStore).should(never()).applyDelta(anyString(), anyList(), anyList());
        assertThat(events)
                .containsExactly(new SourceEvent(
                        "event1-uid",
                        ZonedDateTime.of(2026, 2, 1, 10, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 2, 1, 11, 0, 0, 0, BERLIN),
                        false,
                        true,
                        false,
                        false));
    }

    @Test
    void readEvents_givenDeltaSyncEnabledAndStoredToken_thenIssuesSyncCollectionWithStoredTokenAndAppliesDelta()
            throws IOException {
        var capture = new AtomicReference<String>();
        var changedResource = new SyncChangedEntry(
                "/cal/event2.ics", "\"etag-2\"", icsWithSingleVEvent("event2-uid", "20260301T090000", "20260301T093000"));
        var responseBody =
                syncCollectionMultiStatus("sync-token-2", List.of(changedResource), List.of("/cal/removed.ics"));
        var uri = startServer(requestBody -> {
            capture.set(requestBody);
            return new StubResponse(207, responseBody);
        });

        var replicaStore = mock(CalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("sync-token-1");
        var cachedEvent2 = new CachedCalendarResource(
                "/cal/event2.ics",
                "\"etag-2\"",
                xmlNormalized(icsWithSingleVEvent("event2-uid", "20260301T090000", "20260301T093000")));
        given(replicaStore.loadAllResources()).willReturn(List.of(cachedEvent2));

        var events = deltaSyncAdapter(uri, replicaStore).readEvents();

        assertThat(capture.get()).contains("sync-collection").contains("<D:sync-token>sync-token-1</D:sync-token>");
        then(replicaStore)
                .should()
                .applyDelta(
                        eq("sync-token-2"),
                        eq(List.of(new CachedCalendarResource(
                                changedResource.href(),
                                changedResource.etag(),
                                xmlNormalized(changedResource.rawCalendarData())))),
                        eq(List.of("/cal/removed.ics")));
        then(replicaStore).should(never()).resetTo(anyString(), anyList());
        assertThat(events)
                .containsExactly(new SourceEvent(
                        "event2-uid",
                        ZonedDateTime.of(2026, 3, 1, 9, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 3, 1, 9, 30, 0, 0, BERLIN),
                        false,
                        true,
                        false,
                        false));
    }

    @Test
    void readEvents_givenInvalidSyncTokenResponseWithPrecondition_thenPerformsForcedFullResync() throws IOException {
        var initialSyncResponseBody = syncCollectionMultiStatus(
                "sync-token-fresh",
                List.of(new SyncChangedEntry("/cal/event1.ics", "\"etag-1\"", icsWithSingleVEvent(
                        "event1-uid", "20260201T100000", "20260201T110000"))),
                List.of());
        var uri = startServer(requestBody -> {
            if (requestBody.contains("<D:sync-token>stale-token</D:sync-token>")) {
                return new StubResponse(403, invalidSyncTokenErrorBody());
            }
            return new StubResponse(207, initialSyncResponseBody);
        });

        var replicaStore = mock(CalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("stale-token");
        var freshResource = new CachedCalendarResource(
                "/cal/event1.ics",
                "\"etag-1\"",
                xmlNormalized(icsWithSingleVEvent("event1-uid", "20260201T100000", "20260201T110000")));
        given(replicaStore.loadAllResources()).willReturn(List.of(freshResource));

        var events = deltaSyncAdapter(uri, replicaStore).readEvents();

        then(replicaStore).should().resetTo(eq("sync-token-fresh"), eq(List.of(freshResource)));
        then(replicaStore).should(never()).applyDelta(anyString(), anyList(), anyList());
        assertThat(events).extracting(SourceEvent::sourceUid).containsExactly("event1-uid");
    }

    @Test
    void readEvents_givenInsufficientStorageResponse_thenPerformsForcedFullResyncSameAsInvalidToken() throws IOException {
        var initialSyncResponseBody = syncCollectionMultiStatus(
                "sync-token-fresh",
                List.of(new SyncChangedEntry("/cal/event1.ics", "\"etag-1\"", icsWithSingleVEvent(
                        "event1-uid", "20260201T100000", "20260201T110000"))),
                List.of());
        var uri = startServer(requestBody -> {
            if (requestBody.contains("<D:sync-token>stale-token</D:sync-token>")) {
                return new StubResponse(507, "insufficient storage");
            }
            return new StubResponse(207, initialSyncResponseBody);
        });

        var replicaStore = mock(CalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("stale-token");
        var freshResource = new CachedCalendarResource(
                "/cal/event1.ics",
                "\"etag-1\"",
                xmlNormalized(icsWithSingleVEvent("event1-uid", "20260201T100000", "20260201T110000")));
        given(replicaStore.loadAllResources()).willReturn(List.of(freshResource));

        var events = deltaSyncAdapter(uri, replicaStore).readEvents();

        then(replicaStore).should().resetTo(eq("sync-token-fresh"), eq(List.of(freshResource)));
        assertThat(events).extracting(SourceEvent::sourceUid).containsExactly("event1-uid");
    }

    @Test
    void readEvents_givenSyncCollectionUnsupported_thenFallsBackToCalendarQueryPermanentlyWithinAndAcrossCalls()
            throws IOException {
        var calendarQueryResponseBody =
                multiStatusWithCalendarData(icsWithSingleVEvent("event1-uid", "20260201T100000", "20260201T110000"));
        var uri = startServer(requestBody -> {
            if (requestBody.contains("sync-collection")) {
                return new StubResponse(501, "Not Implemented");
            }
            return new StubResponse(207, calendarQueryResponseBody);
        });

        var replicaStore = mock(CalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn(null);
        var adapter = deltaSyncAdapter(uri, replicaStore);

        var firstCallEvents = adapter.readEvents();
        var secondCallEvents = adapter.readEvents();

        var expectedEvent = new SourceEvent(
                "event1-uid",
                ZonedDateTime.of(2026, 2, 1, 10, 0, 0, 0, BERLIN),
                ZonedDateTime.of(2026, 2, 1, 11, 0, 0, 0, BERLIN),
                false,
                true,
                false,
                false);
        assertThat(firstCallEvents).containsExactly(expectedEvent);
        assertThat(secondCallEvents).containsExactly(expectedEvent);

        then(replicaStore).should(times(1)).loadSyncToken();
        then(replicaStore).shouldHaveNoMoreInteractions();
    }

    @Test
    void readEvents_givenReplicaStoreLoadSyncTokenThrows_thenThrowsCalDavCalendarSourceException() throws IOException {
        var uri = startServer(requestBody -> new StubResponse(207, cleanSingleEventMultiStatus()));
        var replicaStore = mock(CalendarReplicaStore.class);
        willThrow(new RuntimeException("db unavailable")).given(replicaStore).loadSyncToken();

        assertThatThrownBy(() -> deltaSyncAdapter(uri, replicaStore).readEvents())
                .isInstanceOf(CalDavCalendarSourceException.class);
    }

    @Test
    void readEvents_givenReplicaStoreApplyDeltaThrows_thenThrowsCalDavCalendarSourceExceptionWrappingCause()
            throws IOException {
        var responseBody = syncCollectionMultiStatus(
                "sync-token-2",
                List.of(new SyncChangedEntry("/cal/event1.ics", "\"etag-1\"", icsWithSingleVEvent(
                        "event1-uid", "20260201T100000", "20260201T110000"))),
                List.of());
        var uri = startServer(requestBody -> new StubResponse(207, responseBody));

        var replicaStore = mock(CalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("sync-token-1");
        var cause = new CalendarReplicaStoreException("persistence failed");
        willThrow(cause).given(replicaStore).applyDelta(anyString(), anyList(), anyList());

        assertThatThrownBy(() -> deltaSyncAdapter(uri, replicaStore).readEvents())
                .isInstanceOf(CalDavCalendarSourceException.class)
                .hasCause(cause);
    }

    @Test
    void readEvents_givenDeltaSyncEnabledAndRecurringSeriesInReplica_thenAppliesUnchangedExpansionPipeline()
            throws IOException {
        var syncCollectionResponseBody = syncCollectionMultiStatus("sync-token-2", List.of(), List.of());
        var uri = startServer(requestBody -> new StubResponse(207, syncCollectionResponseBody));

        var replicaStore = mock(CalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("sync-token-1");
        var seriesResource =
                new CachedCalendarResource("/cal/series.ics", "\"etag\"", icsCalendar(weeklyMasterVEvent(false)));
        given(replicaStore.loadAllResources()).willReturn(List.of(seriesResource));

        var events = deltaSyncAdapter(uri, replicaStore, seriesClock(), seriesHorizon()).readEvents();

        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.recurring()).isTrue();
            assertThat(event.sourceUid()).startsWith(SERIES_UID + "#");
        });
    }
}
