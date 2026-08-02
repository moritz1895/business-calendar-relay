package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import java.util.List;
import ms.rohde.businesscalendarrelay.ports.outbound.CachedGoogleCalendarEvent;
import ms.rohde.businesscalendarrelay.ports.outbound.GoogleCalendarReplicaStore;
import ms.rohde.businesscalendarrelay.ports.outbound.GoogleCalendarReplicaStoreException;
import ms.rohde.hexagonalarch.annotations.InfrastructureServiceAdapter;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link GoogleCalendarReplicaStore} backed by the same embedded, file-mode H2 database as
 * {@link JpaStateStoreAdapter} and {@link JpaCalendarReplicaStoreAdapter}, via Spring Data
 * JPA. Structurally a direct sibling of {@link JpaCalendarReplicaStoreAdapter}, keyed by
 * Google's own per-occurrence {@code eventId} instead of a CalDAV {@code href} -- see
 * {@code docs/features/google-calendar-integration.md}.
 *
 * <p>One instance per configured Google source calendar, per {@link
 * GoogleCalendarReplicaStore}'s own contract: the {@code sourceCalendarId} passed to the
 * constructor scopes every repository call this instance makes, so a
 * {@link GoogleCalendarReplicaResourceJpaRepository} and a
 * {@link GoogleCalendarSyncTokenJpaRepository} can safely be shared across every Google
 * calendar's adapter instance while each instance only ever sees, returns, or mutates its
 * own rows.
 *
 * <p>Deliberately not wired as an auto-scanned, no-arg Spring singleton bean, for the same
 * reason as {@link JpaCalendarReplicaStoreAdapter} -- see that class's Javadoc for the full
 * explanation, including why a directly injected {@link PlatformTransactionManager} wrapped
 * in a {@link TransactionTemplate} is used instead of a declarative {@code @Transactional}
 * on this adapter's own methods.
 */
@InfrastructureServiceAdapter
public final class JpaGoogleCalendarReplicaStoreAdapter implements GoogleCalendarReplicaStore {

    private final GoogleCalendarReplicaResourceJpaRepository resourceRepository;
    private final GoogleCalendarSyncTokenJpaRepository tokenRepository;
    private final String sourceCalendarId;
    private final TransactionTemplate transactionTemplate;

    public JpaGoogleCalendarReplicaStoreAdapter(
            GoogleCalendarReplicaResourceJpaRepository resourceRepository,
            GoogleCalendarSyncTokenJpaRepository tokenRepository,
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
                .map(GoogleCalendarSyncTokenEntity::getSyncToken)
                .orElse(null);
    }

    @Override
    public List<CachedGoogleCalendarEvent> loadAllEvents() {
        return resourceRepository.findAllBySourceCalendarId(sourceCalendarId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void applyDelta(
            String newSyncToken, List<CachedGoogleCalendarEvent> upserted, List<String> removedEventIds) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                upsertEvents(upserted);
                if (!removedEventIds.isEmpty()) {
                    resourceRepository.deleteBySourceCalendarIdAndEventIdIn(sourceCalendarId, removedEventIds);
                }
                saveSyncToken(newSyncToken);
            });
        } catch (RuntimeException e) {
            throw new GoogleCalendarReplicaStoreException(
                    "Failed to apply Google calendar replica delta for sourceCalendarId=" + sourceCalendarId, e);
        }
    }

    @Override
    public void resetTo(String newSyncToken, List<CachedGoogleCalendarEvent> events) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                resourceRepository.deleteBySourceCalendarId(sourceCalendarId);
                upsertEvents(events);
                saveSyncToken(newSyncToken);
            });
        } catch (RuntimeException e) {
            throw new GoogleCalendarReplicaStoreException(
                    "Failed to reset Google calendar replica for sourceCalendarId=" + sourceCalendarId, e);
        }
    }

    private void upsertEvents(List<CachedGoogleCalendarEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        var entities = events.stream()
                .map(event -> new GoogleCalendarReplicaResourceEntity(
                        sourceCalendarId, event.eventId(), event.etag(), event.rawEventJson()))
                .toList();
        resourceRepository.saveAll(entities);
    }

    private void saveSyncToken(String newSyncToken) {
        var entity = tokenRepository
                .findBySourceCalendarId(sourceCalendarId)
                .orElseGet(() -> new GoogleCalendarSyncTokenEntity(sourceCalendarId, newSyncToken));
        entity.setSyncToken(newSyncToken);
        tokenRepository.save(entity);
    }

    private CachedGoogleCalendarEvent toDomain(GoogleCalendarReplicaResourceEntity entity) {
        return new CachedGoogleCalendarEvent(entity.getEventId(), entity.getEtag(), entity.getRawEventJson());
    }
}
