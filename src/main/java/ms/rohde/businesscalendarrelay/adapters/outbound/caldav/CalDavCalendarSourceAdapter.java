package ms.rohde.businesscalendarrelay.adapters.outbound.caldav;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarSource;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.TzId;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Uid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Reads the full current set of {@code VEVENT}s from one private CalDAV calendar
 * collection via a WebDAV {@code REPORT calendar-query}, RFC 4791 style.
 *
 * <p>One instance per configured source calendar, per {@link CalendarSource}'s own
 * contract. Always issues a full {@code calendar-query} with no time-range restriction
 * and no {@code sync-collection} token — {@code sync-collection} (RFC 6578) delta sync
 * is deliberately out of scope for now, per the project {@code CLAUDE.md}. Each
 * {@code VEVENT} component found in a resource's {@code calendar-data} is mapped
 * directly to one {@link SourceEvent} using that component's own {@code DTSTART}/
 * {@code DTEND} — recurring series are never expanded from {@code RRULE}, since
 * recurrence handling is out of scope until explicitly revisited.
 *
 * <p>Deliberately not wired as an auto-scanned, no-arg Spring singleton bean: the
 * collection URL and credentials are per-calendar constructor arguments, not Spring
 * beans, the same resolution already used by {@code JpaStateStoreAdapter} for its
 * per-calendar {@code sourceCalendarId}. A later PR (multi-calendar configuration
 * wiring) constructs one instance per configured calendar via explicit {@code @Bean}
 * factory methods.
 *
 * <p>Basic credentials are sent directly on every request rather than relying on
 * {@code java.net.Authenticator} challenge-response, since CalDAV servers do not
 * reliably issue a clean {@code 401} challenge before accepting Basic auth on a
 * {@code REPORT}.
 */
@InfrastructureServiceAdapter
public final class CalDavCalendarSourceAdapter implements CalendarSource {

    private static final Logger LOG = LogManager.getLogger(CalDavCalendarSourceAdapter.class);

    private static final String DAV_NAMESPACE = "DAV:";

    private static final String CALDAV_NAMESPACE = "urn:ietf:params:xml:ns:caldav";

    private static final int MULTI_STATUS = 207;

    private static final String CALENDAR_QUERY_BODY =
            """
            <?xml version="1.0" encoding="utf-8" ?>
            <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:prop>
                <D:getetag/>
                <C:calendar-data/>
              </D:prop>
              <C:filter>
                <C:comp-filter name="VCALENDAR">
                  <C:comp-filter name="VEVENT"/>
                </C:comp-filter>
              </C:filter>
            </C:calendar-query>
            """;

    private final HttpClient httpClient;
    private final URI calendarCollectionUri;
    private final String basicCredentials;

    public CalDavCalendarSourceAdapter(
            HttpClient httpClient, URI calendarCollectionUri, String username, String password) {
        this.httpClient = httpClient;
        this.calendarCollectionUri = calendarCollectionUri;
        this.basicCredentials = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<SourceEvent> readEvents() {
        var response = executeCalendarQuery();

        if (response.statusCode() != MULTI_STATUS) {
            throw new CalDavCalendarSourceException("Unexpected CalDAV REPORT response status "
                    + response.statusCode() + " from " + calendarCollectionUri);
        }

        var events = new ArrayList<SourceEvent>();
        for (var calendarData : extractCalendarDataBlobs(response.body())) {
            events.addAll(parseVEvents(calendarData));
        }

        LOG.info("Read {} source event(s) from {}", events.size(), calendarCollectionUri);
        return List.copyOf(events);
    }

    private HttpResponse<String> executeCalendarQuery() {
        var request = HttpRequest.newBuilder(calendarCollectionUri)
                .method("REPORT", HttpRequest.BodyPublishers.ofString(CALENDAR_QUERY_BODY, StandardCharsets.UTF_8))
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .header("Authorization", "Basic " + basicCredentials)
                .build();

        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CalDavCalendarSourceException(
                    "Failed to reach CalDAV server at " + calendarCollectionUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CalDavCalendarSourceException(
                    "Interrupted while reading from CalDAV server at " + calendarCollectionUri, e);
        }
    }

    private List<String> extractCalendarDataBlobs(String multiStatusXml) {
        Document document;
        try {
            document = newSecureDocumentBuilder().parse(new InputSource(new StringReader(multiStatusXml)));
        } catch (SAXException | IOException e) {
            throw new CalDavCalendarSourceException(
                    "Malformed multistatus XML from " + calendarCollectionUri, e);
        }

        var blobs = new ArrayList<String>();
        var responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response");
        for (int i = 0; i < responses.getLength(); i++) {
            var response = (Element) responses.item(i);
            var propstats = response.getElementsByTagNameNS(DAV_NAMESPACE, "propstat");
            for (int j = 0; j < propstats.getLength(); j++) {
                var propstat = (Element) propstats.item(j);
                if (!isSuccessStatus(propstat)) {
                    continue;
                }
                var calendarDataNodes = propstat.getElementsByTagNameNS(CALDAV_NAMESPACE, "calendar-data");
                if (calendarDataNodes.getLength() == 0) {
                    continue;
                }
                blobs.add(calendarDataNodes.item(0).getTextContent());
            }
        }
        return blobs;
    }

    private boolean isSuccessStatus(Element propstat) {
        NodeList statusNodes = propstat.getElementsByTagNameNS(DAV_NAMESPACE, "status");
        if (statusNodes.getLength() == 0) {
            return false;
        }
        var statusLine = statusNodes.item(0).getTextContent().trim();
        var parts = statusLine.split("\\s+");
        return parts.length >= 2 && parts[1].startsWith("2");
    }

    private static DocumentBuilder newSecureDocumentBuilder() {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new CalDavCalendarSourceException("Failed to configure a secure XML parser", e);
        }
    }

    private List<SourceEvent> parseVEvents(String calendarData) {
        Calendar calendar;
        try {
            calendar = new CalendarBuilder().build(new StringReader(calendarData));
        } catch (IOException | ParserException e) {
            throw new CalDavCalendarSourceException("Malformed calendar-data from " + calendarCollectionUri, e);
        }

        var events = new ArrayList<SourceEvent>();
        for (VEvent vevent : calendar.<VEvent>getComponents(Component.VEVENT)) {
            events.add(toSourceEvent(vevent));
        }
        return events;
    }

    private SourceEvent toSourceEvent(VEvent vevent) {
        var uid = vevent.getUid()
                .map(Uid::getValue)
                .orElseThrow(() -> new CalDavCalendarSourceException(
                        "VEVENT from " + calendarCollectionUri + " is missing UID"));

        DtStart<?> dtStart = vevent.<DtStart<?>>getProperty(Property.DTSTART)
                .orElseThrow(() -> new CalDavCalendarSourceException("VEVENT " + uid + " is missing DTSTART"));
        DtEnd<?> dtEnd = vevent.<DtEnd<?>>getProperty(Property.DTEND)
                .orElseThrow(() -> new CalDavCalendarSourceException("VEVENT " + uid + " is missing DTEND"));

        return new SourceEvent(uid, toZonedDateTime(uid, dtStart), toZonedDateTime(uid, dtEnd));
    }

    private ZonedDateTime toZonedDateTime(String uid, DateProperty<?> dateProperty) {
        LocalDateTime localDateTime;
        try {
            localDateTime = LocalDateTime.from(dateProperty.getDate());
        } catch (RuntimeException e) {
            throw new CalDavCalendarSourceException(
                    "VEVENT " + uid + " has a " + dateProperty.getName() + " without a time component", e);
        }

        var tzId = dateProperty.<TzId>getParameter(Property.TZID);
        if (tzId.isPresent()) {
            return ZonedDateTime.of(localDateTime, ZoneId.of(tzId.get().getValue()));
        }
        if (dateProperty.isUtc()) {
            return ZonedDateTime.of(localDateTime, ZoneOffset.UTC);
        }
        throw new CalDavCalendarSourceException("VEVENT " + uid + " has a " + dateProperty.getName()
                + " with neither a TZID parameter nor a UTC designator");
    }
}
