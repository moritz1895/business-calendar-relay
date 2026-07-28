package ms.rohde.businesscalendarrelay.adapters.outbound.caldav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
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

    private CalDavCalendarSourceAdapter adapter(URI calendarCollectionUri) {
        return new CalDavCalendarSourceAdapter(HttpClient.newHttpClient(), calendarCollectionUri, USERNAME, PASSWORD);
    }

    private static String icsWithSingleVEvent(String uid, String dtStartLocal, String dtEndLocal) {
        return "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Nextcloud//Test\r\n"
                + VTIMEZONE_EUROPE_BERLIN.replace("\n", "\r\n")
                + "BEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\n"
                + "DTSTAMP:20260101T000000Z\r\n"
                + "DTSTART;TZID=Europe/Berlin:" + dtStartLocal + "\r\n"
                + "DTEND;TZID=Europe/Berlin:" + dtEndLocal + "\r\n"
                + "SUMMARY:Some private thing\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    private static String cleanSingleEventMultiStatus() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<d:multistatus xmlns:d=\"DAV:\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\">\n"
                + "  <d:response>\n"
                + "    <d:href>" + COLLECTION_PATH + "event1.ics</d:href>\n"
                + "    <d:propstat>\n"
                + "      <d:prop>\n"
                + "        <d:getetag>&quot;abc123&quot;</d:getetag>\n"
                + "        <cal:calendar-data>"
                + icsWithSingleVEvent("event1-uid", "20260201T100000", "20260201T110000")
                + "</cal:calendar-data>\n"
                + "      </d:prop>\n"
                + "      <d:status>HTTP/1.1 200 OK</d:status>\n"
                + "    </d:propstat>\n"
                + "  </d:response>\n"
                + "</d:multistatus>\n";
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
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<d:multistatus xmlns:d=\"DAV:\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\">\n"
                + "  <d:response>\n"
                + "    <d:href>" + COLLECTION_PATH + "no-dtend.ics</d:href>\n"
                + "    <d:propstat>\n"
                + "      <d:prop>\n"
                + "        <cal:calendar-data>" + ics + "</cal:calendar-data>\n"
                + "      </d:prop>\n"
                + "      <d:status>HTTP/1.1 200 OK</d:status>\n"
                + "    </d:propstat>\n"
                + "  </d:response>\n"
                + "</d:multistatus>\n";
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

        var berlin = ZoneId.of("Europe/Berlin");
        assertThat(events)
                .containsExactly(new SourceEvent(
                        "event1-uid",
                        ZonedDateTime.of(2026, 2, 1, 10, 0, 0, 0, berlin),
                        ZonedDateTime.of(2026, 2, 1, 11, 0, 0, 0, berlin)));
    }

    @Test
    void readEvents_givenResponseWithDifferentNamespacePrefixesAndOneStaleEntry_thenSkipsStaleAndMapsRemaining()
            throws IOException {
        var uri = startServer(207, multiStatusWithAlternatePrefixesAndOneStaleResponse(), null);

        var events = adapter(uri).readEvents();

        var berlin = ZoneId.of("Europe/Berlin");
        assertThat(events)
                .containsExactly(new SourceEvent(
                        "event2-uid",
                        ZonedDateTime.of(2026, 3, 1, 9, 0, 0, 0, berlin),
                        ZonedDateTime.of(2026, 3, 1, 9, 30, 0, 0, berlin)));
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
}
