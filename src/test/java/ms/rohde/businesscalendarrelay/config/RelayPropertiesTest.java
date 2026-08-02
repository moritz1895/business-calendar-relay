package ms.rohde.businesscalendarrelay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Map;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig.CalendarSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifies {@code relay.recurring-event-horizon} and {@code relay.initialization}'s
 * binding behaviour specifically -- default values when absent from configuration, and
 * correct parsing when present -- via {@link Binder} directly rather than booting a
 * Spring context, mirroring how {@link RelayWiringConfigurationTest} keeps
 * {@code RelayWiringConfiguration}'s config-to-use-case mapping testable without one.
 */
class RelayPropertiesTest {

    private static final RelayProperties.InitializationProperties DEFAULT_INITIALIZATION =
            new RelayProperties.InitializationProperties(5, Duration.ofHours(1));

    @Test
    void bind_givenRecurringEventHorizonAbsentFromConfiguration_thenDefaultsToSixMonths() {
        var source = new MapConfigurationPropertySource(Map.of("relay.poll-interval", "5m"));
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.recurringEventHorizon()).isEqualTo(Period.ofMonths(6));
    }

    @Test
    void bind_givenRecurringEventHorizonConfigured_thenBindsConfiguredPeriod() {
        var source = new MapConfigurationPropertySource(
                Map.of("relay.poll-interval", "5m", "relay.recurring-event-horizon", "P3M"));
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.recurringEventHorizon()).isEqualTo(Period.ofMonths(3));
    }

    @Test
    void bind_givenInitializationAbsentFromConfiguration_thenDefaultsToBurstSizeFiveAndOneHourInterval() {
        var source = new MapConfigurationPropertySource(Map.of("relay.poll-interval", "5m"));
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.initialization().burstSize()).isEqualTo(5);
        assertThat(relayProperties.initialization().burstInterval()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void bind_givenInitializationConfigured_thenBindsConfiguredBurstSizeAndInterval() {
        var source = new MapConfigurationPropertySource(Map.of(
                "relay.poll-interval",
                "5m",
                "relay.initialization.burst-size",
                "10",
                "relay.initialization.burst-interval",
                "PT30M"));
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.initialization().burstSize()).isEqualTo(10);
        assertThat(relayProperties.initialization().burstInterval()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void create_givenExplicitRecurringEventHorizon_thenRelayPropertiesCarriesIt() {
        var relayProperties =
                new RelayProperties(Duration.ofMinutes(5), List.of(), Period.ofMonths(9), DEFAULT_INITIALIZATION);

        assertThat(relayProperties.recurringEventHorizon()).isEqualTo(Period.ofMonths(9));
    }

    @Test
    void create_givenNullInitialization_thenDefaultsToBurstSizeFiveAndOneHourInterval() {
        var relayProperties = new RelayProperties(Duration.ofMinutes(5), List.of(), Period.ofMonths(6), null);

        assertThat(relayProperties.initialization()).isEqualTo(DEFAULT_INITIALIZATION);
    }

    /**
     * Pins the zero-config-migration guarantee at the heart of
     * {@code docs/features/google-calendar-integration.md}'s Design-Entscheidung 1: a
     * {@code relay.calendars[]} entry written before {@code type} existed -- carrying only
     * CalDAV fields -- must bind exactly as it did before this feature, defaulting {@code
     * type} to {@code CALDAV}.
     */
    @Test
    void bind_givenCalendarEntryWithoutTypeField_thenDefaultsToCaldav() {
        var source = new MapConfigurationPropertySource(Map.of(
                "relay.poll-interval",
                "5m",
                "relay.calendars[0].id",
                "personal-nextcloud",
                "relay.calendars[0].caldav-url",
                "https://cloud.example.com/calendars/personal/",
                "relay.calendars[0].caldav-username",
                "user",
                "relay.calendars[0].caldav-password",
                "password",
                "relay.calendars[0].organizer-email",
                "organizer@example.com",
                "relay.calendars[0].attendee-email",
                "business@example.com",
                "relay.calendars[0].from-address",
                "relay@example.com",
                "relay.calendars[0].reply-to-address",
                "organizer@example.com"));
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.calendars()).singleElement().satisfies(
                calendar -> assertThat(calendar.type()).isEqualTo(CalendarSourceType.CALDAV));
    }

    @Test
    void bind_givenCalendarEntryWithTypeGoogle_thenBindsGoogleTypeAndFields() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("relay.poll-interval", "5m"),
                Map.entry("relay.calendars[0].id", "personal-google"),
                Map.entry("relay.calendars[0].type", "google"),
                Map.entry("relay.calendars[0].google-calendar-id", "someone@gmail.com"),
                Map.entry("relay.calendars[0].google-client-id", "client-id"),
                Map.entry("relay.calendars[0].google-client-secret", "client-secret"),
                Map.entry("relay.calendars[0].google-refresh-token", "refresh-token"),
                Map.entry("relay.calendars[0].organizer-email", "organizer@example.com"),
                Map.entry("relay.calendars[0].attendee-email", "business@example.com"),
                Map.entry("relay.calendars[0].from-address", "relay@example.com"),
                Map.entry("relay.calendars[0].reply-to-address", "organizer@example.com")));
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.calendars()).singleElement().satisfies(calendar -> {
            assertThat(calendar.type()).isEqualTo(CalendarSourceType.GOOGLE);
            assertThat(calendar.googleCalendarId()).isEqualTo("someone@gmail.com");
            assertThat(calendar.googleClientId()).isEqualTo("client-id");
            assertThat(calendar.googleClientSecret()).isEqualTo("client-secret");
            assertThat(calendar.googleRefreshToken()).isEqualTo("refresh-token");
        });
    }
}
