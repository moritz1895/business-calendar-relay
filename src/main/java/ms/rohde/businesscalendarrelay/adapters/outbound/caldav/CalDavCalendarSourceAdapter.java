package ms.rohde.businesscalendarrelay.adapters.outbound.caldav;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.TzId;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.model.property.Transp;
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
 * is deliberately out of scope for now, per the project {@code CLAUDE.md}.
 *
 * <p>{@code VEVENT} components across every {@code calendar-data} blob in one response
 * are first grouped by {@code UID} (a series master and its {@code RECURRENCE-ID}
 * override components can arrive as separate CalDAV resources), then expanded per group:
 * a group without an {@code RRULE} on its master yields exactly one {@link SourceEvent}
 * as before; a group with an {@code RRULE} is expanded from the master's {@code DTSTART}
 * using ical4j's {@link Recur#getDates(Object, Temporal, Temporal)}, bounded forward at
 * {@code now.plus(recurringEventHorizon)} and deliberately left unbounded backward (see
 * {@code docs/features/event-filtering.md}'s "Auflösung von {@code EXDATE} und
 * {@code RECURRENCE-ID}" section). {@code EXDATE}-listed occurrences are dropped,
 * {@code RECURRENCE-ID} overrides replace their series-computed slot with the override's
 * own window, an override itself carrying {@code STATUS:CANCELLED} is dropped entirely,
 * and a {@code STATUS:CANCELLED} master propagates {@code cancelled = true} to every
 * occurrence still emitted for the series rather than dropping the series.
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

    /**
     * Default zone applied to all-day ({@code VALUE=DATE}) occurrences, matching the
     * {@code VTIMEZONE} convention already hardcoded in {@code ImipCalendarRenderer}. The
     * exact clock time chosen for an all-day event is inconsequential: {@code allDay}
     * events are never creation-eligible per {@code RelayDiffPlanner}'s gate.
     */
    private static final ZoneId ALL_DAY_ZONE = ZoneId.of("Europe/Berlin");

    /**
     * Lower bound used for recurrence expansion. Deliberately not a computed "far past"
     * value relative to {@code now} — the series-expansion range is unbounded backward by
     * design (see class Javadoc), so this is simply a fixed date far enough in the past
     * that no real CalDAV calendar could ever have an earlier occurrence.
     */
    private static final ZonedDateTime UNBOUNDED_PAST = ZonedDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

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
    private final Clock clock;
    private final Period recurringEventHorizon;

    public CalDavCalendarSourceAdapter(
            HttpClient httpClient,
            URI calendarCollectionUri,
            String username,
            String password,
            Clock clock,
            Period recurringEventHorizon) {
        this.httpClient = httpClient;
        this.calendarCollectionUri = calendarCollectionUri;
        this.basicCredentials = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
        this.recurringEventHorizon = recurringEventHorizon;
    }

    @Override
    public List<SourceEvent> readEvents() {
        var response = executeCalendarQuery();

        if (response.statusCode() != MULTI_STATUS) {
            throw new CalDavCalendarSourceException("Unexpected CalDAV REPORT response status "
                    + response.statusCode() + " from " + calendarCollectionUri);
        }

        var allVEvents = new ArrayList<VEvent>();
        for (var calendarData : extractCalendarDataBlobs(response.body())) {
            allVEvents.addAll(parseVEvents(calendarData));
        }

        var now = ZonedDateTime.now(clock);
        var events = expandAll(allVEvents, now);

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

    private List<VEvent> parseVEvents(String calendarData) {
        Calendar calendar;
        try {
            calendar = new CalendarBuilder().build(new StringReader(calendarData));
        } catch (IOException | ParserException e) {
            throw new CalDavCalendarSourceException("Malformed calendar-data from " + calendarCollectionUri, e);
        }
        return calendar.getComponents(Component.VEVENT);
    }

    /**
     * Groups every parsed {@code VEVENT} by {@code UID} first (a series master and its
     * {@code RECURRENCE-ID} overrides may have arrived from different {@code calendar-data}
     * blobs), then expands each group independently.
     */
    private List<SourceEvent> expandAll(List<VEvent> allVEvents, ZonedDateTime now) {
        var byUid = new LinkedHashMap<String, List<VEvent>>();
        for (var vevent : allVEvents) {
            byUid.computeIfAbsent(requireUid(vevent), key -> new ArrayList<>()).add(vevent);
        }

        var result = new ArrayList<SourceEvent>();
        for (var entry : byUid.entrySet()) {
            result.addAll(expandSeries(entry.getKey(), entry.getValue(), now));
        }
        return result;
    }

    private String requireUid(VEvent vevent) {
        return vevent.getUid()
                .map(Uid::getValue)
                .orElseThrow(() -> new CalDavCalendarSourceException(
                        "VEVENT from " + calendarCollectionUri + " is missing UID"));
    }

    private List<SourceEvent> expandSeries(String uid, List<VEvent> components, ZonedDateTime now) {
        VEvent master = null;
        var overrides = new ArrayList<VEvent>();
        for (var component : components) {
            if (component.getProperty(Property.RECURRENCE_ID).isPresent()) {
                overrides.add(component);
            } else {
                master = component;
            }
        }

        if (master == null) {
            throw new CalDavCalendarSourceException("UID " + uid + " from " + calendarCollectionUri
                    + " has RECURRENCE-ID override component(s) but no master VEVENT");
        }

        Optional<Property> rruleProperty = master.getProperty(Property.RRULE);
        if (rruleProperty.isEmpty()) {
            return List.of(toSingleSourceEvent(uid, master));
        }

        return expandRecurringSeries(uid, master, rruleProperty.get(), overrides, now);
    }

    private SourceEvent toSingleSourceEvent(String uid, VEvent vevent) {
        var dtStart = requireDtStart(uid, vevent);
        var dtEnd = requireDtEnd(uid, vevent);
        return new SourceEvent(
                uid,
                toZonedDateTime(uid, dtStart),
                toZonedDateTime(uid, dtEnd),
                isDateOnlyValue(dtStart),
                busy(vevent),
                false,
                statusCancelled(vevent));
    }

    private List<SourceEvent> expandRecurringSeries(
            String uid, VEvent master, Property rruleProperty, List<VEvent> overrides, ZonedDateTime now) {
        var masterDtStart = requireDtStart(uid, master);
        var masterDtEnd = requireDtEnd(uid, master);
        var masterForm = formOf(masterDtStart);
        var masterStart = toZonedDateTime(uid, masterDtStart);
        var masterEnd = toZonedDateTime(uid, masterDtEnd);
        var masterDuration = Duration.between(masterStart, masterEnd);
        var masterAllDay = masterForm.valueIsDate();
        var masterBusy = busy(master);
        var masterCancelled = statusCancelled(master);

        Recur<ZonedDateTime> recur;
        try {
            recur = new Recur<>(rruleProperty.getValue());
        } catch (RuntimeException e) {
            throw new CalDavCalendarSourceException("VEVENT " + uid + " has a malformed RRULE", e);
        }

        var horizonEnd = now.plus(recurringEventHorizon);
        List<ZonedDateTime> occurrenceStarts;
        try {
            occurrenceStarts = recur.getDates(masterStart, UNBOUNDED_PAST, horizonEnd);
        } catch (RuntimeException e) {
            throw new CalDavCalendarSourceException("VEVENT " + uid + " has an RRULE that could not be expanded", e);
        }

        var exceptionDates = exceptionDates(uid, master, masterForm);
        var overridesByOccurrence = overridesByRecurrenceId(uid, overrides, masterForm);

        var result = new ArrayList<SourceEvent>();
        for (var occurrenceStart : occurrenceStarts.stream().sorted().toList()) {
            if (exceptionDates.contains(occurrenceStart)) {
                continue;
            }

            var sourceUid = uid + "#" + occurrenceStart.toInstant();
            var override = overridesByOccurrence.get(occurrenceStart);
            if (override != null) {
                if (statusCancelled(override)) {
                    continue;
                }
                var overrideDtStart = requireDtStart(uid, override);
                var overrideDtEnd = requireDtEnd(uid, override);
                result.add(new SourceEvent(
                        sourceUid,
                        toZonedDateTime(uid, overrideDtStart),
                        toZonedDateTime(uid, overrideDtEnd),
                        isDateOnlyValue(overrideDtStart),
                        busy(override),
                        true,
                        masterCancelled));
            } else {
                result.add(new SourceEvent(
                        sourceUid,
                        occurrenceStart,
                        occurrenceStart.plus(masterDuration),
                        masterAllDay,
                        masterBusy,
                        true,
                        masterCancelled));
            }
        }
        return result;
    }

    private Set<ZonedDateTime> exceptionDates(String uid, VEvent master, DateForm masterForm) {
        List<ExDate<?>> exDateProperties = master.getProperties(Property.EXDATE);
        var exceptionDates = new HashSet<ZonedDateTime>();
        for (var exDate : exDateProperties) {
            for (Temporal date : exDate.getDates()) {
                exceptionDates.add(toZonedDateTime(uid, exDate.getName(), date, masterForm));
            }
        }
        return exceptionDates;
    }

    private Map<ZonedDateTime, VEvent> overridesByRecurrenceId(String uid, List<VEvent> overrides, DateForm masterForm) {
        var byOccurrence = new HashMap<ZonedDateTime, VEvent>();
        for (var override : overrides) {
            RecurrenceId<?> recurrenceId = override
                    .<RecurrenceId<?>>getProperty(Property.RECURRENCE_ID)
                    .orElseThrow(() ->
                            new CalDavCalendarSourceException("VEVENT " + uid + " override is missing RECURRENCE-ID"));
            var occurrence = toZonedDateTime(uid, recurrenceId.getName(), recurrenceId.getDate(), masterForm);
            byOccurrence.merge(
                    occurrence,
                    override,
                    (existing, candidate) -> sequenceNumber(candidate) >= sequenceNumber(existing) ? candidate : existing);
        }
        return byOccurrence;
    }

    private int sequenceNumber(VEvent vevent) {
        return vevent.<Sequence>getProperty(Property.SEQUENCE)
                .map(Sequence::getSequenceNo)
                .orElse(0);
    }

    private boolean busy(VEvent vevent) {
        return vevent.<Transp>getProperty(Property.TRANSP)
                .map(transp -> !Transp.VALUE_TRANSPARENT.equals(transp.getValue()))
                .orElse(true);
    }

    private boolean statusCancelled(VEvent vevent) {
        return vevent.<Status>getProperty(Property.STATUS)
                .map(status -> Status.VALUE_CANCELLED.equals(status.getValue()))
                .orElse(false);
    }

    private DtStart<?> requireDtStart(String uid, VEvent vevent) {
        return vevent.<DtStart<?>>getProperty(Property.DTSTART)
                .orElseThrow(() -> new CalDavCalendarSourceException("VEVENT " + uid + " is missing DTSTART"));
    }

    private DtEnd<?> requireDtEnd(String uid, VEvent vevent) {
        return vevent.<DtEnd<?>>getProperty(Property.DTEND)
                .orElseThrow(() -> new CalDavCalendarSourceException("VEVENT " + uid + " is missing DTEND"));
    }

    private boolean isDateOnlyValue(DateProperty<?> dateProperty) {
        return dateProperty
                .<Value>getParameter(Parameter.VALUE)
                .map(value -> Value.DATE.getValue().equals(value.getValue()))
                .orElse(false);
    }

    /**
     * A property's date/time "form", per RFC 5545: whether it is a {@code VALUE=DATE}
     * (all-day) value, and — if not — the {@code TZID} zone or UTC-ness that resolves its
     * floating local time to a zone. {@code EXDATE} and {@code RECURRENCE-ID} values must
     * share the same form as their series master's {@code DTSTART}, so this is computed
     * once per master and reused rather than re-derived from each individual property.
     */
    private record DateForm(boolean valueIsDate, Optional<TzId> tzId, boolean utc) {}

    private DateForm formOf(DateProperty<?> dateProperty) {
        return new DateForm(
                isDateOnlyValue(dateProperty), dateProperty.<TzId>getParameter(Property.TZID), dateProperty.isUtc());
    }

    private ZonedDateTime toZonedDateTime(String uid, DateProperty<?> dateProperty) {
        return toZonedDateTime(uid, dateProperty.getName(), dateProperty.getDate(), formOf(dateProperty));
    }

    private ZonedDateTime toZonedDateTime(String uid, String propertyName, Temporal rawValue, DateForm form) {
        if (form.valueIsDate()) {
            LocalDate localDate;
            try {
                localDate = LocalDate.from(rawValue);
            } catch (RuntimeException e) {
                throw new CalDavCalendarSourceException(
                        "VEVENT " + uid + " has a " + propertyName + " with VALUE=DATE that could not be parsed", e);
            }
            return localDate.atStartOfDay(ALL_DAY_ZONE);
        }

        LocalDateTime localDateTime;
        try {
            localDateTime = LocalDateTime.from(rawValue);
        } catch (RuntimeException e) {
            throw new CalDavCalendarSourceException(
                    "VEVENT " + uid + " has a " + propertyName + " without a time component", e);
        }

        if (form.tzId().isPresent()) {
            return ZonedDateTime.of(localDateTime, ZoneId.of(form.tzId().get().getValue()));
        }
        if (form.utc()) {
            return ZonedDateTime.of(localDateTime, ZoneOffset.UTC);
        }
        throw new CalDavCalendarSourceException(
                "VEVENT " + uid + " has a " + propertyName + " with neither a TZID parameter nor a UTC designator");
    }
}
