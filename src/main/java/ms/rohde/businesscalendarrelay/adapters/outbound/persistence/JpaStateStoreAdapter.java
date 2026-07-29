package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import java.util.Optional;
import ms.rohde.businesscalendarrelay.core.domain.RelayState;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStore;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStoreException;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;

/**
 * {@link StateStore} backed by an embedded, file-mode H2 database via Spring Data JPA.
 *
 * <p>One instance per configured source calendar, per {@link StateStore}'s own
 * contract: the {@code sourceCalendarId} passed to the constructor scopes every
 * repository call this instance makes, so a {@link RelayStateJpaRepository} can safely
 * be shared across every calendar's adapter instance while each instance only ever
 * sees, returns, or mutates its own rows. The composite business key per row is
 * {@code (sourceCalendarId, sourceUid)}.
 *
 * <p>Deliberately not wired as an auto-scanned, no-arg Spring singleton bean, since
 * that would be at odds with needing a per-calendar {@code sourceCalendarId} at
 * construction time. A later PR (scheduler + multi-calendar configuration wiring)
 * constructs one instance per configured calendar via explicit {@code @Bean} factory
 * methods and injects it into that calendar's use-case instance. See the PR description
 * for how this interacts with {@code @ArchComponentScan}.
 */
@InfrastructureServiceAdapter
public final class JpaStateStoreAdapter implements StateStore {

    private final RelayStateJpaRepository repository;
    private final String sourceCalendarId;

    public JpaStateStoreAdapter(RelayStateJpaRepository repository, String sourceCalendarId) {
        this.repository = repository;
        this.sourceCalendarId = sourceCalendarId;
    }

    @Override
    public List<RelayState> loadAll() {
        return repository.findAllBySourceCalendarId(sourceCalendarId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void save(RelayState state) {
        var entity = findEntity(state.sourceUid())
                .orElseGet(() -> new RelayStateEntity(
                        sourceCalendarId,
                        state.sourceUid(),
                        state.blockerUid(),
                        state.sequence(),
                        state.lastKnownStart(),
                        state.lastKnownEnd(),
                        state.active(),
                        state.lastKnownAllDay(),
                        state.lastKnownBusy(),
                        state.lastKnownCancelled()));

        entity.setBlockerUid(state.blockerUid());
        entity.setSequence(state.sequence());
        entity.setLastKnownStart(state.lastKnownStart());
        entity.setLastKnownEnd(state.lastKnownEnd());
        entity.setActive(state.active());
        entity.setLastKnownAllDay(state.lastKnownAllDay());
        entity.setLastKnownBusy(state.lastKnownBusy());
        entity.setLastKnownCancelled(state.lastKnownCancelled());

        saveEntity(entity, state.sourceUid());
    }

    @Override
    public void markCancelled(String sourceUid, long sequence) {
        var entity = findEntity(sourceUid)
                .orElseThrow(() -> new IllegalStateException("No RelayState found for sourceCalendarId="
                        + sourceCalendarId + ", sourceUid=" + sourceUid));

        entity.setSequence(sequence);
        entity.setActive(false);

        saveEntity(entity, sourceUid);
    }

    private Optional<RelayStateEntity> findEntity(String sourceUid) {
        try {
            return repository.findBySourceCalendarIdAndSourceUid(sourceCalendarId, sourceUid);
        } catch (RuntimeException e) {
            throw new StateStoreException(
                    "Failed to load relay state for sourceCalendarId=" + sourceCalendarId + ", sourceUid="
                            + sourceUid,
                    e);
        }
    }

    private void saveEntity(RelayStateEntity entity, String sourceUid) {
        try {
            repository.save(entity);
        } catch (RuntimeException e) {
            throw new StateStoreException(
                    "Failed to persist relay state for sourceCalendarId=" + sourceCalendarId + ", sourceUid="
                            + sourceUid,
                    e);
        }
    }

    private RelayState toDomain(RelayStateEntity entity) {
        return new RelayState(
                entity.getSourceUid(),
                entity.getBlockerUid(),
                entity.getSequence(),
                entity.getLastKnownStart(),
                entity.getLastKnownEnd(),
                entity.isActive(),
                entity.isLastKnownAllDay(),
                entity.isLastKnownBusy(),
                entity.isLastKnownCancelled());
    }
}
