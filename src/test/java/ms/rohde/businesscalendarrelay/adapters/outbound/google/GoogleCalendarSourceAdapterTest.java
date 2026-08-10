package ms.rohde.businesscalendarrelay.adapters.outbound.google;

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

import com.sun.net.httpserver.HttpExchange;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import ms.rohde.businesscalendarrelay.core.domain.SourceEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedGoogleCalendarEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.GoogleCalendarReplicaStore;
import ms.rohde.businesscalendarrelay.ports.outbound.GoogleCalendarReplicaStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link GoogleCalendarSourceAdapter} against a real local HTTP server standing
 * in for both the OAuth token endpoint and {@code events.list}, mirroring
 * {@code CalDavCalendarSourceAdapterTest}'s approach: the request shape (query parameters,
 * headers, body) and the JSON response parsing are protocol-level details a mock cannot
 * meaningfully stand in for.
 */
class GoogleCalendarSourceAdapterTest {

    private static final String CALENDAR_ID = "someone@gmail.com";
    private static final String CLIENT_ID = "client-id";
    private static final String CLIENT_SECRET = "client-secret";
    private static final String REFRESH_TOKEN = "refresh-token";
    private static final String TOKEN_PATH = "/token";
    private static final String EVENTS_PATH = "/events";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static final Clock DEFAULT_CLOCK = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Period DEFAULT_HORIZON = Period.ofDays(20);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record Endpoints(URI tokenEndpoint, URI eventsEndpoint) {}

    /**
     * Starts a local server with a token-endpoint responder and an events-endpoint
     * responder, each free to inspect the incoming request (path/query/body) and return
     * whatever status/body the test scenario needs.
     */
    private Endpoints startServer(
            Function<String, StubResponse> tokenResponder, Function<String, StubResponse> eventsResponder)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(TOKEN_PATH, exchange -> respond(exchange, tokenResponder));
        server.createContext(EVENTS_PATH, exchange -> respond(exchange, eventsResponder));
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new Endpoints(URI.create(base + TOKEN_PATH), URI.create(base + EVENTS_PATH));
    }

    private void respond(HttpExchange exchange, Function<String, StubResponse> responder) throws IOException {
        var requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var target = exchange.getRequestURI().toString();
        var stub = responder.apply(exchange.getRequestMethod().equals("GET") ? target : requestBody);
        var payload = stub.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(stub.statusCode(), payload.length);
        try (OutputStream responseStream = exchange.getResponseBody()) {
            responseStream.write(payload);
        }
    }

    private record StubResponse(int statusCode, String body) {}

    private static Function<String, StubResponse> fixedTokenResponse() {
        return requestBody -> new StubResponse(200, "{\"access_token\":\"token-1\",\"expires_in\":3600}");
    }

    private static GoogleCalendarReplicaStore neverTouchedReplicaStore() {
        return mock(GoogleCalendarReplicaStore.class, invocation -> {
            throw new AssertionError("GoogleCalendarReplicaStore must not be touched when delta sync is disabled, but "
                    + invocation.getMethod().getName() + " was called");
        });
    }

    private GoogleCalendarSourceAdapter adapter(Endpoints endpoints, GoogleCalendarReplicaStore replicaStore, boolean deltaSyncEnabled) {
        return adapter(endpoints, replicaStore, deltaSyncEnabled, DEFAULT_CLOCK, DEFAULT_HORIZON);
    }

    private GoogleCalendarSourceAdapter adapter(
            Endpoints endpoints,
            GoogleCalendarReplicaStore replicaStore,
            boolean deltaSyncEnabled,
            Clock clock,
            Period horizon) {
        return new GoogleCalendarSourceAdapter(
                HttpClient.newHttpClient(),
                CALENDAR_ID,
                CLIENT_ID,
                CLIENT_SECRET,
                REFRESH_TOKEN,
                clock,
                horizon,
                replicaStore,
                deltaSyncEnabled,
                endpoints.tokenEndpoint(),
                endpoints.eventsEndpoint());
    }

    private static String simpleEventJson(String id, String startLocal, String endLocal) {
        return """
                {"id":"%s","status":"confirmed","start":{"dateTime":"%s","timeZone":"Europe/Berlin"},\
                "end":{"dateTime":"%s","timeZone":"Europe/Berlin"}}""".formatted(id, startLocal, endLocal);
    }

    private static String eventsListBody(String nextSyncToken, List<String> itemsJson) {
        var items = String.join(",", itemsJson);
        return "{\"kind\":\"calendar#events\",\"items\":[" + items + "],\"nextSyncToken\":\"" + nextSyncToken + "\"}";
    }

    // --- OAuth token exchange ---

    @Test
    void readEvents_givenNoCachedToken_thenIssuesTokenExchangeWithClientCredentialsAndRefreshToken() throws IOException {
        var capturedBody = new AtomicReference<String>();
        var endpoints = startServer(
                requestBody -> {
                    capturedBody.set(requestBody);
                    return new StubResponse(200, "{\"access_token\":\"token-1\",\"expires_in\":3600}");
                },
                requestBody -> new StubResponse(200, eventsListBody("sync-1", List.of())));

        adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        assertThat(capturedBody.get())
                .contains("client_id=" + CLIENT_ID)
                .contains("client_secret=" + CLIENT_SECRET)
                .contains("refresh_token=" + REFRESH_TOKEN)
                .contains("grant_type=refresh_token");
    }

    @Test
    void readEvents_givenEventsListCall_thenSendsBearerAuthorizationHeaderWithExchangedToken() throws IOException {
        var capturedAuthHeader = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(TOKEN_PATH, exchange -> {
            exchange.getRequestBody().readAllBytes();
            var payload = "{\"access_token\":\"secret-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext(EVENTS_PATH, exchange -> {
            capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var payload = eventsListBody("sync-1", List.of()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var endpoints = new Endpoints(URI.create(base + TOKEN_PATH), URI.create(base + EVENTS_PATH));

        adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        assertThat(capturedAuthHeader.get()).isEqualTo("Bearer secret-token");
    }

    @Test
    void readEvents_givenTwoCallsWithinTokenExpiry_thenTokenEndpointCalledOnlyOnce() throws IOException {
        var tokenCallCount = new AtomicInteger();
        var endpoints = startServer(
                requestBody -> {
                    tokenCallCount.incrementAndGet();
                    return new StubResponse(200, "{\"access_token\":\"token-1\",\"expires_in\":3600}");
                },
                requestBody -> new StubResponse(200, eventsListBody("sync-1", List.of())));

        var adapter = adapter(endpoints, neverTouchedReplicaStore(), false);
        adapter.readEvents();
        adapter.readEvents();

        assertThat(tokenCallCount.get()).isEqualTo(1);
    }

    @Test
    void readEvents_givenTokenExpiredSinceLastCall_thenTokenEndpointCalledAgain() throws IOException {
        var tokenCallCount = new AtomicInteger();
        var endpoints = startServer(
                requestBody -> {
                    tokenCallCount.incrementAndGet();
                    return new StubResponse(200, "{\"access_token\":\"token-1\",\"expires_in\":3600}");
                },
                requestBody -> new StubResponse(200, eventsListBody("sync-1", List.of())));

        var mutableClock = new AtomicReference<>(DEFAULT_CLOCK);
        var advancingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return mutableClock.get().getZone();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return mutableClock.get().instant();
            }
        };
        var adapter = adapter(endpoints, neverTouchedReplicaStore(), false, advancingClock, DEFAULT_HORIZON);

        adapter.readEvents();
        mutableClock.set(Clock.fixed(DEFAULT_CLOCK.instant().plusSeconds(3601), ZoneOffset.UTC));
        adapter.readEvents();

        assertThat(tokenCallCount.get()).isEqualTo(2);
    }

    @Test
    void readEvents_givenTokenExchangeFailsWithInvalidGrant_thenThrowsGoogleCalendarSourceException() throws IOException {
        var endpoints = startServer(
                requestBody -> new StubResponse(400, "{\"error\":\"invalid_grant\"}"),
                requestBody -> new StubResponse(200, eventsListBody("sync-1", List.of())));

        assertThatThrownBy(() -> adapter(endpoints, neverTouchedReplicaStore(), false).readEvents())
                .isInstanceOf(GoogleCalendarSourceException.class)
                .hasMessageContaining("400");
    }

    // --- Full fetch (deltaSyncEnabled == false) ---

    @Test
    void readEvents_givenDeltaSyncDisabled_thenIssuesEventsListWithShowDeletedFalseAndTimeMaxAndNoSyncToken()
            throws IOException {
        var capturedQuery = new AtomicReference<String>();
        var endpoints = startServer(
                fixedTokenResponse(),
                query -> {
                    capturedQuery.set(query);
                    return new StubResponse(200, eventsListBody("sync-1", List.of()));
                });

        adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        assertThat(capturedQuery.get())
                .contains("singleEvents=true")
                .contains("showDeleted=false")
                .contains("timeMax=")
                .doesNotContain("syncToken=")
                .doesNotContain("timeMin=");
    }

    @Test
    void readEvents_givenDeltaSyncDisabled_thenNeverTouchesReplicaStore() throws IOException {
        var endpoints = startServer(
                fixedTokenResponse(), query -> new StubResponse(200, eventsListBody("sync-1", List.of())));

        adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();
        // neverTouchedReplicaStore() throws AssertionError itself if touched -- reaching
        // here without one propagating is the assertion.
    }

    @Test
    void readEvents_givenSimpleEventWithTimeZone_thenMapsToSourceEvent() throws IOException {
        var endpoints = startServer(
                fixedTokenResponse(),
                query -> new StubResponse(
                        200,
                        eventsListBody(
                                "sync-1",
                                List.of(simpleEventJson(
                                        "event1", "2026-02-01T10:00:00+01:00", "2026-02-01T11:00:00+01:00")))));

        var events = adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        assertThat(events)
                .containsExactly(new SourceEvent(
                        "event1",
                        ZonedDateTime.of(2026, 2, 1, 10, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 2, 1, 11, 0, 0, 0, BERLIN),
                        false,
                        true,
                        false,
                        false));
    }

    @Test
    void readEvents_givenAllDayEvent_thenMapsToAllDaySourceEvent() throws IOException {
        var allDayEvent =
                "{\"id\":\"allday1\",\"status\":\"confirmed\",\"start\":{\"date\":\"2026-03-15\"},"
                        + "\"end\":{\"date\":\"2026-03-16\"}}";
        var endpoints = startServer(
                fixedTokenResponse(), query -> new StubResponse(200, eventsListBody("sync-1", List.of(allDayEvent))));

        var events = adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        assertThat(events)
                .containsExactly(new SourceEvent(
                        "allday1",
                        ZonedDateTime.of(2026, 3, 15, 0, 0, 0, 0, BERLIN),
                        ZonedDateTime.of(2026, 3, 16, 0, 0, 0, 0, BERLIN),
                        true,
                        true,
                        false,
                        false));
    }

    @Test
    void readEvents_givenTransparentEvent_thenMapsToBusyFalse() throws IOException {
        var transparentEvent =
                "{\"id\":\"transparent1\",\"status\":\"confirmed\",\"transparency\":\"transparent\","
                        + "\"start\":{\"dateTime\":\"2026-02-01T10:00:00+01:00\",\"timeZone\":\"Europe/Berlin\"},"
                        + "\"end\":{\"dateTime\":\"2026-02-01T11:00:00+01:00\",\"timeZone\":\"Europe/Berlin\"}}";
        var endpoints = startServer(
                fixedTokenResponse(),
                query -> new StubResponse(200, eventsListBody("sync-1", List.of(transparentEvent))));

        var events = adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        assertThat(events).singleElement().satisfies(event -> assertThat(event.busy()).isFalse());
    }

    @Test
    void readEvents_givenRecurringOccurrence_thenSourceUidIsRecurringEventIdHashOriginalStartInstant()
            throws IOException {
        var occurrence =
                "{\"id\":\"instance1\",\"status\":\"confirmed\",\"recurringEventId\":\"series-uid\","
                        + "\"originalStartTime\":{\"dateTime\":\"2026-02-09T10:00:00+01:00\",\"timeZone\":\"Europe/Berlin\"},"
                        + "\"start\":{\"dateTime\":\"2026-02-09T10:00:00+01:00\",\"timeZone\":\"Europe/Berlin\"},"
                        + "\"end\":{\"dateTime\":\"2026-02-09T11:00:00+01:00\",\"timeZone\":\"Europe/Berlin\"}}";
        var endpoints = startServer(
                fixedTokenResponse(), query -> new StubResponse(200, eventsListBody("sync-1", List.of(occurrence))));

        var events = adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        var expectedInstant = ZonedDateTime.of(2026, 2, 9, 10, 0, 0, 0, BERLIN).toInstant();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.sourceUid()).isEqualTo("series-uid#" + expectedInstant);
            assertThat(event.recurring()).isTrue();
        });
    }

    @Test
    void readEvents_givenMalformedEventMissingStart_thenSkipsItInsteadOfFailingWholeRead() throws IOException {
        var malformed = "{\"id\":\"broken\",\"status\":\"confirmed\"}";
        var valid = simpleEventJson("valid1", "2026-02-01T10:00:00+01:00", "2026-02-01T11:00:00+01:00");
        var endpoints = startServer(
                fixedTokenResponse(),
                query -> new StubResponse(200, eventsListBody("sync-1", List.of(malformed, valid))));

        var events = adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        assertThat(events).extracting(SourceEvent::sourceUid).containsExactly("valid1");
    }

    @Test
    void readEvents_givenUnexpectedEventsListStatus_thenThrowsGoogleCalendarSourceException() throws IOException {
        var endpoints = startServer(fixedTokenResponse(), query -> new StubResponse(500, "internal server error"));

        assertThatThrownBy(() -> adapter(endpoints, neverTouchedReplicaStore(), false).readEvents())
                .isInstanceOf(GoogleCalendarSourceException.class)
                .hasMessageContaining("500");
    }

    @Test
    void readEvents_givenForbiddenStatusOnEventsList_thenThrowsGoogleCalendarSourceExceptionFailingOnlyThisCycle()
            throws IOException {
        var endpoints = startServer(fixedTokenResponse(), query -> new StubResponse(403, "quota exceeded"));

        assertThatThrownBy(() -> adapter(endpoints, neverTouchedReplicaStore(), false).readEvents())
                .isInstanceOf(GoogleCalendarSourceException.class);
    }

    @Test
    void readEvents_givenTwoPagesOfResults_thenCombinesItemsFromBothPages() throws IOException {
        var pageOneEvent = simpleEventJson("event1", "2026-02-01T10:00:00+01:00", "2026-02-01T11:00:00+01:00");
        var pageTwoEvent = simpleEventJson("event2", "2026-02-02T10:00:00+01:00", "2026-02-02T11:00:00+01:00");
        var endpoints = startServer(fixedTokenResponse(), query -> {
            if (query.contains("pageToken=")) {
                return new StubResponse(200, eventsListBody("sync-final", List.of(pageTwoEvent)));
            }
            return new StubResponse(
                    200,
                    "{\"kind\":\"calendar#events\",\"items\":[" + pageOneEvent + "],\"nextPageToken\":\"page-2\"}");
        });

        var events = adapter(endpoints, neverTouchedReplicaStore(), false).readEvents();

        assertThat(events).extracting(SourceEvent::sourceUid).containsExactlyInAnyOrder("event1", "event2");
    }

    // --- Delta sync (syncToken-based, plus horizon-bound supplemental fetch) ---

    @Test
    void readEvents_givenDeltaSyncEnabledAndNoStoredToken_thenInitialSyncWithShowDeletedTrueAndTimeMaxAndResetsReplica()
            throws IOException {
        var capturedInitialQuery = new AtomicReference<String>();
        var event1 = simpleEventJson("event1", "2026-02-01T10:00:00+01:00", "2026-02-01T11:00:00+01:00");
        var endpoints = startServer(fixedTokenResponse(), query -> {
            if (!query.contains("timeMin=") && capturedInitialQuery.get() == null) {
                capturedInitialQuery.set(query);
                return new StubResponse(200, eventsListBody("sync-token-1", List.of(event1)));
            }
            // supplemental fetch (timeMin present)
            return new StubResponse(200, eventsListBody("ignored", List.of()));
        });

        var replicaStore = mock(GoogleCalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn(null);
        given(replicaStore.loadAllEvents())
                .willReturn(List.of(new CachedGoogleCalendarEvent("event1", "", event1)));

        var events = adapter(endpoints, replicaStore, true).readEvents();

        assertThat(capturedInitialQuery.get()).contains("showDeleted=true").contains("timeMax=").doesNotContain("syncToken=");
        then(replicaStore).should().resetTo(eq("sync-token-1"), anyList());
        then(replicaStore).should(never()).applyDelta(anyString(), anyList(), anyList());
        assertThat(events).extracting(SourceEvent::sourceUid).containsExactly("event1");
    }

    @Test
    void readEvents_givenDeltaSyncEnabledAndStoredToken_thenIncrementalSyncHasNoTimeBoundsAndAppliesDelta()
            throws IOException {
        var capturedIncrementalQuery = new AtomicReference<String>();
        var upsertedEvent = simpleEventJson("event2", "2026-03-01T09:00:00+01:00", "2026-03-01T09:30:00+01:00");
        var cancelledEvent = "{\"id\":\"removed-event\",\"status\":\"cancelled\"}";
        var endpoints = startServer(fixedTokenResponse(), query -> {
            if (query.contains("syncToken=stale-token-1")) {
                capturedIncrementalQuery.set(query);
                return new StubResponse(200, eventsListBody("sync-token-2", List.of(upsertedEvent, cancelledEvent)));
            }
            return new StubResponse(200, eventsListBody("ignored", List.of()));
        });

        var replicaStore = mock(GoogleCalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("stale-token-1");
        given(replicaStore.loadAllEvents())
                .willReturn(List.of(new CachedGoogleCalendarEvent("event2", "", upsertedEvent)));

        var events = adapter(endpoints, replicaStore, true).readEvents();

        assertThat(capturedIncrementalQuery.get()).doesNotContain("timeMin=").doesNotContain("timeMax=");
        then(replicaStore)
                .should()
                .applyDelta(eq("sync-token-2"), anyList(), eq(List.of("removed-event")));
        then(replicaStore).should(never()).resetTo(anyString(), anyList());
        assertThat(events).extracting(SourceEvent::sourceUid).containsExactly("event2");
    }

    @Test
    void readEvents_givenIncrementalSyncReceives410Gone_thenForcedFullResync() throws IOException {
        var freshEvent = simpleEventJson("event1", "2026-02-01T10:00:00+01:00", "2026-02-01T11:00:00+01:00");
        var endpoints = startServer(fixedTokenResponse(), query -> {
            if (query.contains("syncToken=stale-token")) {
                return new StubResponse(410, "Gone");
            }
            if (!query.contains("timeMin=")) {
                return new StubResponse(200, eventsListBody("sync-token-fresh", List.of(freshEvent)));
            }
            return new StubResponse(200, eventsListBody("ignored", List.of()));
        });

        var replicaStore = mock(GoogleCalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("stale-token");
        given(replicaStore.loadAllEvents())
                .willReturn(List.of(new CachedGoogleCalendarEvent("event1", "", freshEvent)));

        var events = adapter(endpoints, replicaStore, true).readEvents();

        then(replicaStore).should().resetTo(eq("sync-token-fresh"), anyList());
        assertThat(events).extracting(SourceEvent::sourceUid).containsExactly("event1");
    }

    @Test
    void readEvents_givenReplicaStoreLoadSyncTokenThrows_thenThrowsGoogleCalendarSourceException() throws IOException {
        var endpoints = startServer(fixedTokenResponse(), query -> new StubResponse(200, eventsListBody("sync-1", List.of())));
        var replicaStore = mock(GoogleCalendarReplicaStore.class);
        willThrow(new RuntimeException("db unavailable")).given(replicaStore).loadSyncToken();

        assertThatThrownBy(() -> adapter(endpoints, replicaStore, true).readEvents())
                .isInstanceOf(GoogleCalendarSourceException.class);
    }

    @Test
    void readEvents_givenReplicaStoreApplyDeltaThrows_thenThrowsGoogleCalendarSourceExceptionWrappingCause()
            throws IOException {
        var endpoints = startServer(
                fixedTokenResponse(), query -> new StubResponse(200, eventsListBody("sync-2", List.of())));
        var replicaStore = mock(GoogleCalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("token-1");
        var cause = new GoogleCalendarReplicaStoreException("persistence failed");
        willThrow(cause).given(replicaStore).applyDelta(anyString(), anyList(), anyList());

        assertThatThrownBy(() -> adapter(endpoints, replicaStore, true).readEvents())
                .isInstanceOf(GoogleCalendarSourceException.class)
                .hasCause(cause);
    }

    @Test
    void readEvents_givenSupplementalFetchFindsEventNotYetInReplica_thenUnionedIntoResult() throws IOException {
        var supplementalOnlyEvent = simpleEventJson("event3", "2026-02-05T10:00:00+01:00", "2026-02-05T11:00:00+01:00");
        var endpoints = startServer(fixedTokenResponse(), query -> {
            if (query.contains("timeMin=")) {
                return new StubResponse(200, eventsListBody("ignored", List.of(supplementalOnlyEvent)));
            }
            return new StubResponse(200, eventsListBody("sync-token-1", List.of()));
        });

        var replicaStore = mock(GoogleCalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("token-1");
        given(replicaStore.loadAllEvents()).willReturn(List.of());

        var events = adapter(endpoints, replicaStore, true).readEvents();

        assertThat(events).extracting(SourceEvent::sourceUid).containsExactly("event3");
    }

    @Test
    void readEvents_givenSameEventInReplicaAndSupplementalWindow_thenDeduplicatedByEventId() throws IOException {
        var event1 = simpleEventJson("event1", "2026-02-01T10:00:00+01:00", "2026-02-01T11:00:00+01:00");
        var endpoints = startServer(fixedTokenResponse(), query -> {
            if (query.contains("timeMin=")) {
                return new StubResponse(200, eventsListBody("ignored", List.of(event1)));
            }
            return new StubResponse(200, eventsListBody("sync-token-1", List.of()));
        });

        var replicaStore = mock(GoogleCalendarReplicaStore.class);
        given(replicaStore.loadSyncToken()).willReturn("token-1");
        given(replicaStore.loadAllEvents()).willReturn(List.of(new CachedGoogleCalendarEvent("event1", "", event1)));

        var events = adapter(endpoints, replicaStore, true).readEvents();

        assertThat(events).hasSize(1);
    }
}
