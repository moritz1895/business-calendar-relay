package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import ms.rohde.businesscalendarrelay.core.domain.RelayState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;

/**
 * Verifies {@link JpaStateStoreAdapter} against a real (embedded, in-memory for the
 * test) H2 database via {@link DataJpaTest}: per-calendar scoping, upsert semantics on
 * {@code save}, and that {@code markCancelled} flips {@code active} without deleting or
 * losing {@code blockerUid}. Also pins {@code ZonedDateTime} round-tripping including
 * the named zone id, since {@link RelayState}'s own change-detection relies on
 * {@link ZonedDateTime#equals(Object)} comparing zone identity, not just instant.
 *
 * <p>Boots from the empty {@link TestConfig} rather than
 * {@code BusinessCalendarRelayApplication} (the default {@code @DataJpaTest} would
 * auto-detect): that class carries {@code @ArchComponentScan}, which would otherwise
 * eagerly try to instantiate every {@code @ArchComponent}-annotated class in the whole
 * application as a Spring bean, including ones not yet meant to be auto-wired as
 * no-arg singletons (see the PR description for the full explanation). Scoping the
 * context to this package is enough: {@code @DataJpaTest} resolves entity/repository
 * scanning relative to the located configuration class's package.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaStateStoreAdapterTest.TestConfig.class)
class JpaStateStoreAdapterTest {

    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {}

    private static final String CALENDAR_ID = "personal-nextcloud";
    private static final String OTHER_CALENDAR_ID = "work-nextcloud";
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Autowired
    private RelayStateJpaRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private JpaStateStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JpaStateStoreAdapter(repository, CALENDAR_ID);
    }

    private static ZonedDateTime start() {
        return ZonedDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZONE);
    }

    private static ZonedDateTime end() {
        return ZonedDateTime.of(2026, 8, 1, 11, 0, 0, 0, ZONE);
    }

    @Test
    void loadAll_givenRowsForAnotherCalendar_thenReturnsOnlyRowsForConstructedCalendarId() {
        entityManager.persistAndFlush(new RelayStateEntity(
                CALENDAR_ID, "source-1", "blocker-1", 0, start(), end(), true, false, true, false));
        entityManager.persistAndFlush(new RelayStateEntity(
                OTHER_CALENDAR_ID, "source-2", "blocker-2", 0, start(), end(), true, false, true, false));

        var result = adapter.loadAll();

        assertThat(result).extracting(RelayState::sourceUid).containsExactly("source-1");
    }

    @Test
    void loadAll_givenSavedState_thenRoundTripsZonedDateTimeIncludingZoneId() {
        adapter.save(new RelayState("source-1", "blocker-1", 0, start(), end(), true, false, true, false));

        var result = adapter.loadAll();

        assertThat(result).singleElement().satisfies(state -> {
            assertThat(state.lastKnownStart()).isEqualTo(start());
            assertThat(state.lastKnownStart().getZone()).isEqualTo(ZONE);
            assertThat(state.lastKnownEnd()).isEqualTo(end());
            assertThat(state.lastKnownEnd().getZone()).isEqualTo(ZONE);
        });
    }

    @Test
    void loadAll_givenSavedState_thenRoundTripsAllDayBusyAndCancelledFlags() {
        adapter.save(new RelayState("source-1", "blocker-1", 0, start(), end(), true, true, false, true));

        var result = adapter.loadAll();

        assertThat(result).singleElement().satisfies(state -> {
            assertThat(state.lastKnownAllDay()).isTrue();
            assertThat(state.lastKnownBusy()).isFalse();
            assertThat(state.lastKnownCancelled()).isTrue();
        });
    }

    @Test
    void save_givenExistingSourceUid_thenUpdatesAllDayBusyAndCancelledFlagsInPlace() {
        adapter.save(new RelayState("source-1", "blocker-1", 0, start(), end(), true, false, true, false));

        adapter.save(new RelayState("source-1", "blocker-1", 1, start(), end(), true, true, false, true));

        var result = adapter.loadAll();
        assertThat(result).singleElement().satisfies(state -> {
            assertThat(state.lastKnownAllDay()).isTrue();
            assertThat(state.lastKnownBusy()).isFalse();
            assertThat(state.lastKnownCancelled()).isTrue();
        });
    }

    @Test
    void save_givenNewSourceUid_thenInsertsNewRow() {
        adapter.save(new RelayState("source-1", "blocker-1", 0, start(), end(), true, false, true, false));

        assertThat(repository.findAllBySourceCalendarId(CALENDAR_ID)).hasSize(1);
    }

    @Test
    void save_givenExistingSourceUid_thenUpdatesInPlaceWithoutDuplicateRow() {
        adapter.save(new RelayState("source-1", "blocker-1", 0, start(), end(), true, false, true, false));

        var newStart = start().plusHours(2);
        var newEnd = end().plusHours(2);
        adapter.save(new RelayState("source-1", "blocker-1", 1, newStart, newEnd, true, false, true, false));

        var rows = repository.findAllBySourceCalendarId(CALENDAR_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSequence()).isEqualTo(1);
        assertThat(rows.getFirst().getLastKnownStart()).isEqualTo(newStart);
        assertThat(rows.getFirst().getLastKnownEnd()).isEqualTo(newEnd);
    }

    @Test
    void markCancelled_thenSetsActiveFalseAndUpdatesSequenceLeavingBlockerUidUnchanged() {
        adapter.save(new RelayState("source-1", "blocker-1", 0, start(), end(), true, false, true, false));

        adapter.markCancelled("source-1", 1);

        var updated =
                repository.findBySourceCalendarIdAndSourceUid(CALENDAR_ID, "source-1").orElseThrow();
        assertThat(updated.isActive()).isFalse();
        assertThat(updated.getSequence()).isEqualTo(1);
        assertThat(updated.getBlockerUid()).isEqualTo("blocker-1");
    }

    @Test
    void loadAll_givenCancelledEntry_thenStillReturnsItInsteadOfDeleting() {
        adapter.save(new RelayState("source-1", "blocker-1", 0, start(), end(), true, false, true, false));
        adapter.markCancelled("source-1", 1);

        var result = adapter.loadAll();

        assertThat(result).singleElement().satisfies(state -> {
            assertThat(state.sourceUid()).isEqualTo("source-1");
            assertThat(state.active()).isFalse();
        });
    }

    @Test
    void markCancelled_givenUnknownSourceUid_thenThrowsIllegalStateException() {
        assertThatThrownBy(() -> adapter.markCancelled("unknown-source", 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
