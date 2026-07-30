package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.List;
import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;
import org.jspecify.annotations.Nullable;

/**
 * Durable, per-source-calendar local replica of a CalDAV collection's raw resources, plus
 * the RFC 6578 {@code sync-collection} sync-token used to fetch the next incremental
 * delta. Backs {@code CalDavCalendarSourceAdapter}'s delta-sync mechanism (see
 * {@code docs/features/delta-sync.md}) -- a resource delta only ever updates this raw
 * replica; the existing {@code expandAll}/{@code expandSeries} pipeline re-derives the
 * full, always-current {@code SourceEvent} snapshot from it on every
 * {@code CalendarSource.readEvents()} call.
 *
 * <p>A dedicated port rather than an extension of {@link StateStore}, analogous to why
 * {@link PendingCreationQueue} is its own port (see ADR-008): {@code RelayState}'s
 * invariants describe relay-occurrence bookkeeping, while this store describes CalDAV
 * protocol bookkeeping (raw bytes, ETags, {@code href}s, a token) at a different
 * abstraction level with a different key ({@code href}, not {@code sourceUid}).
 *
 * <p>One configured instance per source calendar, exactly like {@link StateStore},
 * {@code CalendarSource}, and {@link PendingCreationQueue}.
 */
@InfrastructureServicePort
public interface CalendarReplicaStore {

    /**
     * Returns the persisted sync-token for this source calendar, or {@code null} if none
     * is stored yet -- either this calendar has never been synced under this feature, or
     * a prior forced resync cleared it via {@link #resetTo}. A {@code null} return means
     * the next sync-collection exchange must use an empty request token (initial sync).
     */
    @Nullable String loadSyncToken();

    /**
     * Returns every currently cached raw resource for this source calendar. Order is not
     * part of the contract -- callers group and expand by UID, not by insertion order.
     */
    List<CachedCalendarResource> loadAllResources();

    /**
     * Applies one incremental sync-collection delta in a single persistence operation:
     * upserts {@code upserted} (keyed by {@link CachedCalendarResource#href()}), removes
     * every entry whose {@code href} is in {@code removedHrefs}, and advances the stored
     * sync-token to {@code newSyncToken}.
     *
     * @throws CalendarReplicaStoreException if the underlying persistence operation fails
     */
    void applyDelta(String newSyncToken, List<CachedCalendarResource> upserted, List<String> removedHrefs);

    /**
     * Replaces the entire cached resource set and sync-token for this source calendar in
     * one shot. Used for an initial sync-collection exchange (empty request token) and for
     * a forced full resync after the server invalidates a previously stored token.
     *
     * @throws CalendarReplicaStoreException if the underlying persistence operation fails
     */
    void resetTo(String newSyncToken, List<CachedCalendarResource> resources);
}
