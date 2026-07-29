package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import ms.rohde.businesscalendarrelay.core.domain.RelayAction;
import ms.rohde.businesscalendarrelay.ports.outbound.PendingCreationQueueException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;

/**
 * Verifies {@link JpaPendingCreationQueueAdapter} against a real (embedded, in-memory for
 * the test) H2 database via {@link DataJpaTest}: per-calendar scoping, insert-only
 * {@code saveAll}, ascending-by-{@code start} ordering coming from the query itself, and
 * idempotent {@code remove}. Also pins {@code ZonedDateTime} round-tripping including the
 * named zone id, mirroring {@code JpaStateStoreAdapterTest}.
 *
 * <p>Boots from the empty {@link TestConfig} for the same reason as
 * {@code JpaStateStoreAdapterTest}: avoids {@code @ArchComponentScan} eagerly trying to
 * instantiate every {@code @ArchComponent}-annotated class as a Spring bean.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaPendingCreationQueueAdapterTest.TestConfig.class)
class JpaPendingCreationQueueAdapterTest {

    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {}

    private static final String CALENDAR_ID = "personal-nextcloud";
    private static final String OTHER_CALENDAR_ID = "work-nextcloud";
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Autowired
    private PendingCreationJpaRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private JpaPendingCreationQueueAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JpaPendingCreationQueueAdapter(repository, CALENDAR_ID);
    }

    private static ZonedDateTime start() {
        return ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE);
    }

    private static ZonedDateTime end() {
        return ZonedDateTime.of(2026, 8, 1, 11, 0, 0, 0, ZONE);
    }

    private static RelayAction.Create createAction(String sourceUid, ZonedDateTime start, ZonedDateTime end) {
        return new RelayAction.Create(sourceUid, "blocker-" + sourceUid, 0, start, end, false, true, false);
    }

    @Test
    void loadAllOrderedByStart_givenRowsForAnotherCalendar_thenReturnsOnlyRowsForConstructedCalendarId() {
        entityManager.persistAndFlush(
                new PendingCreationEntity(CALENDAR_ID, "source-1", "blocker-1", start(), end(), false, true, false));
        entityManager.persistAndFlush(new PendingCreationEntity(
                OTHER_CALENDAR_ID, "source-2", "blocker-2", start(), end(), false, true, false));

        var result = adapter.loadAllOrderedByStart();

        assertThat(result).extracting(RelayAction.Create::sourceUid).containsExactly("source-1");
    }

    @Test
    void loadAllOrderedByStart_givenMultipleRows_thenReturnsThemOrderedAscendingByStart() {
        var earliest = start();
        var middle = start().plusDays(1);
        var latest = start().plusDays(2);
        adapter.saveAll(List.of(
                createAction("source-latest", latest, latest.plusHours(1)),
                createAction("source-earliest", earliest, earliest.plusHours(1)),
                createAction("source-middle", middle, middle.plusHours(1))));

        var result = adapter.loadAllOrderedByStart();

        assertThat(result)
                .extracting(RelayAction.Create::sourceUid)
                .containsExactly("source-earliest", "source-middle", "source-latest");
    }

    @Test
    void loadAllOrderedByStart_givenSavedEntry_thenRoundTripsAllFieldsIncludingZoneId() {
        adapter.saveAll(List.of(createAction("source-1", start(), end())));

        var result = adapter.loadAllOrderedByStart();

        assertThat(result).singleElement().satisfies(action -> {
            assertThat(action.sourceUid()).isEqualTo("source-1");
            assertThat(action.blockerUid()).isEqualTo("blocker-source-1");
            assertThat(action.sequence()).isZero();
            assertThat(action.start()).isEqualTo(start());
            assertThat(action.start().getZone()).isEqualTo(ZONE);
            assertThat(action.end()).isEqualTo(end());
            assertThat(action.end().getZone()).isEqualTo(ZONE);
            assertThat(action.allDay()).isFalse();
            assertThat(action.busy()).isTrue();
            assertThat(action.cancelled()).isFalse();
        });
    }

    @Test
    void saveAll_givenPendingCreates_thenInsertsAllRowsForConstructedCalendarId() {
        adapter.saveAll(List.of(createAction("source-1", start(), end()), createAction("source-2", start(), end())));

        assertThat(repository.findAllBySourceCalendarIdOrderByStartAsc(CALENDAR_ID)).hasSize(2);
    }

    @Test
    void remove_givenExistingSourceUid_thenDeletesRow() {
        adapter.saveAll(List.of(createAction("source-1", start(), end())));

        adapter.remove("source-1");

        assertThat(repository.findAllBySourceCalendarIdOrderByStartAsc(CALENDAR_ID)).isEmpty();
    }

    @Test
    void remove_givenNonExistentSourceUid_thenIsNoOp() {
        adapter.remove("unknown-source");

        assertThat(repository.findAllBySourceCalendarIdOrderByStartAsc(CALENDAR_ID)).isEmpty();
    }

    @Test
    void loadAllOrderedByStart_givenRepositoryThrows_thenPropagatesUnwrapped() {
        var mockRepository = mock(PendingCreationJpaRepository.class);
        var mockAdapter = new JpaPendingCreationQueueAdapter(mockRepository, CALENDAR_ID);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockRepository).findAllBySourceCalendarIdOrderByStartAsc(CALENDAR_ID);

        assertThatThrownBy(mockAdapter::loadAllOrderedByStart).isSameAs(cause);
    }

    @Test
    void saveAll_givenRepositorySaveAllThrows_thenThrowsPendingCreationQueueExceptionWrappingCause() {
        var mockRepository = mock(PendingCreationJpaRepository.class);
        var mockAdapter = new JpaPendingCreationQueueAdapter(mockRepository, CALENDAR_ID);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockRepository).saveAll(any());

        assertThatThrownBy(() -> mockAdapter.saveAll(List.of(createAction("source-1", start(), end()))))
                .isInstanceOf(PendingCreationQueueException.class)
                .hasCause(cause);
    }

    @Test
    void remove_givenRepositoryDeleteThrows_thenThrowsPendingCreationQueueExceptionWrappingCause() {
        var mockRepository = mock(PendingCreationJpaRepository.class);
        var mockAdapter = new JpaPendingCreationQueueAdapter(mockRepository, CALENDAR_ID);
        var cause = new RuntimeException("db unavailable");
        willThrow(cause).given(mockRepository).deleteBySourceCalendarIdAndSourceUid(CALENDAR_ID, "source-1");

        assertThatThrownBy(() -> mockAdapter.remove("source-1"))
                .isInstanceOf(PendingCreationQueueException.class)
                .hasCause(cause);
    }
}
