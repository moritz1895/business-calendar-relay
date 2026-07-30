package ms.rohde.businesscalendarrelay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.CalendarReplicaResourceJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.CalendarSyncTokenJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.PendingCreationJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.RelayStateJpaRepository;
import ms.rohde.businesscalendarrelay.ports.inbound.PollAndRelaySourceCalendarUseCase;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import ms.rohde.businesscalendarrelay.ports.outbound.BurstBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RelayWiringConfiguration#buildUseCases} is deliberately a plain static method
 * so this config-to-use-case mapping can be verified without booting a Spring context --
 * the actual Spring wiring (bean resolution, eager-singleton avoidance) is covered
 * separately by {@code BusinessCalendarRelayApplicationContextStartupTest}.
 */
@ExtendWith(MockitoExtension.class)
class RelayWiringConfigurationTest {

    @Mock
    private BlockerSink blockerSink;

    @Mock
    private RelayStateJpaRepository relayStateJpaRepository;

    @Mock
    private PendingCreationJpaRepository pendingCreationJpaRepository;

    @Mock
    private CalendarReplicaResourceJpaRepository calendarReplicaResourceJpaRepository;

    @Mock
    private CalendarSyncTokenJpaRepository calendarSyncTokenJpaRepository;

    @Mock
    private BurstBudget burstBudget;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Clock clock = Clock.systemUTC();

    private static final Period RECURRING_EVENT_HORIZON = Period.ofMonths(6);
    private static final RelayProperties.InitializationProperties INITIALIZATION =
            new RelayProperties.InitializationProperties(5, Duration.ofHours(1));

    @Test
    void buildUseCases_givenThreeCalendarConfigs_thenReturnsThreeMappedUseCases() {
        var relayProperties = new RelayProperties(
                Duration.ofMinutes(5),
                List.of(calendarConfig("calendar-a"), calendarConfig("calendar-b"), calendarConfig("calendar-c")),
                RECURRING_EVENT_HORIZON,
                INITIALIZATION);

        var useCases = RelayWiringConfiguration.buildUseCases(
                relayProperties,
                httpClient,
                clock,
                blockerSink,
                relayStateJpaRepository,
                pendingCreationJpaRepository,
                calendarReplicaResourceJpaRepository,
                calendarSyncTokenJpaRepository,
                burstBudget);

        assertThat(useCases).hasSize(3);
        assertThat(useCases).allSatisfy(
                useCase -> assertThat(useCase).isInstanceOf(PollAndRelaySourceCalendarUseCase.class));
    }

    @Test
    void buildUseCases_givenNoCalendarConfigs_thenReturnsEmptyList() {
        var relayProperties =
                new RelayProperties(Duration.ofMinutes(5), List.of(), RECURRING_EVENT_HORIZON, INITIALIZATION);

        var useCases = RelayWiringConfiguration.buildUseCases(
                relayProperties,
                httpClient,
                clock,
                blockerSink,
                relayStateJpaRepository,
                pendingCreationJpaRepository,
                calendarReplicaResourceJpaRepository,
                calendarSyncTokenJpaRepository,
                burstBudget);

        assertThat(useCases).isEmpty();
    }

    @Test
    void relayProperties_givenNullCalendars_thenDefaultsToEmptyList() {
        var relayProperties = new RelayProperties(Duration.ofMinutes(5), null, RECURRING_EVENT_HORIZON, INITIALIZATION);

        assertThat(relayProperties.calendars()).isEmpty();
    }

    private static RelayProperties.CalendarConfig calendarConfig(String id) {
        return new RelayProperties.CalendarConfig(
                id,
                "https://caldav.example.com/" + id + "/",
                "user-" + id,
                "password-" + id,
                "organizer-" + id + "@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer-" + id + "@example.com",
                true);
    }
}
