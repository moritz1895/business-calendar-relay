package ms.rohde.businesscalendarrelay.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code relay.*} configuration: the shared poll interval, the shared recurring-
 * event creation horizon, the shared initialization burst-filter budget, the shared iMIP
 * identity, the shared Google OAuth credential sets, and the list of configured private
 * source calendars, per {@code CLAUDE.md}'s "any number of source calendars, declared in
 * one config file" requirement.
 *
 * <p>An empty {@link #calendars()} is valid on purpose so the application starts
 * cleanly with zero source calendars configured (e.g. in CI).
 *
 * <p>{@link #recurringEventHorizon()} is a global value shared across every configured
 * calendar (same pattern as {@link #pollInterval()}), not a per-{@link CalendarConfig}
 * override. It bounds how far into the future a recurring source event's occurrence may
 * start and still be eligible for its first blocker creation -- see
 * {@code RelayDiffPlanner#isEligibleForCreation}. {@code Period}, not {@code Duration},
 * because "6 months from now" is a calendar-based span, not an elapsed-time one.
 *
 * <p>{@link #initialization()} is likewise a single, mailbox-wide value shared across
 * every configured calendar, not a per-{@link CalendarConfig} override -- see
 * {@code docs/features/burst-filter-initialization.md} (issue #16) for why a per-calendar
 * override would defeat the mailbox-wide budget's purpose.
 *
 * <p>{@link #organizerEmail()}/{@link #attendeeEmail()}/{@link #fromAddress()}/
 * {@link #replyToAddress()} are, likewise, a single global iMIP identity shared across
 * every configured calendar regardless of {@link CalendarConfig#type()} -- see
 * {@code docs/features/relay-config-consolidation.md}. Deliberately no per-calendar
 * override: this project's single deployer runs exactly one business mailbox.
 *
 * <p>{@link #googleCredentials()} is a global list of named Google OAuth credential sets,
 * each referenced by id from any number of {@code type: GOOGLE} {@link CalendarConfig}
 * entries via {@link CalendarConfig#googleCredentialsId()} -- see
 * {@code docs/features/relay-config-consolidation.md}. An empty list is valid, exactly
 * like an empty {@link #calendars()}, for a deployment with no {@code type: GOOGLE}
 * calendar configured. {@link ConsistentGoogleCredentialsReferences} enforces that every
 * configured reference actually resolves and that no two entries share an {@code id}.
 */
@Validated
@ConfigurationProperties("relay")
@ConsistentGoogleCredentialsReferences
public record RelayProperties(
        @NotNull Duration pollInterval,
        @Valid List<CalendarConfig> calendars,
        @NotNull @DefaultValue("P6M") Period recurringEventHorizon,
        @NotNull @Valid InitializationProperties initialization,
        @NotBlank String organizerEmail,
        @NotBlank String attendeeEmail,
        @NotBlank String fromAddress,
        @NotBlank String replyToAddress,
        @Valid List<GoogleCredentials> googleCredentials) {

    public RelayProperties {
        calendars = calendars == null ? List.of() : List.copyOf(calendars);
        initialization = initialization == null ? new InitializationProperties(5, Duration.ofHours(1)) : initialization;
        googleCredentials = googleCredentials == null ? List.of() : List.copyOf(googleCredentials);
    }

    /**
     * Mailbox-wide send budget throttling the one-time initialization ramp-up for a
     * source calendar's very first poll cycle (see
     * {@code docs/features/burst-filter-initialization.md}, issue #16). Global across all
     * configured calendars combined, never a per-calendar override.
     *
     * @param burstSize how many initialization-backlog creations may be sent per
     *     {@code burstInterval}, mailbox-wide across all configured calendars combined.
     *     {@code @Min(1)} prevents a configuration that would never allow a single
     *     creation through, permanently stalling a calendar's initialization.
     * @param burstInterval the send budget's window size. {@code Duration}, not
     *     {@code Period}, since "once per hour" is a plain elapsed-time span with no
     *     calendar-based ambiguity, unlike {@link #recurringEventHorizon()}.
     */
    public record InitializationProperties(
            @Min(1) @DefaultValue("5") int burstSize, @NotNull @DefaultValue("PT1H") Duration burstInterval) {
    }

    /**
     * One named Google OAuth 2.0 credential set, configured once per Google account under
     * {@code relay.google-credentials[]} and referenced by any number of {@code type:
     * GOOGLE} {@link CalendarConfig} entries via {@link CalendarConfig#googleCredentialsId()}
     * -- see {@code docs/features/relay-config-consolidation.md}.
     *
     * @param id stable identifier for this credential set, freely chosen by the deployer.
     *     Referenced from {@link CalendarConfig#googleCredentialsId()}. Must be unique
     *     within {@link #googleCredentials()} -- enforced by
     *     {@link ConsistentGoogleCredentialsReferences}.
     * @param clientId the deployer's own OAuth 2.0 client id, from a Google Cloud project
     *     the deployer owns.
     * @param clientSecret the deployer's own OAuth 2.0 client secret, paired with
     *     {@link #clientId()}.
     * @param refreshToken the long-lived refresh token obtained once via the OAuth
     *     Playground consent flow, exchanged for a short-lived access token on every poll
     *     cycle -- treated exactly like a CalDAV password: never rewritten at runtime.
     */
    public record GoogleCredentials(
            @NotBlank String id,
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotBlank String refreshToken) {
    }

    /**
     * One configured private source calendar: its source-protocol location and
     * credentials, and its stable persistence key. The iMIP identity used for every
     * blocker this calendar produces is no longer carried here -- it is global, see
     * {@link RelayProperties#organizerEmail()} and siblings.
     *
     * <p>{@link #type()} discriminates between the two coexisting source-protocol
     * families this record can describe -- CalDAV ({@link #caldavUrl()}/
     * {@link #caldavUsername()}/{@link #caldavPassword()}) or Google Calendar
     * ({@link #googleCalendarId()}/{@link #googleCredentialsId()}) -- see
     * {@code docs/features/google-calendar-integration.md}'s Design-Entscheidung 1. It
     * defaults to {@code CALDAV} so an existing deployment's configuration, written before
     * this field existed, binds unchanged (Spring Boot's relaxed binding fills in the
     * default whenever the {@code type} key is absent) -- the zero-config-migration
     * requirement that spec is built around. Neither the CalDAV-specific nor the
     * Google-specific fields carry {@code @NotBlank} any more: which set is mandatory
     * depends on {@link #type()}, expressed instead by the class-level
     * {@link ConsistentCalendarSourceFields} constraint below.
     *
     * @param id stable identifier for this calendar, used as {@code JpaStateStoreAdapter}'s
     *     {@code sourceCalendarId} persistence key. Must never be renamed once events
     *     have been relayed under it -- see {@code README.md}.
     * @param type which source-protocol family this entry describes. Defaults to
     *     {@code CALDAV}.
     * @param caldavUrl CalDAV calendar collection URL this entry reads events from.
     *     Required only when {@link #type()} is {@code CALDAV}.
     * @param caldavUsername Basic-auth username for {@code caldavUrl}. Required only when
     *     {@link #type()} is {@code CALDAV}.
     * @param caldavPassword Basic-auth password for {@code caldavUrl}. Required only when
     *     {@link #type()} is {@code CALDAV}.
     * @param googleCalendarId Google Calendar identifier {@code GoogleCalendarSourceAdapter}
     *     reads events from. Required only when {@link #type()} is {@code GOOGLE}.
     * @param googleCredentialsId references a {@link GoogleCredentials#id()} in
     *     {@link RelayProperties#googleCredentials()}, resolved in
     *     {@code RelayWiringConfiguration} into the concrete client-id/client-secret/
     *     refresh-token triple passed to {@code GoogleCalendarSourceAdapter}. Required
     *     only when {@link #type()} is {@code GOOGLE} -- see
     *     {@code docs/features/relay-config-consolidation.md}.
     * @param deltaSyncEnabled whether the configured source adapter may use its
     *     protocol-specific delta-sync mechanism for this calendar (RFC 6578
     *     {@code sync-collection} for CalDAV, {@code syncToken}-based {@code events.list}
     *     for Google -- see {@code docs/features/delta-sync.md} and
     *     {@code docs/features/google-calendar-integration.md}). A per-calendar, not
     *     global, field, shared verbatim across both types: the "kill switch, falls back
     *     to an always-full read" semantics is identical for both, only the concrete
     *     fallback mechanism differs adapter-internally. Defaults to {@code true}; a
     *     CalDAV server that does not support {@code sync-collection} is already detected
     *     automatically and falls back to the legacy {@code calendar-query} request on its
     *     own -- this flag is a manual override for the rarer case where the automatic
     *     detection itself misbehaves against a specific server.
     */
    @ConsistentCalendarSourceFields
    public record CalendarConfig(
            @NotBlank String id,
            @NotNull @DefaultValue("caldav") CalendarSourceType type,
            @Nullable String caldavUrl,
            @Nullable String caldavUsername,
            @Nullable String caldavPassword,
            @Nullable String googleCalendarId,
            @Nullable String googleCredentialsId,
            @DefaultValue("true") boolean deltaSyncEnabled) {

        /**
         * The two coexisting source-protocol families a {@link CalendarConfig} entry can
         * describe -- see {@code docs/features/google-calendar-integration.md}.
         */
        public enum CalendarSourceType {
            CALDAV, GOOGLE
        }
    }
}
