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
 * Verifies {@code relay.recurring-event-horizon}'s binding behaviour specifically --
 * default value when absent from configuration, and correct {@link Period} parsing when
 * present -- via {@link Binder} directly rather than booting a Spring context, mirroring
 * how {@link RelayWiringConfigurationTest} keeps {@code RelayWiringConfiguration}'s
 * config-to-use-case mapping testable without one.
 */
class RelayPropertiesTest {

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
    void create_givenExplicitRecurringEventHorizon_thenRelayPropertiesCarriesIt() {
        var relayProperties = new RelayProperties(Duration.ofMinutes(5), List.of(), Period.ofMonths(9));

        assertThat(relayProperties.recurringEventHorizon()).isEqualTo(Period.ofMonths(9));
    }
}
