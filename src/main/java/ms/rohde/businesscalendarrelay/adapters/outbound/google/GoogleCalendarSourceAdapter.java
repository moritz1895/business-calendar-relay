package ms.rohde.businesscalendarrelay.adapters.outbound.google;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedGoogleCalendarEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarSource;
import ms.rohde.businesscalendarrelay.ports.outbound.GoogleCalendarReplicaStore;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the full current set of events from one private Google Calendar via the Google
 * Calendar REST API v3 (`events.list`).
 *
 * <p>One instance per configured source calendar, per {@link CalendarSource}'s own
 * contract. {@code readEvents()}'s public contract is unchanged: it always returns a full,
 * current {@link SourceEvent} snapshot, never a delta. Internally, exactly like {@code
 * CalDavCalendarSourceAdapter}, it uses one of two mechanisms to obtain that snapshot (see
 * {@code docs/features/google-calendar-integration.md}):
 *
 * <ul>
 *   <li>{@code syncToken}-based incremental sync, when {@code deltaSyncEnabled} is {@code
 *       true}: an {@code events.list(syncToken=...)} delta updates a local replica ({@link
 *       GoogleCalendarReplicaStore}), which is then re-read in full and unioned with a
 *       second, narrow, {@code syncToken}-independent request bounded to {@code [now, now +
 *       recurringEventHorizon]} -- the "horizon-bound supplemental fetch" from
 *       Design-Entscheidung 3, which exists solely so a long-running, unchanged recurring
 *       series keeps revealing new occurrences purely as {@code now} advances, something a
 *       pure {@code syncToken} delta (which only ever reports genuine
 *       creations/changes/removals) cannot do on its own.
 *   <li>A single, always-full {@code events.list(singleEvents=true, showDeleted=false,
 *       timeMax=now+recurringEventHorizon)} request with no {@code syncToken}, used when
 *       {@code deltaSyncEnabled} is {@code false}. The replica is never touched on this
 *       path, mirroring {@code CalDavCalendarSourceAdapter}'s legacy {@code calendar-query}
 *       fallback.
 * </ul>
 *
 * <p>Recursion is resolved server-side via {@code singleEvents=true} rather than by any
 * client-side RRULE expansion -- see Design-Entscheidung 3. Each expanded occurrence's
 * composite {@code sourceUid} is built the same way as CalDAV's (a stable series identifier,
 * {@code recurringEventId}, plus the occurrence's series-computed original start instant),
 * just sourced from Google's {@code recurringEventId}/{@code originalStartTime} JSON fields
 * instead of ICS {@code UID}/{@code RECURRENCE-ID} properties.
 *
 * <p>JSON parsing uses plain Jackson ({@link ObjectMapper}/{@link JsonNode}, already a
 * transitive {@code spring-boot-starter-web} dependency), not the official Google API Java
 * SDK -- see {@code docs/features/google-calendar-integration.md} for the justification
 * (avoids a large, largely-unused dependency chain for a handful of read-only REST calls),
 * consistent with {@code CalDavCalendarSourceAdapter} using plain {@link HttpClient} instead
 * of a dedicated CalDAV SDK.
 *
 * <p>OAuth access-token exchange (refresh-token -&gt; access-token) is purely internal,
 * protocol-level adapter logic, not a separate hexagonal port -- see
 * {@code docs/features/google-calendar-integration.md}'s "Weitere Entscheidungen". The
 * exchanged access token is cached as an instance field together with its expiry (minus a
 * small safety buffer) and only refreshed once expired -- see {@link #obtainAccessToken()}.
 *
 * <p>Deliberately not wired as an auto-scanned, no-arg Spring singleton bean, for the same
 * reason as {@code CalDavCalendarSourceAdapter} -- {@code RelayWiringConfiguration}
 * constructs one instance per configured Google calendar by hand.
 */
@InfrastructureServiceAdapter
public final class GoogleCalendarSourceAdapter implements CalendarSource {

    private static final Logger LOG = LogManager.getLogger(GoogleCalendarSourceAdapter.class);

    private static final String DEFAULT_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    private static final String DEFAULT_EVENTS_ENDPOINT_TEMPLATE =
            "https://www.googleapis.com/calendar/v3/calendars/%s/events";

    private static final int HTTP_OK = 200;

    private static final int HTTP_GONE = 410;

    /**
     * Safety buffer subtracted from an access token's server-reported {@code expires_in}
     * before it is considered expired locally, so a token that is valid but about to expire
     * within the next HTTP round trip is proactively refreshed rather than risking a
     * request that starts valid but is rejected mid-flight.
     */
    private static final long ACCESS_TOKEN_EXPIRY_SAFETY_BUFFER_SECONDS = 60;

    private static final long DEFAULT_ACCESS_TOKEN_EXPIRES_IN_SECONDS = 3600;

    /**
     * Default zone applied to all-day occurrences (Google {@code date}, not {@code
     * dateTime}, fields), matching {@code CalDavCalendarSourceAdapter#ALL_DAY_ZONE}'s
     * convention. Inconsequential in practice: {@code allDay} events are never
     * creation-eligible per {@code RelayDiffPlanner}'s gate.
     */
    private static final ZoneId ALL_DAY_ZONE = ZoneId.of("Europe/Berlin");

    private final HttpClient httpClient;
    private final String googleCalendarId;
    private final String googleClientId;
    private final String googleClientSecret;
    private final String googleRefreshToken;
    private final Clock clock;
    private final Period recurringEventHorizon;
    private final GoogleCalendarReplicaStore googleCalendarReplicaStore;
    private final boolean deltaSyncEnabled;
    private final URI tokenEndpoint;
    private final URI eventsEndpoint;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private @Nullable String cachedAccessToken;
    private @Nullable Instant cachedAccessTokenExpiry;

    public GoogleCalendarSourceAdapter(
            HttpClient httpClient,
            String googleCalendarId,
            String googleClientId,
            String googleClientSecret,
            String googleRefreshToken,
            Clock clock,
            Period recurringEventHorizon,
            GoogleCalendarReplicaStore googleCalendarReplicaStore,
            boolean deltaSyncEnabled) {
        this(
                httpClient,
                googleCalendarId,
                googleClientId,
                googleClientSecret,
                googleRefreshToken,
                clock,
                recurringEventHorizon,
                googleCalendarReplicaStore,
                deltaSyncEnabled,
                URI.create(DEFAULT_TOKEN_ENDPOINT),
                URI.create(DEFAULT_EVENTS_ENDPOINT_TEMPLATE.formatted(
                        URLEncoder.encode(googleCalendarId, StandardCharsets.UTF_8))));
    }

    /**
     * Widened constructor accepting explicit token-exchange/{@code events.list} endpoints,
     * used only by tests to point this adapter at a local HTTP server instead of the real
     * Google API -- the public constructor above always delegates here with Google's real
     * endpoints.
     */
    GoogleCalendarSourceAdapter(
            HttpClient httpClient,
            String googleCalendarId,
            String googleClientId,
            String googleClientSecret,
            String googleRefreshToken,
            Clock clock,
            Period recurringEventHorizon,
            GoogleCalendarReplicaStore googleCalendarReplicaStore,
            boolean deltaSyncEnabled,
            URI tokenEndpoint,
            URI eventsEndpoint) {
        this.httpClient = httpClient;
        this.googleCalendarId = googleCalendarId;
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
        this.googleRefreshToken = googleRefreshToken;
        this.clock = clock;
        this.recurringEventHorizon = recurringEventHorizon;
        this.googleCalendarReplicaStore = googleCalendarReplicaStore;
        this.deltaSyncEnabled = deltaSyncEnabled;
        this.tokenEndpoint = tokenEndpoint;
        this.eventsEndpoint = eventsEndpoint;
    }

    @Override
    public List<SourceEvent> readEvents() {
        var accessToken = obtainAccessToken();
        var events = deltaSyncEnabled
                ? readEventsViaDeltaSyncPlusSupplementalFetch(accessToken)
                : readEventsViaFullFetch(accessToken);

        LOG.info("Read {} source event(s) from Google Calendar {}", events.size(), googleCalendarId);
        return List.copyOf(events);
    }

    // --- OAuth access-token exchange, in-memory cached ---

    private String obtainAccessToken() {
        var now = clock.instant();
        if (cachedAccessToken != null && cachedAccessTokenExpiry != null && now.isBefore(cachedAccessTokenExpiry)) {
            return cachedAccessToken;
        }

        var response = executeTokenExchange();
        if (response.statusCode() != HTTP_OK) {
            throw new GoogleCalendarSourceException("Google OAuth token exchange failed with status "
                    + response.statusCode() + " for Google Calendar " + googleCalendarId + ": " + response.body());
        }

        var root = parseJson(response.body());
        var accessToken = requireText(root, "access_token");
        var expiresInNode = root.get("expires_in");
        var expiresIn =
                (expiresInNode != null && !expiresInNode.isNull()) ? expiresInNode.asLong() : DEFAULT_ACCESS_TOKEN_EXPIRES_IN_SECONDS;

        cachedAccessToken = accessToken;
        cachedAccessTokenExpiry = now.plusSeconds(expiresIn).minusSeconds(ACCESS_TOKEN_EXPIRY_SAFETY_BUFFER_SECONDS);
        return accessToken;
    }

    private HttpResponse<String> executeTokenExchange() {
        var form = "client_id=" + urlEncode(googleClientId)
                + "&client_secret=" + urlEncode(googleClientSecret)
                + "&refresh_token=" + urlEncode(googleRefreshToken)
                + "&grant_type=refresh_token";
        var request = HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    // --- syncToken-based delta sync, plus horizon-bound supplemental fetch ---

    private List<SourceEvent> readEventsViaDeltaSyncPlusSupplementalFetch(String accessToken) {
        syncReplicaWithServer(accessToken);
        var replicaEvents = loadReplicaEvents();
        var supplementalEvents = fetchSupplementalWindow(accessToken);
        var merged = mergeById(replicaEvents, supplementalEvents);
        return mapToSourceEvents(merged);
    }

    private void syncReplicaWithServer(String accessToken) {
        var token = loadSyncToken();
        if (token == null) {
            performInitialSync(accessToken);
        } else {
            performIncrementalSync(accessToken, token);
        }
    }

    private void performInitialSync(String accessToken) {
        var now = ZonedDateTime.now(clock);
        EventsListResult result;
        try {
            result = fetchEventsList(accessToken, null, null, horizonEnd(now), true);
        } catch (SyncTokenInvalidatedException e) {
            throw new GoogleCalendarSourceException(
                    "Unexpected 410 Gone from Google events.list on a token-less initial sync for Google Calendar "
                            + googleCalendarId,
                    e);
        }
        requireNewSyncToken(result);

        var upserted = new ArrayList<CachedGoogleCalendarEvent>();
        for (var item : result.items()) {
            if (!isCancelledStatus(item)) {
                upserted.add(toCached(item));
            }
        }
        resetReplica(result.nextSyncToken(), upserted);
    }

    private void performIncrementalSync(String accessToken, String token) {
        EventsListResult result;
        try {
            result = fetchEventsList(accessToken, token, null, null, true);
        } catch (SyncTokenInvalidatedException e) {
            performInitialSync(accessToken);
            return;
        }
        requireNewSyncToken(result);

        var upserted = new ArrayList<CachedGoogleCalendarEvent>();
        var removedEventIds = new ArrayList<String>();
        for (var item : result.items()) {
            if (isCancelledStatus(item)) {
                removedEventIds.add(requireText(item, "id"));
            } else {
                upserted.add(toCached(item));
            }
        }
        applyReplicaDelta(result.nextSyncToken(), upserted, removedEventIds);
    }

    private void requireNewSyncToken(EventsListResult result) {
        if (result.nextSyncToken() == null) {
            throw new GoogleCalendarSourceException(
                    "Google events.list response for Google Calendar " + googleCalendarId
                            + " is missing nextSyncToken");
        }
    }

    private List<CachedGoogleCalendarEvent> fetchSupplementalWindow(String accessToken) {
        var now = ZonedDateTime.now(clock);
        EventsListResult result;
        try {
            result = fetchEventsList(accessToken, null, now.toInstant(), horizonEnd(now), false);
        } catch (SyncTokenInvalidatedException e) {
            throw new GoogleCalendarSourceException(
                    "Unexpected 410 Gone from Google events.list on the horizon-bound supplemental fetch for Google"
                            + " Calendar " + googleCalendarId,
                    e);
        }
        return result.items().stream().map(this::toCached).toList();
    }

    private List<CachedGoogleCalendarEvent> mergeById(
            List<CachedGoogleCalendarEvent> replicaEvents, List<CachedGoogleCalendarEvent> supplementalEvents) {
        var byId = new LinkedHashMap<String, CachedGoogleCalendarEvent>();
        for (var event : replicaEvents) {
            byId.put(event.eventId(), event);
        }
        for (var event : supplementalEvents) {
            byId.putIfAbsent(event.eventId(), event);
        }
        return List.copyOf(byId.values());
    }

    private Instant horizonEnd(ZonedDateTime now) {
        return now.plus(recurringEventHorizon).toInstant();
    }

    private @Nullable String loadSyncToken() {
        try {
            return googleCalendarReplicaStore.loadSyncToken();
        } catch (RuntimeException e) {
            throw new GoogleCalendarSourceException(
                    "Failed to load sync-token from Google calendar replica store for " + googleCalendarId, e);
        }
    }

    private List<CachedGoogleCalendarEvent> loadReplicaEvents() {
        try {
            return googleCalendarReplicaStore.loadAllEvents();
        } catch (RuntimeException e) {
            throw new GoogleCalendarSourceException(
                    "Failed to load cached events from Google calendar replica store for " + googleCalendarId, e);
        }
    }

    private void resetReplica(String newSyncToken, List<CachedGoogleCalendarEvent> events) {
        try {
            googleCalendarReplicaStore.resetTo(newSyncToken, events);
        } catch (RuntimeException e) {
            throw new GoogleCalendarSourceException(
                    "Failed to reset Google calendar replica store for " + googleCalendarId, e);
        }
    }

    private void applyReplicaDelta(
            String newSyncToken, List<CachedGoogleCalendarEvent> upserted, List<String> removedEventIds) {
        try {
            googleCalendarReplicaStore.applyDelta(newSyncToken, upserted, removedEventIds);
        } catch (RuntimeException e) {
            throw new GoogleCalendarSourceException(
                    "Failed to apply Google calendar replica delta for " + googleCalendarId, e);
        }
    }

    // --- Always-full fetch (deltaSyncEnabled == false) ---

    private List<SourceEvent> readEventsViaFullFetch(String accessToken) {
        var now = ZonedDateTime.now(clock);
        EventsListResult result;
        try {
            result = fetchEventsList(accessToken, null, null, horizonEnd(now), false);
        } catch (SyncTokenInvalidatedException e) {
            throw new GoogleCalendarSourceException(
                    "Unexpected 410 Gone from Google events.list on a token-less full fetch for Google Calendar "
                            + googleCalendarId,
                    e);
        }
        var cached = result.items().stream().map(this::toCached).toList();
        return mapToSourceEvents(cached);
    }

    // --- events.list HTTP exchange, with pagination ---

    private record EventsListResult(List<JsonNode> items, @Nullable String nextSyncToken) {}

    /**
     * Internal control-flow signal only, never thrown out of {@link #readEvents()}: raised
     * when Google responds {@code 410 Gone} to an {@code events.list(syncToken=...)}
     * request, its documented signal that the stored sync-token has been invalidated --
     * caught by {@link #performIncrementalSync} to trigger a forced full resync, per
     * {@code docs/features/google-calendar-integration.md}'s "Fehlerfälle — Ergänzungen".
     */
    private static final class SyncTokenInvalidatedException extends RuntimeException {}

    private EventsListResult fetchEventsList(
            String accessToken,
            @Nullable String syncToken,
            @Nullable Instant timeMin,
            @Nullable Instant timeMax,
            boolean showDeleted) {
        var items = new ArrayList<JsonNode>();
        String nextSyncToken = null;
        String pageToken = null;
        do {
            var response = executeEventsListRequest(accessToken, syncToken, timeMin, timeMax, showDeleted, pageToken);
            if (response.statusCode() == HTTP_GONE) {
                throw new SyncTokenInvalidatedException();
            }
            if (response.statusCode() != HTTP_OK) {
                throw new GoogleCalendarSourceException("Unexpected events.list response status "
                        + response.statusCode() + " for Google Calendar " + googleCalendarId + ": "
                        + response.body());
            }

            var root = parseJson(response.body());
            for (var item : root.path("items")) {
                items.add(item);
            }
            var nextSyncTokenNode = root.get("nextSyncToken");
            if (nextSyncTokenNode != null && !nextSyncTokenNode.isNull()) {
                nextSyncToken = nextSyncTokenNode.asText();
            }
            var nextPageTokenNode = root.get("nextPageToken");
            pageToken = (nextPageTokenNode != null && !nextPageTokenNode.isNull()) ? nextPageTokenNode.asText() : null;
        } while (pageToken != null);

        return new EventsListResult(List.copyOf(items), nextSyncToken);
    }

    private HttpResponse<String> executeEventsListRequest(
            String accessToken,
            @Nullable String syncToken,
            @Nullable Instant timeMin,
            @Nullable Instant timeMax,
            boolean showDeleted,
            @Nullable String pageToken) {
        var uri = new StringBuilder(eventsEndpoint.toString())
                .append("?singleEvents=true")
                .append("&showDeleted=")
                .append(showDeleted);
        if (syncToken != null) {
            uri.append("&syncToken=").append(urlEncode(syncToken));
        }
        if (timeMin != null) {
            uri.append("&timeMin=").append(urlEncode(timeMin.toString()));
        }
        if (timeMax != null) {
            uri.append("&timeMax=").append(urlEncode(timeMax.toString()));
        }
        if (pageToken != null) {
            uri.append("&pageToken=").append(urlEncode(pageToken));
        }

        var request = HttpRequest.newBuilder(URI.create(uri.toString()))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GoogleCalendarSourceException("Failed to reach Google Calendar API for " + googleCalendarId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleCalendarSourceException(
                    "Interrupted while reading from Google Calendar API for " + googleCalendarId, e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // --- JSON <-> CachedGoogleCalendarEvent/SourceEvent mapping ---

    private CachedGoogleCalendarEvent toCached(JsonNode item) {
        var id = requireText(item, "id");
        var etagNode = item.get("etag");
        var etag = (etagNode != null && !etagNode.isNull()) ? etagNode.asText() : "";
        return new CachedGoogleCalendarEvent(id, etag, item.toString());
    }

    private boolean isCancelledStatus(JsonNode item) {
        var statusNode = item.get("status");
        return statusNode != null && "cancelled".equals(statusNode.asText());
    }

    private List<SourceEvent> mapToSourceEvents(List<CachedGoogleCalendarEvent> cachedEvents) {
        var result = new ArrayList<SourceEvent>();
        for (var cached : cachedEvents) {
            try {
                result.add(toSourceEvent(cached));
            } catch (RuntimeException e) {
                LOG.warn(
                        "Skipping unparseable Google Calendar event id={} for {}: {}",
                        cached.eventId(),
                        googleCalendarId,
                        e.getMessage());
            }
        }
        return result;
    }

    private SourceEvent toSourceEvent(CachedGoogleCalendarEvent cached) {
        var item = parseJson(cached.rawEventJson());
        var id = requireText(item, "id");

        var recurringEventIdNode = item.get("recurringEventId");
        var recurring = recurringEventIdNode != null && !recurringEventIdNode.isNull();
        var sourceUid = recurring ? recurringEventIdNode.asText() + "#" + originalStartKey(id, item) : id;

        var startNode = requireNode(item, "start");
        var endNode = requireNode(item, "end");
        var allDay = isAllDay(startNode);
        var start = toZonedDateTime(startNode, allDay);
        var end = toZonedDateTime(endNode, allDay);

        var transparencyNode = item.get("transparency");
        var busy = transparencyNode == null || !"transparent".equals(transparencyNode.asText());
        var cancelled = isCancelledStatus(item);

        return new SourceEvent(sourceUid, start, end, allDay, busy, recurring, cancelled);
    }

    /**
     * Derives the composite {@code sourceUid} suffix for one expanded recurring occurrence
     * from its {@code originalStartTime} field, per
     * {@code docs/features/google-calendar-integration.md}'s Design-Entscheidung 3.
     *
     * <p>Judgment call (Open Question in the spec: exact {@code originalStartTime} format
     * for an all-day recurring occurrence is not verified against the real API): rather
     * than using Google's raw {@code date}/{@code dateTime} string verbatim, this converts
     * {@code originalStartTime} through the same {@link #toZonedDateTime} logic used for
     * {@code start}/{@code end} and takes its {@link ZonedDateTime#toInstant()}, exactly
     * mirroring {@code CalDavCalendarSourceAdapter}'s own {@code
     * uid + "#" + occurrenceStart.toInstant()} composite key format. This keeps the
     * composite key representation identical across both source types regardless of which
     * of Google's two original-start-time shapes is actually returned.
     */
    private String originalStartKey(String uid, JsonNode item) {
        var originalStartTime = item.get("originalStartTime");
        if (originalStartTime == null || originalStartTime.isNull()) {
            throw new GoogleCalendarSourceException(
                    "Google event " + uid + " has recurringEventId but no originalStartTime");
        }
        var allDay = isAllDay(originalStartTime);
        return toZonedDateTime(originalStartTime, allDay).toInstant().toString();
    }

    private boolean isAllDay(JsonNode dateNode) {
        return dateNode.has("date") && !dateNode.has("dateTime");
    }

    private ZonedDateTime toZonedDateTime(JsonNode dateNode, boolean allDay) {
        if (allDay) {
            LocalDate date;
            try {
                date = LocalDate.parse(requireText(dateNode, "date"));
            } catch (DateTimeParseException e) {
                throw new GoogleCalendarSourceException("Malformed all-day 'date' in Google Calendar event", e);
            }
            return date.atStartOfDay(ALL_DAY_ZONE);
        }

        var dateTime = requireText(dateNode, "dateTime");
        OffsetDateTime offsetDateTime;
        try {
            offsetDateTime = OffsetDateTime.parse(dateTime);
        } catch (DateTimeParseException e) {
            throw new GoogleCalendarSourceException("Malformed 'dateTime' in Google Calendar event: " + dateTime, e);
        }

        var timeZoneNode = dateNode.get("timeZone");
        if (timeZoneNode != null && !timeZoneNode.isNull() && !timeZoneNode.asText().isBlank()) {
            return offsetDateTime.atZoneSameInstant(ZoneId.of(timeZoneNode.asText()));
        }
        return offsetDateTime.toZonedDateTime();
    }

    private JsonNode requireNode(JsonNode item, String fieldName) {
        var node = item.get(fieldName);
        if (node == null || node.isNull()) {
            throw new GoogleCalendarSourceException(
                    "Google Calendar event is missing required field '" + fieldName + "'");
        }
        return node;
    }

    private String requireText(JsonNode node, String fieldName) {
        var field = node.get(fieldName);
        if (field == null || field.isNull() || field.asText().isBlank()) {
            throw new GoogleCalendarSourceException(
                    "Google Calendar API response is missing required field '" + fieldName + "'");
        }
        return field.asText();
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new GoogleCalendarSourceException("Malformed JSON response from Google Calendar API", e);
        }
    }
}
