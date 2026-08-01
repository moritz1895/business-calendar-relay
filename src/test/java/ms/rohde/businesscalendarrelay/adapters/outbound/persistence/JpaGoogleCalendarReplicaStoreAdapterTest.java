package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.util.List;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedGoogleCalendarEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.GoogleCalendarReplicaStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Verifies {@link JpaGoogleCalendarReplicaStoreAdapter} against a real (embedded, in-memory
 * for the test) H2 database via {@link DataJpaTest}: per-calendar scoping, initial-sync
 * {@code resetTo} semantics, incremental {@code applyDelta} upsert/remove/advance-token
 * semantics, and unwrapped vs. wrapped exception propagation, mirroring
 * {@code JpaCalendarReplicaStoreAdapterTest}.
 *
 * <p>Boots from the empty {@link TestConfig} for the same reason as
 * {@code JpaCalendarReplicaStoreAdapterTest}: avoids {@code @ArchComponentScan} eagerly
 * trying to instantiate every {@code @ArchComponent}-annotated class as a Spring bean.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaGoogleCalendarReplicaStoreAdapterTest.TestConfig.class)
class JpaGoogleCalendarReplicaStoreAdapterTest {

    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {}

    private static final String CALENDAR_ID = "personal-google";
    private static final String OTHER_CALENDAR_ID = "work-google";

    @Autowired
    private GoogleCalendarReplicaResourceJpaRepository resourceRepository;

    @Autowired
    private GoogleCalendarSyncTokenJpaRepository tokenRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JpaGoogleCalendarReplicaStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JpaGoogleCalendarReplicaStoreAdapter(
                resourceRepository, tokenRepository, CALENDAR_ID, transactionManager);
    }

    private static CachedGoogleCalendarEvent event(String eventId, String etag, String rawEventJson) {
        return new CachedGoogleCalendarEvent(eventId, etag, rawEventJson);
    }

    @Test
    void loadSyncToken_givenNeverSynced_thenReturnsNull() {
        assertThat(adapter.loadSyncToken()).isNull();
    }

    @Test
    void loadSyncToken_givenTokenPersistedForAnotherCalendar_thenStillReturnsNull() {
        entityManager.persistAndFlush(new GoogleCalendarSyncTokenEntity(OTHER_CALENDAR_ID, "other-calendar-token"));

        assertThat(adapter.loadSyncToken()).isNull();
    }

    @Test
    void loadAllEvents_givenRowsForAnotherCalendar_thenReturnsOnlyRowsForConstructedCalendarId() {
        entityManager.persistAndFlush(
                new GoogleCalendarReplicaResourceEntity(CALENDAR_ID, "event1", "etag-1", "{\"id\":\"event1\"}"));
        entityManager.persistAndFlush(new GoogleCalendarReplicaResourceEntity(
                OTHER_CALENDAR_ID, "event2", "etag-2", "{\"id\":\"event2\"}"));

        var result = adapter.loadAllEvents();

        assertThat(result).extracting(CachedGoogleCalendarEvent::eventId).containsExactly("event1");
    }

    @Test
    void resetTo_givenInitialSyncResponse_thenPersistsAllEventsAndToken() {
        adapter.resetTo(
                "sync-token-1",
                List.of(
                        event("event1", "etag-1", "{\"id\":\"event1\"}"),
                        event("event2", "etag-2", "{\"id\":\"event2\"}")));

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-1");
        assertThat(adapter.loadAllEvents())
                .extracting(CachedGoogleCalendarEvent::eventId)
                .containsExactlyInAnyOrder("event1", "event2");
    }

    @Test
    void resetTo_givenExistingReplicaForCalendar_thenReplacesEntireSetAndDoesNotTouchOtherCalendars() {
        adapter.resetTo("sync-token-1", List.of(event("stale", "etag-stale", "{\"id\":\"stale\"}")));
        var otherAdapter = new JpaGoogleCalendarReplicaStoreAdapter(
                resourceRepository, tokenRepository, OTHER_CALENDAR_ID, transactionManager);
        otherAdapter.resetTo("other-token", List.of(event("untouched", "etag-untouched", "{\"id\":\"untouched\"}")));

        adapter.resetTo("sync-token-2", List.of(event("fresh", "etag-fresh", "{\"id\":\"fresh\"}")));

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-2");
        assertThat(adapter.loadAllEvents()).extracting(CachedGoogleCalendarEvent::eventId).containsExactly("fresh");
        assertThat(otherAdapter.loadSyncToken()).isEqualTo("other-token");
        assertThat(otherAdapter.loadAllEvents())
                .extracting(CachedGoogleCalendarEvent::eventId)
                .containsExactly("untouched");
    }

    @Test
    void applyDelta_givenUpsertsAndRemovals_thenUpdatesReplicaAndAdvancesToken() {
        adapter.resetTo(
                "sync-token-1",
                List.of(
                        event("unchanged", "etag-unchanged", "{\"id\":\"unchanged\"}"),
                        event("to-be-removed", "etag-old", "{\"id\":\"to-be-removed\"}"),
                        event("to-be-changed", "etag-old", "{\"id\":\"to-be-changed\",\"summary\":\"old\"}")));

        adapter.applyDelta(
                "sync-token-2",
                List.of(
                        event("to-be-changed", "etag-new", "{\"id\":\"to-be-changed\",\"summary\":\"new\"}"),
                        event("newly-added", "etag-added", "{\"id\":\"newly-added\"}")),
                List.of("to-be-removed"));

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-2");
        var events = adapter.loadAllEvents();
        assertThat(events)
                .extracting(CachedGoogleCalendarEvent::eventId)
                .containsExactlyInAnyOrder("unchanged", "to-be-changed", "newly-added");
        assertThat(events)
                .filteredOn(event -> event.eventId().equals("to-be-changed"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.etag()).isEqualTo("etag-new");
                    assertThat(event.rawEventJson()).contains("new");
                });
    }

    @Test
    void applyDelta_givenNoEventsAndNoRemovals_thenStillAdvancesToken() {
        adapter.resetTo("sync-token-1", List.of(event("event1", "etag-1", "{\"id\":\"event1\"}")));

        adapter.applyDelta("sync-token-2", List.of(), List.of());

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-2");
        assertThat(adapter.loadAllEvents()).extracting(CachedGoogleCalendarEvent::eventId).containsExactly("event1");
    }

    @Test
    void applyDelta_givenRepeatedIdenticalCall_thenEndsInSameIdempotentState() {
        adapter.resetTo("sync-token-1", List.of(event("event1", "etag-1", "{\"id\":\"event1\"}")));
        var upserted = List.of(event("event2", "etag-2", "{\"id\":\"event2\"}"));

        adapter.applyDelta("sync-token-2", upserted, List.of());
        adapter.applyDelta("sync-token-2", upserted, List.of());

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-2");
        assertThat(adapter.loadAllEvents())
                .extracting(CachedGoogleCalendarEvent::eventId)
                .containsExactlyInAnyOrder("event1", "event2");
    }

    @Test
    void loadSyncToken_givenRepositoryThrows_thenPropagatesUnwrapped() {
        var mockTokenRepository = mock(GoogleCalendarSyncTokenJpaRepository.class);
        var mockAdapter = new JpaGoogleCalendarReplicaStoreAdapter(
                resourceRepository, mockTokenRepository, CALENDAR_ID, transactionManager);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockTokenRepository).findBySourceCalendarId(CALENDAR_ID);

        assertThatThrownBy(mockAdapter::loadSyncToken).isSameAs(cause);
    }

    @Test
    void loadAllEvents_givenRepositoryThrows_thenPropagatesUnwrapped() {
        var mockResourceRepository = mock(GoogleCalendarReplicaResourceJpaRepository.class);
        var mockAdapter = new JpaGoogleCalendarReplicaStoreAdapter(
                mockResourceRepository, tokenRepository, CALENDAR_ID, transactionManager);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockResourceRepository).findAllBySourceCalendarId(CALENDAR_ID);

        assertThatThrownBy(mockAdapter::loadAllEvents).isSameAs(cause);
    }

    @Test
    void applyDelta_givenRepositoryThrows_thenThrowsGoogleCalendarReplicaStoreExceptionWrappingCause() {
        var mockResourceRepository = mock(GoogleCalendarReplicaResourceJpaRepository.class);
        var mockAdapter = new JpaGoogleCalendarReplicaStoreAdapter(
                mockResourceRepository, tokenRepository, CALENDAR_ID, transactionManager);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockResourceRepository).saveAll(any());

        assertThatThrownBy(() -> mockAdapter.applyDelta(
                        "sync-token-2", List.of(event("event1", "etag-1", "{\"id\":\"event1\"}")), List.of()))
                .isInstanceOf(GoogleCalendarReplicaStoreException.class)
                .hasCause(cause);
    }

    @Test
    void resetTo_givenRepositoryThrows_thenThrowsGoogleCalendarReplicaStoreExceptionWrappingCause() {
        var mockResourceRepository = mock(GoogleCalendarReplicaResourceJpaRepository.class);
        var mockAdapter = new JpaGoogleCalendarReplicaStoreAdapter(
                mockResourceRepository, tokenRepository, CALENDAR_ID, transactionManager);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockResourceRepository).deleteBySourceCalendarId(CALENDAR_ID);

        assertThatThrownBy(() -> mockAdapter.resetTo(
                        "sync-token-1", List.of(event("event1", "etag-1", "{\"id\":\"event1\"}"))))
                .isInstanceOf(GoogleCalendarReplicaStoreException.class)
                .hasCause(cause);
    }
}
