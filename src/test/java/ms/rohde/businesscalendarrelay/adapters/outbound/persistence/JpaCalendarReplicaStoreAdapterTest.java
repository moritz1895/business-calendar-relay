package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.util.List;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedCalendarResource;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarReplicaStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;

/**
 * Verifies {@link JpaCalendarReplicaStoreAdapter} against a real (embedded, in-memory for
 * the test) H2 database via {@link DataJpaTest}: per-calendar scoping, initial-sync
 * {@code resetTo} semantics, incremental {@code applyDelta} upsert/remove/advance-token
 * semantics, and unwrapped vs. wrapped exception propagation, mirroring
 * {@code JpaPendingCreationQueueAdapterTest}.
 *
 * <p>Boots from the empty {@link TestConfig} for the same reason as
 * {@code JpaPendingCreationQueueAdapterTest}: avoids {@code @ArchComponentScan} eagerly
 * trying to instantiate every {@code @ArchComponent}-annotated class as a Spring bean.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaCalendarReplicaStoreAdapterTest.TestConfig.class)
class JpaCalendarReplicaStoreAdapterTest {

    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {}

    private static final String CALENDAR_ID = "personal-nextcloud";
    private static final String OTHER_CALENDAR_ID = "work-nextcloud";

    @Autowired
    private CalendarReplicaResourceJpaRepository resourceRepository;

    @Autowired
    private CalendarSyncTokenJpaRepository tokenRepository;

    @Autowired
    private TestEntityManager entityManager;

    private JpaCalendarReplicaStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JpaCalendarReplicaStoreAdapter(resourceRepository, tokenRepository, CALENDAR_ID);
    }

    private static CachedCalendarResource resource(String href, String etag, String rawCalendarData) {
        return new CachedCalendarResource(href, etag, rawCalendarData);
    }

    @Test
    void loadSyncToken_givenNeverSynced_thenReturnsNull() {
        assertThat(adapter.loadSyncToken()).isNull();
    }

    @Test
    void loadSyncToken_givenTokenPersistedForAnotherCalendar_thenStillReturnsNull() {
        entityManager.persistAndFlush(new CalendarSyncTokenEntity(OTHER_CALENDAR_ID, "other-calendar-token"));

        assertThat(adapter.loadSyncToken()).isNull();
    }

    @Test
    void loadAllResources_givenRowsForAnotherCalendar_thenReturnsOnlyRowsForConstructedCalendarId() {
        entityManager.persistAndFlush(new CalendarReplicaResourceEntity(
                CALENDAR_ID, "/cal/event1.ics", "etag-1", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"));
        entityManager.persistAndFlush(new CalendarReplicaResourceEntity(
                OTHER_CALENDAR_ID, "/cal/event2.ics", "etag-2", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"));

        var result = adapter.loadAllResources();

        assertThat(result).extracting(CachedCalendarResource::href).containsExactly("/cal/event1.ics");
    }

    @Test
    void resetTo_givenInitialSyncResponse_thenPersistsAllResourcesAndToken() {
        adapter.resetTo(
                "sync-token-1",
                List.of(
                        resource("/cal/event1.ics", "etag-1", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"),
                        resource("/cal/event2.ics", "etag-2", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")));

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-1");
        assertThat(adapter.loadAllResources())
                .extracting(CachedCalendarResource::href)
                .containsExactlyInAnyOrder("/cal/event1.ics", "/cal/event2.ics");
    }

    @Test
    void resetTo_givenExistingReplicaForCalendar_thenReplacesEntireSetAndDoesNotTouchOtherCalendars() {
        adapter.resetTo("sync-token-1", List.of(resource("/cal/stale.ics", "etag-stale", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")));
        var otherAdapter = new JpaCalendarReplicaStoreAdapter(resourceRepository, tokenRepository, OTHER_CALENDAR_ID);
        otherAdapter.resetTo(
                "other-token", List.of(resource("/cal/untouched.ics", "etag-untouched", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")));

        adapter.resetTo("sync-token-2", List.of(resource("/cal/fresh.ics", "etag-fresh", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")));

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-2");
        assertThat(adapter.loadAllResources()).extracting(CachedCalendarResource::href).containsExactly("/cal/fresh.ics");
        assertThat(otherAdapter.loadSyncToken()).isEqualTo("other-token");
        assertThat(otherAdapter.loadAllResources())
                .extracting(CachedCalendarResource::href)
                .containsExactly("/cal/untouched.ics");
    }

    @Test
    void applyDelta_givenUpsertsAndRemovals_thenUpdatesReplicaAndAdvancesToken() {
        adapter.resetTo(
                "sync-token-1",
                List.of(
                        resource("/cal/unchanged.ics", "etag-unchanged", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"),
                        resource("/cal/to-be-removed.ics", "etag-old", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"),
                        resource("/cal/to-be-changed.ics", "etag-old", "BEGIN:VCALENDAR\r\nOLD\r\nEND:VCALENDAR\r\n")));

        adapter.applyDelta(
                "sync-token-2",
                List.of(
                        resource("/cal/to-be-changed.ics", "etag-new", "BEGIN:VCALENDAR\r\nNEW\r\nEND:VCALENDAR\r\n"),
                        resource("/cal/newly-added.ics", "etag-added", "BEGIN:VCALENDAR\r\nADDED\r\nEND:VCALENDAR\r\n")),
                List.of("/cal/to-be-removed.ics"));

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-2");
        var resources = adapter.loadAllResources();
        assertThat(resources)
                .extracting(CachedCalendarResource::href)
                .containsExactlyInAnyOrder("/cal/unchanged.ics", "/cal/to-be-changed.ics", "/cal/newly-added.ics");
        assertThat(resources)
                .filteredOn(resource -> resource.href().equals("/cal/to-be-changed.ics"))
                .singleElement()
                .satisfies(resource -> {
                    assertThat(resource.etag()).isEqualTo("etag-new");
                    assertThat(resource.rawCalendarData()).contains("NEW");
                });
    }

    @Test
    void applyDelta_givenNoResourcesAndNoRemovals_thenStillAdvancesToken() {
        adapter.resetTo("sync-token-1", List.of(resource("/cal/event1.ics", "etag-1", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")));

        adapter.applyDelta("sync-token-2", List.of(), List.of());

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-2");
        assertThat(adapter.loadAllResources()).extracting(CachedCalendarResource::href).containsExactly("/cal/event1.ics");
    }

    @Test
    void applyDelta_givenRepeatedIdenticalCall_thenEndsInSameIdempotentState() {
        adapter.resetTo("sync-token-1", List.of(resource("/cal/event1.ics", "etag-1", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")));
        var upserted = List.of(resource("/cal/event2.ics", "etag-2", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"));

        adapter.applyDelta("sync-token-2", upserted, List.of());
        adapter.applyDelta("sync-token-2", upserted, List.of());

        assertThat(adapter.loadSyncToken()).isEqualTo("sync-token-2");
        assertThat(adapter.loadAllResources())
                .extracting(CachedCalendarResource::href)
                .containsExactlyInAnyOrder("/cal/event1.ics", "/cal/event2.ics");
    }

    @Test
    void loadSyncToken_givenRepositoryThrows_thenPropagatesUnwrapped() {
        var mockTokenRepository = mock(CalendarSyncTokenJpaRepository.class);
        var mockAdapter = new JpaCalendarReplicaStoreAdapter(resourceRepository, mockTokenRepository, CALENDAR_ID);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockTokenRepository).findBySourceCalendarId(CALENDAR_ID);

        assertThatThrownBy(mockAdapter::loadSyncToken).isSameAs(cause);
    }

    @Test
    void loadAllResources_givenRepositoryThrows_thenPropagatesUnwrapped() {
        var mockResourceRepository = mock(CalendarReplicaResourceJpaRepository.class);
        var mockAdapter = new JpaCalendarReplicaStoreAdapter(mockResourceRepository, tokenRepository, CALENDAR_ID);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockResourceRepository).findAllBySourceCalendarId(CALENDAR_ID);

        assertThatThrownBy(mockAdapter::loadAllResources).isSameAs(cause);
    }

    @Test
    void applyDelta_givenRepositoryThrows_thenThrowsCalendarReplicaStoreExceptionWrappingCause() {
        var mockResourceRepository = mock(CalendarReplicaResourceJpaRepository.class);
        var mockAdapter = new JpaCalendarReplicaStoreAdapter(mockResourceRepository, tokenRepository, CALENDAR_ID);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockResourceRepository).saveAll(any());

        assertThatThrownBy(() -> mockAdapter.applyDelta(
                        "sync-token-2",
                        List.of(resource("/cal/event1.ics", "etag-1", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")),
                        List.of()))
                .isInstanceOf(CalendarReplicaStoreException.class)
                .hasCause(cause);
    }

    @Test
    void resetTo_givenRepositoryThrows_thenThrowsCalendarReplicaStoreExceptionWrappingCause() {
        var mockResourceRepository = mock(CalendarReplicaResourceJpaRepository.class);
        var mockAdapter = new JpaCalendarReplicaStoreAdapter(mockResourceRepository, tokenRepository, CALENDAR_ID);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockResourceRepository).deleteBySourceCalendarId(CALENDAR_ID);

        assertThatThrownBy(() -> mockAdapter.resetTo(
                        "sync-token-1",
                        List.of(resource("/cal/event1.ics", "etag-1", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"))))
                .isInstanceOf(CalendarReplicaStoreException.class)
                .hasCause(cause);
    }
}
