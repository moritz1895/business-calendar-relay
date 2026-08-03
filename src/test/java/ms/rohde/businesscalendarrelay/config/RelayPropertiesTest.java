package ms.rohde.businesscalendarrelay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Period;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig.CalendarSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Verifies {@code relay.recurring-event-horizon}, {@code relay.initialization}, the global
 * iMIP identity fields, and {@code relay.google-credentials}' binding behaviour
 * specifically -- default values when absent from configuration, and correct parsing when
 * present -- via {@link Binder} directly rather than booting a Spring context, mirroring
 * how {@link RelayWiringConfigurationTest} keeps {@code RelayWiringConfiguration}'s
 * config-to-use-case mapping testable without one.
 */
class RelayPropertiesTest {

    private static final RelayProperties.InitializationProperties DEFAULT_INITIALIZATION =
            new RelayProperties.InitializationProperties(5, Duration.ofHours(1));

    private static RelayProperties relayProperties(
            List<RelayProperties.CalendarConfig> calendars, List<RelayProperties.GoogleCredentials> googleCredentials) {
        return new RelayProperties(
                Duration.ofMinutes(5),
                calendars,
                Period.ofMonths(6),
                DEFAULT_INITIALIZATION,
                "organizer@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer@example.com",
                googleCredentials);
    }

    @Test
    void bind_givenRecurringEventHorizonAbsentFromConfiguration_thenDefaultsToSixMonths() {
        var source = new MapConfigurationPropertySource(baseProperties());
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.recurringEventHorizon()).isEqualTo(Period.ofMonths(6));
    }

    @Test
    void bind_givenRecurringEventHorizonConfigured_thenBindsConfiguredPeriod() {
        var properties = baseProperties();
        properties.put("relay.recurring-event-horizon", "P3M");
        var source = new MapConfigurationPropertySource(properties);
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.recurringEventHorizon()).isEqualTo(Period.ofMonths(3));
    }

    @Test
    void bind_givenInitializationAbsentFromConfiguration_thenDefaultsToBurstSizeFiveAndOneHourInterval() {
        var source = new MapConfigurationPropertySource(baseProperties());
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.initialization().burstSize()).isEqualTo(5);
        assertThat(relayProperties.initialization().burstInterval()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void bind_givenInitializationConfigured_thenBindsConfiguredBurstSizeAndInterval() {
        var properties = baseProperties();
        properties.put("relay.initialization.burst-size", "10");
        properties.put("relay.initialization.burst-interval", "PT30M");
        var source = new MapConfigurationPropertySource(properties);
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.initialization().burstSize()).isEqualTo(10);
        assertThat(relayProperties.initialization().burstInterval()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void create_givenExplicitRecurringEventHorizon_thenRelayPropertiesCarriesIt() {
        var relayProperties = new RelayProperties(
                Duration.ofMinutes(5),
                List.of(),
                Period.ofMonths(9),
                DEFAULT_INITIALIZATION,
                "organizer@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer@example.com",
                List.of());

        assertThat(relayProperties.recurringEventHorizon()).isEqualTo(Period.ofMonths(9));
    }

    @Test
    void create_givenNullInitialization_thenDefaultsToBurstSizeFiveAndOneHourInterval() {
        var relayProperties = new RelayProperties(
                Duration.ofMinutes(5),
                List.of(),
                Period.ofMonths(6),
                null,
                "organizer@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer@example.com",
                List.of());

        assertThat(relayProperties.initialization()).isEqualTo(DEFAULT_INITIALIZATION);
    }

    @Test
    void create_givenNullGoogleCredentials_thenDefaultsToEmptyList() {
        var relayProperties = relayProperties(List.of(), null);

        assertThat(relayProperties.googleCredentials()).isEmpty();
    }

    @Test
    void create_givenEmptyGoogleCredentialsListAndNoGoogleCalendars_thenValid() {
        var relayProperties = relayProperties(List.of(), List.of());

        assertThat(relayProperties.googleCredentials()).isEmpty();
        assertThat(relayProperties.calendars()).isEmpty();
    }

    @Test
    void bind_givenGoogleCredentialsConfigured_thenBindsIdClientIdClientSecretAndRefreshToken() {
        var properties = baseProperties();
        properties.put("relay.google-credentials[0].id", "personal-google-account");
        properties.put("relay.google-credentials[0].client-id", "client-id");
        properties.put("relay.google-credentials[0].client-secret", "client-secret");
        properties.put("relay.google-credentials[0].refresh-token", "refresh-token");
        var source = new MapConfigurationPropertySource(properties);
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.googleCredentials()).singleElement().satisfies(credentials -> {
            assertThat(credentials.id()).isEqualTo("personal-google-account");
            assertThat(credentials.clientId()).isEqualTo("client-id");
            assertThat(credentials.clientSecret()).isEqualTo("client-secret");
            assertThat(credentials.refreshToken()).isEqualTo("refresh-token");
        });
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
        var properties = baseProperties();
        properties.put("relay.calendars[0].id", "personal-nextcloud");
        properties.put("relay.calendars[0].caldav-url", "https://cloud.example.com/calendars/personal/");
        properties.put("relay.calendars[0].caldav-username", "user");
        properties.put("relay.calendars[0].caldav-password", "password");
        var source = new MapConfigurationPropertySource(properties);
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.calendars()).singleElement().satisfies(
                calendar -> assertThat(calendar.type()).isEqualTo(CalendarSourceType.CALDAV));
    }

    @Test
    void bind_givenCalendarEntryWithTypeGoogle_thenBindsGoogleTypeAndFields() {
        var properties = baseProperties();
        properties.put("relay.calendars[0].id", "personal-google");
        properties.put("relay.calendars[0].type", "google");
        properties.put("relay.calendars[0].google-calendar-id", "someone@gmail.com");
        properties.put("relay.calendars[0].google-credentials-id", "personal-google-account");
        var source = new MapConfigurationPropertySource(properties);
        var binder = new Binder(source);

        var relayProperties = binder.bind("relay", RelayProperties.class)
                .orElseThrow(() -> new IllegalStateException("relay properties failed to bind"));

        assertThat(relayProperties.calendars()).singleElement().satisfies(calendar -> {
            assertThat(calendar.type()).isEqualTo(CalendarSourceType.GOOGLE);
            assertThat(calendar.googleCalendarId()).isEqualTo("someone@gmail.com");
            assertThat(calendar.googleCredentialsId()).isEqualTo("personal-google-account");
        });
    }

    private static Map<String, String> baseProperties() {
        var properties = new HashMap<String, String>();
        properties.put("relay.poll-interval", "5m");
        properties.put("relay.organizer-email", "organizer@example.com");
        properties.put("relay.attendee-email", "business@example.com");
        properties.put("relay.from-address", "relay@example.com");
        properties.put("relay.reply-to-address", "organizer@example.com");
        return properties;
    }
}
