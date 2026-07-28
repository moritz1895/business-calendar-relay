package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import ms.rohde.businesscalendarrelay.core.domain.RelayState;
import ms.rohde.businesscalendarrelay.ports.outbound.StateStore;
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
        var entity = repository
                .findBySourceCalendarIdAndSourceUid(sourceCalendarId, state.sourceUid())
                .orElseGet(() -> new RelayStateEntity(
                        sourceCalendarId,
                        state.sourceUid(),
                        state.blockerUid(),
                        state.sequence(),
                        state.lastKnownStart(),
                        state.lastKnownEnd(),
                        state.active()));

        entity.setBlockerUid(state.blockerUid());
        entity.setSequence(state.sequence());
        entity.setLastKnownStart(state.lastKnownStart());
        entity.setLastKnownEnd(state.lastKnownEnd());
        entity.setActive(state.active());

        repository.save(entity);
    }

    @Override
    public void markCancelled(String sourceUid, long sequence) {
        var entity = repository
                .findBySourceCalendarIdAndSourceUid(sourceCalendarId, sourceUid)
                .orElseThrow(() -> new IllegalStateException("No RelayState found for sourceCalendarId="
                        + sourceCalendarId + ", sourceUid=" + sourceUid));

        entity.setSequence(sequence);
        entity.setActive(false);

        repository.save(entity);
    }

    private RelayState toDomain(RelayStateEntity entity) {
        return new RelayState(
                entity.getSourceUid(),
                entity.getBlockerUid(),
                entity.getSequence(),
                entity.getLastKnownStart(),
                entity.getLastKnownEnd(),
                entity.isActive());
    }
}
