package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.List;
import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;
import org.jspecify.annotations.Nullable;

/**
 * Durable, per-source-calendar local replica of a Google Calendar's raw event resources,
 * plus the {@code syncToken} used to fetch the next incremental {@code events.list} delta.
 * Backs {@code GoogleCalendarSourceAdapter}'s delta-sync mechanism (see
 * {@code docs/features/google-calendar-integration.md}), a direct Google-side analogue of
 * {@link CalendarReplicaStore} for CalDAV.
 *
 * <p>A dedicated port rather than a reuse of {@link CalendarReplicaStore} itself -- see
 * {@code docs/features/google-calendar-integration.md}'s Design-Entscheidung 4: Google's
 * {@code events.list} response is JSON, not raw ICS text, and its natural resource identity
 * under {@code singleEvents=true} is per already-expanded occurrence (Google's own
 * {@code eventId}), not per series the way a CalDAV {@code href} is. Reusing
 * {@link CalendarReplicaStore}'s type would blur that distinction without any shared
 * processing benefit, and would require CalDAV's own port/adapter to change to accommodate
 * a concept (a horizon time window) it does not need -- see {@link
 * #loadAllEvents()}'s Javadoc for why the horizon-bound supplemental fetch itself is
 * deliberately not part of this port's contract.
 *
 * <p>One configured instance per source calendar, exactly like {@link CalendarReplicaStore},
 * {@code CalendarSource}, {@link StateStore}, and {@link PendingCreationQueue}.
 */
@InfrastructureServicePort
public interface GoogleCalendarReplicaStore {

    /**
     * Returns the persisted sync-token for this Google source calendar, or {@code null} if
     * none is stored yet. A {@code null} return means the next {@code events.list} exchange
     * must omit {@code syncToken} entirely (full sync).
     */
    @Nullable String loadSyncToken();

    /**
     * Returns every currently cached Google Calendar event instance for this source
     * calendar. Order is not part of the contract.
     *
     * <p>Deliberately does not know about the horizon-bound supplemental fetch described in
     * {@code docs/features/google-calendar-integration.md}'s Design-Entscheidung 3: that
     * fetch is intentionally not part of the persisted replica at all (no {@code syncToken}
     * progression, no {@link #applyDelta}) -- it lives entirely inside
     * {@code GoogleCalendarSourceAdapter} as an additional, {@code syncToken}-independent
     * HTTP call whose result is only transiently unioned with what this method returns.
     */
    List<CachedGoogleCalendarEvent> loadAllEvents();

    /**
     * Applies one incremental {@code events.list(syncToken=...)} delta in a single
     * persistence operation: upserts {@code upserted} (keyed by
     * {@link CachedGoogleCalendarEvent#eventId()}), removes every entry whose
     * {@code eventId} is in {@code removedEventIds} (Google-side cancellations, reported via
     * {@code showDeleted=true}), and advances the stored sync-token to {@code newSyncToken}.
     *
     * @throws GoogleCalendarReplicaStoreException if the underlying persistence operation fails
     */
    void applyDelta(String newSyncToken, List<CachedGoogleCalendarEvent> upserted, List<String> removedEventIds);

    /**
     * Replaces the entire cached event set and sync-token for this source calendar in one
     * shot. Used for an initial {@code events.list} exchange (no {@code syncToken}) and for a
     * forced full resync after Google invalidates a previously stored token ({@code 410 Gone}).
     *
     * @throws GoogleCalendarReplicaStoreException if the underlying persistence operation fails
     */
    void resetTo(String newSyncToken, List<CachedGoogleCalendarEvent> events);
}
