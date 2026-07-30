package ms.rohde.businesscalendarrelay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Map;
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
}
