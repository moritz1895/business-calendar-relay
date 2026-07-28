package ms.rohde.businesscalendarrelay.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code relay.*} configuration: the shared poll interval, the shared recurring-
 * event creation horizon, and the list of configured private source calendars, per
 * {@code CLAUDE.md}'s "any number of source calendars, declared in one config file"
 * requirement.
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
 */
@Validated
@ConfigurationProperties("relay")
public record RelayProperties(
        @NotNull Duration pollInterval,
        @Valid List<CalendarConfig> calendars,
        @NotNull @DefaultValue("P6M") Period recurringEventHorizon) {

    public RelayProperties {
        calendars = calendars == null ? List.of() : List.copyOf(calendars);
    }

    /**
     * One configured private source calendar: its CalDAV location and credentials, its
     * stable persistence key, and the iMIP identity used for every blocker it produces.
     *
     * @param id stable identifier for this calendar, used as {@code JpaStateStoreAdapter}'s
     *     {@code sourceCalendarId} persistence key. Must never be renamed once events
     *     have been relayed under it -- see {@code README.md}.
     * @param caldavUrl CalDAV calendar collection URL this entry reads events from
     * @param caldavUsername Basic-auth username for {@code caldavUrl}
     * @param caldavPassword Basic-auth password for {@code caldavUrl}
     * @param organizerEmail organizer address set on every blocker built from this calendar
     * @param attendeeEmail business Outlook mailbox address the iMIP mail is sent to
     * @param fromAddress {@code From}/envelope-from of the iMIP mail
     * @param replyToAddress {@code Reply-To} of the iMIP mail, typically the organizer's
     *     human address
     */
    public record CalendarConfig(
            @NotBlank String id,
            @NotBlank String caldavUrl,
            @NotBlank String caldavUsername,
            @NotBlank String caldavPassword,
            @NotBlank String organizerEmail,
            @NotBlank String attendeeEmail,
            @NotBlank String fromAddress,
            @NotBlank String replyToAddress) {
    }
}
