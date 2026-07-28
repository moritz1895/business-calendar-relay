package ms.rohde.businesscalendarrelay.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code relay.*} configuration: the shared poll interval and the list of
 * configured private source calendars, per {@code CLAUDE.md}'s "any number of source
 * calendars, declared in one config file" requirement.
 *
 * <p>An empty {@link #calendars()} is valid on purpose so the application starts
 * cleanly with zero source calendars configured (e.g. in CI).
 */
@Validated
@ConfigurationProperties("relay")
public record RelayProperties(@NotNull Duration pollInterval, @Valid List<CalendarConfig> calendars) {

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
