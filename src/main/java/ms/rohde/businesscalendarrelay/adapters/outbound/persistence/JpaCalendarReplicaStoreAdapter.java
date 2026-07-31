package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedCalendarResource;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarReplicaStore;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarReplicaStoreException;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p>{@link #applyDelta}/{@link #resetTo} each need <em>multiple</em> repository calls
 * (upsert, delete, sync-token save) to commit as one atomic unit -- {@code @Transactional}
 * cannot provide that here the way it does for a single-call repository method, since this
 * adapter is constructed via plain {@code new} (see {@code RelayWiringConfiguration}),
 * never through Spring, so there is no proxy around it for {@code @Transactional} on its
 * own methods to attach to (verified directly: an earlier version of this exact bug in the
 * sibling {@code JpaPendingCreationQueueAdapter} kept failing with "No EntityManager with
 * actual transaction available for current thread" until fixed). A directly injected
 * {@link PlatformTransactionManager} (itself a genuine Spring-managed singleton, safe to
 * hand to a non-managed object) wrapped in a {@link TransactionTemplate} sidesteps the
 * proxy requirement entirely by opening the transaction programmatically instead of
 * declaratively.
 */
@InfrastructureServiceAdapter
public final class JpaCalendarReplicaStoreAdapter implements CalendarReplicaStore {

    private final CalendarReplicaResourceJpaRepository resourceRepository;
    private final CalendarSyncTokenJpaRepository tokenRepository;
    private final String sourceCalendarId;
    private final TransactionTemplate transactionTemplate;

    public JpaCalendarReplicaStoreAdapter(
            CalendarReplicaResourceJpaRepository resourceRepository,
            CalendarSyncTokenJpaRepository tokenRepository,
            String sourceCalendarId,
            PlatformTransactionManager transactionManager) {
        this.resourceRepository = resourceRepository;
        this.tokenRepository = tokenRepository;
        this.sourceCalendarId = sourceCalendarId;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    public void applyDelta(String newSyncToken, List<CachedCalendarResource> upserted, List<String> removedHrefs) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                upsertResources(upserted);
                if (!removedHrefs.isEmpty()) {
                    resourceRepository.deleteBySourceCalendarIdAndHrefIn(sourceCalendarId, removedHrefs);
                }
                saveSyncToken(newSyncToken);
            });
        } catch (RuntimeException e) {
            throw new CalendarReplicaStoreException(
                    "Failed to apply calendar replica delta for sourceCalendarId=" + sourceCalendarId, e);
        }
    }

    @Override
    public void resetTo(String newSyncToken, List<CachedCalendarResource> resources) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                resourceRepository.deleteBySourceCalendarId(sourceCalendarId);
                upsertResources(resources);
                saveSyncToken(newSyncToken);
            });
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
