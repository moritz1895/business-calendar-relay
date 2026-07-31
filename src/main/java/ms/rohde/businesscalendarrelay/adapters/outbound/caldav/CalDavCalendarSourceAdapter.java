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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedCalendarResource;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarReplicaStore;
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
import net.fortuna.ical4j.util.CompatibilityHints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Reads the full current set of {@code VEVENT}s from one private CalDAV calendar
 * collection.
 *
 * <p>One instance per configured source calendar, per {@link CalendarSource}'s own
 * contract. {@code readEvents()}'s public contract is unchanged: it always returns a full,
 * current {@link SourceEvent} snapshot, never a delta. Internally, it uses one of two
 * mechanisms to obtain the raw CalDAV resources that snapshot is built from (see
 * {@code docs/features/delta-sync.md}):
 *
 * <ul>
 *   <li>An RFC 6578 {@code sync-collection} REPORT with a persisted sync-token, when
 *       {@code deltaSyncEnabled} is {@code true} and the CalDAV server is not (yet) known
 *       to reject the report. Only the resources reported changed or removed since the
 *       last poll are transferred over the wire; the full raw-resource set is
 *       reconstructed locally from {@link CalendarReplicaStore} before every expansion.
 *   <li>The legacy, always-full RFC 4791 {@code calendar-query} REPORT with no time-range
 *       restriction, used when {@code deltaSyncEnabled} is {@code false}, or once the
 *       server has been observed to reject {@code sync-collection} (a permanent,
 *       in-memory, non-persisted fallback for the remaining lifetime of this instance).
 * </ul>
 *
 * <p>Whichever mechanism supplied the raw resources, the same unchanged pipeline turns
 * them into {@link SourceEvent}s: {@code VEVENT} components across every {@code
 * calendar-data} blob are first grouped by {@code UID} (a series master and its {@code
 * RECURRENCE-ID} override components can arrive as separate CalDAV resources), then
 * expanded per group: a group without an {@code RRULE} on its master yields exactly one
 * {@link SourceEvent} as before; a group with an {@code RRULE} is expanded from the
 * master's {@code DTSTART} using ical4j's {@link Recur#getDates(Object, Temporal,
 * Temporal)}, bounded forward at {@code now.plus(recurringEventHorizon)} and deliberately
 * left unbounded backward (see {@code docs/features/event-filtering.md}'s "Auflösung von
 * {@code EXDATE} und {@code RECURRENCE-ID}" section). {@code EXDATE}-listed occurrences
 * are dropped, {@code RECURRENCE-ID} overrides replace their series-computed slot with the
 * override's own window, an override itself carrying {@code STATUS:CANCELLED} is dropped
 * entirely, and a {@code STATUS:CANCELLED} master propagates {@code cancelled = true} to
 * every occurrence still emitted for the series rather than dropping the series. A single
 * {@code UID} group that turns out to be semantically incomplete (missing {@code UID},
 * {@code DTSTART}, or {@code DTEND}) is logged and skipped rather than aborting the whole
 * read -- see {@link #expandAll(List, ZonedDateTime)}.
 *
 * <p>Deliberately not wired as an auto-scanned, no-arg Spring singleton bean: the
 * collection URL, credentials, and {@link CalendarReplicaStore} instance are per-calendar
 * constructor arguments, not Spring beans, the same resolution already used by {@code
 * JpaStateStoreAdapter} for its per-calendar {@code sourceCalendarId}. {@code
 * RelayWiringConfiguration} constructs one instance per configured calendar.
 *
 * <p>Basic credentials are sent directly on every request rather than relying on {@code
 * java.net.Authenticator} challenge-response, since CalDAV servers do not reliably issue a
 * clean {@code 401} challenge before accepting Basic auth on a {@code REPORT}.
 */
@InfrastructureServiceAdapter
public final class CalDavCalendarSourceAdapter implements CalendarSource {

    private static final Logger LOG = LogManager.getLogger(CalDavCalendarSourceAdapter.class);

    private static final String DAV_NAMESPACE = "DAV:";

    private static final String CALDAV_NAMESPACE = "urn:ietf:params:xml:ns:caldav";

    private static final int MULTI_STATUS = 207;

    private static final int FORBIDDEN = 403;

    private static final int INSUFFICIENT_STORAGE = 507;

    private static final int NOT_IMPLEMENTED = 501;

    private static final int UNSUPPORTED_MEDIA_TYPE = 415;

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

    static {
        // Real-world CalDAV exports carry non-RFC-5545-conformant property values on
        // properties this adapter never reads at all (e.g. a CREATED without the mandatory
        // trailing "Z", observed from an aging Nextcloud-generated entry). ical4j's strict
        // parsing aborts CalendarBuilder#build() entirely on any single such value, failing
        // the whole readEvents() call -- and with it every occurrence in that CalDAV
        // resource, not just the one malformed property. Relaxed parsing accepts the
        // non-conformant value instead, verified against exactly this failure mode.
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true);
    }

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

    private static final String SYNC_COLLECTION_BODY_TEMPLATE =
            """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:sync-collection xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:sync-token>%s</D:sync-token>
              <D:sync-level>1</D:sync-level>
              <D:prop>
                <D:getetag/>
                <C:calendar-data/>
              </D:prop>
            </D:sync-collection>
            """;

    private final HttpClient httpClient;
    private final URI calendarCollectionUri;
    private final String basicCredentials;
    private final Clock clock;
    private final Period recurringEventHorizon;
    private final CalendarReplicaStore calendarReplicaStore;
    private final boolean deltaSyncEnabled;

    /**
     * Set once, permanently, in memory (never persisted) the first time this instance
     * observes a {@code sync-collection} response recognized as a definite non-support
     * signal -- see {@link #isDefinitelyUnsupportedResponse(HttpResponse)}. Once set,
     * every subsequent {@link #readEvents()} call on this instance uses the legacy {@code
     * calendar-query} request directly, without touching {@link #calendarReplicaStore} or
     * attempting {@code sync-collection} again. A process restart resets this and
     * re-attempts {@code sync-collection} once.
     *
     * <p>Deliberately narrow: an unrecognized status (e.g. a transient {@code 503 Service
     * Unavailable}) does <em>not</em> set this flag -- see {@code
     * docs/features/delta-sync.md}'s "Fehlerfälle — Ergänzungen", which explicitly calls
     * out that permanently downgrading on a transient error would be wrong, since the next
     * poll can simply retry {@code sync-collection} with the same, still-valid token.
     */
    private boolean deltaSyncPermanentlyDisabled;

    public CalDavCalendarSourceAdapter(
            HttpClient httpClient,
            URI calendarCollectionUri,
            String username,
            String password,
            Clock clock,
            Period recurringEventHorizon,
            CalendarReplicaStore calendarReplicaStore,
            boolean deltaSyncEnabled) {
        this.httpClient = httpClient;
        this.calendarCollectionUri = calendarCollectionUri;
        this.basicCredentials = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
        this.recurringEventHorizon = recurringEventHorizon;
        this.calendarReplicaStore = calendarReplicaStore;
        this.deltaSyncEnabled = deltaSyncEnabled;
    }

    @Override
    public List<SourceEvent> readEvents() {
        var allVEvents =
                deltaSyncEnabled ? readAllVEventsViaDeltaSyncOrFallback() : readAllVEventsViaCalendarQuery();

        var now = ZonedDateTime.now(clock);
        var events = expandAll(allVEvents, now);

        LOG.info("Read {} source event(s) from {}", events.size(), calendarCollectionUri);
        return List.copyOf(events);
    }

    // --- sync-collection (RFC 6578) delta sync ---

    private List<VEvent> readAllVEventsViaDeltaSyncOrFallback() {
        if (deltaSyncPermanentlyDisabled) {
            return readAllVEventsViaCalendarQuery();
        }
        try {
            syncReplicaWithServer();
        } catch (SyncCollectionUnsupportedException e) {
            deltaSyncPermanentlyDisabled = true;
            LOG.warn(
                    "CalDAV server at {} rejected sync-collection with status {}; permanently falling back to"
                            + " calendar-query for the remaining lifetime of this instance. Response body: {}",
                    calendarCollectionUri,
                    e.statusCode,
                    e.responseBody);
            return readAllVEventsViaCalendarQuery();
        }
        return toVEvents(loadReplicaResources());
    }

    private void syncReplicaWithServer() {
        var token = loadSyncToken();
        if (token == null) {
            performInitialSync();
        } else {
            performIncrementalSync(token);
        }
    }

    private void performInitialSync() {
        var response = executeSyncCollection("");
        if (response.statusCode() != MULTI_STATUS) {
            throw unexpectedSyncCollectionResponse(response);
        }
        var result = parseSyncCollectionResponse(response.body());
        resetReplica(result.newSyncToken(), result.changedResources());
    }

    private void performIncrementalSync(String token) {
        var response = executeSyncCollection(token);
        if (response.statusCode() == MULTI_STATUS) {
            var result = parseSyncCollectionResponse(response.body());
            applyReplicaDelta(result.newSyncToken(), result.changedResources(), result.removedHrefs());
            return;
        }
        if (isInvalidSyncTokenResponse(response)) {
            performInitialSync();
            return;
        }
        throw unexpectedSyncCollectionResponse(response);
    }

    /**
     * Classifies a {@code sync-collection} response that is neither {@code 207
     * Multi-Status} nor a recognized invalid-sync-token response. Per {@code
     * docs/features/delta-sync.md}'s "Fehlerfälle — Ergänzungen", only the specific,
     * recognized non-support signals below permanently disable {@code sync-collection} for
     * the remaining lifetime of this instance ({@link SyncCollectionUnsupportedException});
     * every other, unrecognized status (e.g. a transient {@code 503 Service Unavailable})
     * fails only the current poll cycle with a plain {@link CalDavCalendarSourceException}
     * -- the next poll retries {@code sync-collection} with the same, still-valid token.
     */
    private RuntimeException unexpectedSyncCollectionResponse(HttpResponse<String> response) {
        if (isDefinitelyUnsupportedResponse(response)) {
            return new SyncCollectionUnsupportedException(response.statusCode(), response.body());
        }
        return new CalDavCalendarSourceException("Unexpected sync-collection REPORT response status "
                + response.statusCode() + " from " + calendarCollectionUri);
    }

    /**
     * The specific, recognized signals that a CalDAV server does not support {@code
     * sync-collection} at all for this collection: {@code 501 Not Implemented}, {@code 415
     * Unsupported Media Type}, or a {@code 403 Forbidden} that does <em>not</em> carry the
     * {@code <D:valid-sync-token/>} precondition (e.g. a {@code <D:supported-report/>}
     * precondition per RFC 3253, or no recognizable precondition body at all). Deliberately
     * narrower than "any non-207, non-invalid-token response" -- an unrecognized status
     * outside this set is treated as a transient failure of the current cycle only, not as
     * evidence the server lacks {@code sync-collection} support.
     */
    private boolean isDefinitelyUnsupportedResponse(HttpResponse<String> response) {
        if (response.statusCode() == NOT_IMPLEMENTED || response.statusCode() == UNSUPPORTED_MEDIA_TYPE) {
            return true;
        }
        return response.statusCode() == FORBIDDEN && !containsValidSyncTokenPrecondition(response.body());
    }

    private HttpResponse<String> executeSyncCollection(String syncToken) {
        return executeReport(SYNC_COLLECTION_BODY_TEMPLATE.formatted(escapeXml(syncToken)));
    }

    private @Nullable String loadSyncToken() {
        try {
            return calendarReplicaStore.loadSyncToken();
        } catch (RuntimeException e) {
            throw new CalDavCalendarSourceException(
                    "Failed to load sync-token from calendar replica store for " + calendarCollectionUri, e);
        }
    }

    private List<CachedCalendarResource> loadReplicaResources() {
        try {
            return calendarReplicaStore.loadAllResources();
        } catch (RuntimeException e) {
            throw new CalDavCalendarSourceException(
                    "Failed to load cached resources from calendar replica store for " + calendarCollectionUri, e);
        }
    }

    private void resetReplica(String newSyncToken, List<CachedCalendarResource> resources) {
        try {
            calendarReplicaStore.resetTo(newSyncToken, resources);
        } catch (RuntimeException e) {
            throw new CalDavCalendarSourceException(
                    "Failed to reset calendar replica store for " + calendarCollectionUri, e);
        }
    }

    private void applyReplicaDelta(
            String newSyncToken, List<CachedCalendarResource> upserted, List<String> removedHrefs) {
        try {
            calendarReplicaStore.applyDelta(newSyncToken, upserted, removedHrefs);
        } catch (RuntimeException e) {
            throw new CalDavCalendarSourceException(
                    "Failed to apply calendar replica delta for " + calendarCollectionUri, e);
        }
    }

    private List<VEvent> toVEvents(List<CachedCalendarResource> resources) {
        var allVEvents = new ArrayList<VEvent>();
        for (var resource : resources) {
            allVEvents.addAll(parseVEvents(resource.rawCalendarData()));
        }
        return allVEvents;
    }

    /**
     * Result of one {@code sync-collection} REPORT exchange: the token to persist for the
     * next incremental request, every resource reported new or changed (with its raw
     * {@code calendar-data} and {@code etag}), and the {@code href}s of every resource
     * reported removed.
     */
    private record SyncCollectionResult(
            String newSyncToken, List<CachedCalendarResource> changedResources, List<String> removedHrefs) {}

    private SyncCollectionResult parseSyncCollectionResponse(String multiStatusXml) {
        Document document;
        try {
            document = newSecureDocumentBuilder().parse(new InputSource(new StringReader(multiStatusXml)));
        } catch (SAXException | IOException e) {
            throw new CalDavCalendarSourceException(
                    "Malformed sync-collection multistatus XML from " + calendarCollectionUri, e);
        }

        var syncTokenNodes = document.getElementsByTagNameNS(DAV_NAMESPACE, "sync-token");
        if (syncTokenNodes.getLength() == 0) {
            throw new CalDavCalendarSourceException("sync-collection multistatus response from "
                    + calendarCollectionUri + " is missing a top-level sync-token");
        }
        var newSyncToken = syncTokenNodes.item(0).getTextContent().trim();

        var changedResources = new LinkedHashMap<String, CachedCalendarResource>();
        var removedHrefs = new LinkedHashSet<String>();

        var responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response");
        for (int i = 0; i < responses.getLength(); i++) {
            var response = (Element) responses.item(i);
            var hrefNodes = response.getElementsByTagNameNS(DAV_NAMESPACE, "href");
            if (hrefNodes.getLength() == 0) {
                continue;
            }
            var href = hrefNodes.item(0).getTextContent().trim();

            var directStatus = directChildElement(response, DAV_NAMESPACE, "status");
            if (directStatus.isPresent()) {
                if (isNotFoundStatus(directStatus.get())) {
                    removedHrefs.add(href);
                }
                continue;
            }

            var propstats = response.getElementsByTagNameNS(DAV_NAMESPACE, "propstat");
            for (int j = 0; j < propstats.getLength(); j++) {
                var propstat = (Element) propstats.item(j);
                if (!isSuccessStatus(propstat)) {
                    continue;
                }
                var etagNodes = propstat.getElementsByTagNameNS(DAV_NAMESPACE, "getetag");
                var calendarDataNodes = propstat.getElementsByTagNameNS(CALDAV_NAMESPACE, "calendar-data");
                if (etagNodes.getLength() == 0 || calendarDataNodes.getLength() == 0) {
                    continue;
                }
                changedResources.put(
                        href,
                        new CachedCalendarResource(
                                href,
                                etagNodes.item(0).getTextContent(),
                                calendarDataNodes.item(0).getTextContent()));
            }
        }

        return new SyncCollectionResult(newSyncToken, List.copyOf(changedResources.values()), List.copyOf(removedHrefs));
    }

    private Optional<Element> directChildElement(Element parent, String namespaceUri, String localName) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element
                    && namespaceUri.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    private boolean isNotFoundStatus(Element statusElement) {
        var statusLine = statusElement.getTextContent().trim();
        var parts = statusLine.split("\\s+");
        return parts.length >= 2 && "404".equals(parts[1]);
    }

    private boolean isInvalidSyncTokenResponse(HttpResponse<String> response) {
        if (response.statusCode() == INSUFFICIENT_STORAGE) {
            return true;
        }
        return response.statusCode() == FORBIDDEN && containsValidSyncTokenPrecondition(response.body());
    }

    private boolean containsValidSyncTokenPrecondition(String body) {
        Document document;
        try {
            document = newSecureDocumentBuilder().parse(new InputSource(new StringReader(body)));
        } catch (SAXException | IOException e) {
            return false;
        }
        var errorNodes = document.getElementsByTagNameNS(DAV_NAMESPACE, "error");
        for (int i = 0; i < errorNodes.getLength(); i++) {
            var error = (Element) errorNodes.item(i);
            if (error.getElementsByTagNameNS(DAV_NAMESPACE, "valid-sync-token").getLength() > 0) {
                return true;
            }
        }
        return false;
    }

    private static String escapeXml(String value) {
        var builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '"' -> builder.append("&quot;");
                case '\'' -> builder.append("&apos;");
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * Internal control-flow signal only, never thrown out of {@link #readEvents()}: raised
     * only for the specific, recognized non-support signals in
     * {@link #isDefinitelyUnsupportedResponse(HttpResponse)}, meaning the server is treated
     * as not supporting {@code sync-collection} for this collection at all. Any other
     * unexpected response status is a plain {@link CalDavCalendarSourceException} instead,
     * failing only the current poll cycle without setting the permanent fallback flag.
     */
    private static final class SyncCollectionUnsupportedException extends RuntimeException {

        private final int statusCode;
        private final String responseBody;

        private SyncCollectionUnsupportedException(int statusCode, String responseBody) {
            super("sync-collection unsupported: status " + statusCode);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }

    // --- calendar-query (RFC 4791) legacy full read ---

    private List<VEvent> readAllVEventsViaCalendarQuery() {
        var response = executeCalendarQuery();

        if (response.statusCode() != MULTI_STATUS) {
            throw new CalDavCalendarSourceException("Unexpected CalDAV REPORT response status "
                    + response.statusCode() + " from " + calendarCollectionUri);
        }

        var allVEvents = new ArrayList<VEvent>();
        for (var calendarData : extractCalendarDataBlobs(response.body())) {
            allVEvents.addAll(parseVEvents(calendarData));
        }
        return allVEvents;
    }

    private HttpResponse<String> executeCalendarQuery() {
        return executeReport(CALENDAR_QUERY_BODY);
    }

    private HttpResponse<String> executeReport(String body) {
        var request = HttpRequest.newBuilder(calendarCollectionUri)
                .method("REPORT", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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
     *
     * <p>A single UID group that fails to expand (missing {@code UID}, missing {@code
     * DTSTART}/{@code DTEND}, or {@code RECURRENCE-ID} override(s) without a master) is
     * logged at {@code WARN} and skipped rather than aborting the whole read -- see {@code
     * docs/adr/012-skip-malformed-vevent-groups-instead-of-aborting-cycle.md}. This is
     * deliberately narrower than {@link #parseVEvents(String)}'s all-or-nothing failure
     * mode: a response that fails to parse as ICS at all, or an unexpected HTTP status,
     * still aborts the whole {@link #readEvents()} call, since none of that response's data
     * can be trusted at all -- whereas a structurally valid response containing one
     * semantically incomplete {@code VEVENT} still yields trustworthy data for every other
     * {@code VEVENT} in it.
     */
    private List<SourceEvent> expandAll(List<VEvent> allVEvents, ZonedDateTime now) {
        var byUid = new LinkedHashMap<String, List<VEvent>>();
        for (var vevent : allVEvents) {
            String uid;
            try {
                uid = requireUid(vevent);
            } catch (CalDavCalendarSourceException e) {
                LOG.warn(
                        "Skipping unparseable VEVENT ({}) from {}: {}",
                        describeForLogging(vevent),
                        calendarCollectionUri,
                        e.getMessage());
                continue;
            }
            byUid.computeIfAbsent(uid, key -> new ArrayList<>()).add(vevent);
        }

        var result = new ArrayList<SourceEvent>();
        for (var entry : byUid.entrySet()) {
            try {
                result.addAll(expandSeries(entry.getKey(), entry.getValue(), now));
            } catch (CalDavCalendarSourceException e) {
                LOG.warn(
                        "Skipping unparseable VEVENT UID={} ({}) from {}: {}",
                        entry.getKey(),
                        describeForLogging(entry.getValue().getFirst()),
                        calendarCollectionUri,
                        e.getMessage());
            }
        }
        return result;
    }

    private String requireUid(VEvent vevent) {
        return vevent.getUid()
                .map(Uid::getValue)
                .orElseThrow(() -> new CalDavCalendarSourceException(
                        "VEVENT from " + calendarCollectionUri + " is missing UID"));
    }

    /**
     * Best-effort, log-only identification for a {@code VEVENT} that failed the creation
     * pipeline (missing {@code UID}/{@code DTSTART}/{@code DTEND}) -- purely a diagnostic
     * aid so the calendar owner can find and fix/delete the offending entry, never fed into
     * {@link SourceEvent} itself, which deliberately carries no title (see {@code
     * docs/domain.md}). Both {@code SUMMARY} and {@code DTSTAMP} are read directly and
     * independently of whichever property actually caused the failure, so this still
     * produces useful output even when, e.g., {@code DTSTART} itself is the missing one.
     */
    private String describeForLogging(VEvent vevent) {
        var summary = vevent.getProperty(Property.SUMMARY)
                .map(Property::getValue)
                .orElse("(kein SUMMARY)");
        var dtstamp = vevent.getProperty(Property.DTSTAMP)
                .map(Property::getValue)
                .orElse("(kein DTSTAMP)");
        return "SUMMARY=\"" + summary + "\", DTSTAMP=" + dtstamp;
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
