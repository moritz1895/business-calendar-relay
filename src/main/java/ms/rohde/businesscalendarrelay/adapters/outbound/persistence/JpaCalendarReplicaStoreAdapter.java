package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedCalendarResource;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarReplicaStore;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarReplicaStoreException;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CalendarReplicaStore} backed by the same embedded, file-mode H2 database as
 * {@link JpaStateStoreAdapter} and {@link JpaPendingCreationQueueAdapter}, via Spring
 * Data JPA.
 *
 * <p>One instance per configured source calendar, per {@link CalendarReplicaStore}'s own
 * contract: the {@code sourceCalendarId} passed to the constructor scopes every
 * repository call this instance makes, so a {@link CalendarReplicaResourceJpaRepository}
 * and a {@link CalendarSyncTokenJpaRepository} can safely be shared across every
 * calendar's adapter instance while each instance only ever sees, returns, or mutates its
 * own rows. The composite business key per resource row is {@code (sourceCalendarId,
 * href)}; the sync-token table has exactly one row per {@code sourceCalendarId}.
 *
 * <p>Deliberately not wired as an auto-scanned, no-arg Spring singleton bean, for the same
 * reason as {@link JpaStateStoreAdapter} and {@link JpaPendingCreationQueueAdapter} -- see
 * those classes' Javadoc for the full explanation.
 */
@InfrastructureServiceAdapter
public final class JpaCalendarReplicaStoreAdapter implements CalendarReplicaStore {

    private final CalendarReplicaResourceJpaRepository resourceRepository;
    private final CalendarSyncTokenJpaRepository tokenRepository;
    private final String sourceCalendarId;

    public JpaCalendarReplicaStoreAdapter(
            CalendarReplicaResourceJpaRepository resourceRepository,
            CalendarSyncTokenJpaRepository tokenRepository,
            String sourceCalendarId) {
        this.resourceRepository = resourceRepository;
        this.tokenRepository = tokenRepository;
        this.sourceCalendarId = sourceCalendarId;
    }

    @Override
    public @Nullable String loadSyncToken() {
        return tokenRepository
                .findBySourceCalendarId(sourceCalendarId)
                .map(CalendarSyncTokenEntity::getSyncToken)
                .orElse(null);
    }

    @Override
    public List<CachedCalendarResource> loadAllResources() {
        return resourceRepository.findAllBySourceCalendarId(sourceCalendarId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void applyDelta(String newSyncToken, List<CachedCalendarResource> upserted, List<String> removedHrefs) {
        try {
            upsertResources(upserted);
            if (!removedHrefs.isEmpty()) {
                resourceRepository.deleteBySourceCalendarIdAndHrefIn(sourceCalendarId, removedHrefs);
            }
            saveSyncToken(newSyncToken);
        } catch (RuntimeException e) {
            throw new CalendarReplicaStoreException(
                    "Failed to apply calendar replica delta for sourceCalendarId=" + sourceCalendarId, e);
        }
    }

    @Override
    @Transactional
    public void resetTo(String newSyncToken, List<CachedCalendarResource> resources) {
        try {
            resourceRepository.deleteBySourceCalendarId(sourceCalendarId);
            upsertResources(resources);
            saveSyncToken(newSyncToken);
        } catch (RuntimeException e) {
            throw new CalendarReplicaStoreException(
                    "Failed to reset calendar replica for sourceCalendarId=" + sourceCalendarId, e);
        }
    }

    private void upsertResources(List<CachedCalendarResource> resources) {
        if (resources.isEmpty()) {
            return;
        }
        var entities = resources.stream()
                .map(resource -> new CalendarReplicaResourceEntity(
                        sourceCalendarId, resource.href(), resource.etag(), resource.rawCalendarData()))
                .toList();
        resourceRepository.saveAll(entities);
    }

    private void saveSyncToken(String newSyncToken) {
        var entity = tokenRepository
                .findBySourceCalendarId(sourceCalendarId)
                .orElseGet(() -> new CalendarSyncTokenEntity(sourceCalendarId, newSyncToken));
        entity.setSyncToken(newSyncToken);
        tokenRepository.save(entity);
    }

    private CachedCalendarResource toDomain(CalendarReplicaResourceEntity entity) {
        return new CachedCalendarResource(entity.getHref(), entity.getEtag(), entity.getRawCalendarData());
    }
}
