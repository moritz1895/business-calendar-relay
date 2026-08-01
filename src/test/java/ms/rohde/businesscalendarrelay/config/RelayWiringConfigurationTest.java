package ms.rohde.businesscalendarrelay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.CalendarReplicaResourceJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.CalendarSyncTokenJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.GoogleCalendarReplicaResourceJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.GoogleCalendarSyncTokenJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.PendingCreationJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.RelayStateJpaRepository;
import ms.rohde.businesscalendarrelay.config.RelayProperties.CalendarConfig.CalendarSourceType;
import ms.rohde.businesscalendarrelay.ports.inbound.PollAndRelaySourceCalendarUseCase;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import ms.rohde.businesscalendarrelay.ports.outbound.BurstBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

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
    private GoogleCalendarReplicaResourceJpaRepository googleCalendarReplicaResourceJpaRepository;

    @Mock
    private GoogleCalendarSyncTokenJpaRepository googleCalendarSyncTokenJpaRepository;

    @Mock
    private BurstBudget burstBudget;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Clock clock = Clock.systemUTC();

    private static final Period RECURRING_EVENT_HORIZON = Period.ofMonths(6);
    private static final RelayProperties.InitializationProperties INITIALIZATION =
            new RelayProperties.InitializationProperties(5, Duration.ofHours(1));

    @Test
    void buildUseCases_givenThreeCalendarConfigs_thenReturnsThreeMappedUseCases() {
        var relayProperties = new RelayProperties(
                Duration.ofMinutes(5),
                List.of(caldavCalendarConfig("calendar-a"), caldavCalendarConfig("calendar-b"), caldavCalendarConfig("calendar-c")),
                RECURRING_EVENT_HORIZON,
                INITIALIZATION);

        var useCases = buildUseCases(relayProperties);

        assertThat(useCases).hasSize(3);
        assertThat(useCases).allSatisfy(
                useCase -> assertThat(useCase).isInstanceOf(PollAndRelaySourceCalendarUseCase.class));
    }

    @Test
    void buildUseCases_givenNoCalendarConfigs_thenReturnsEmptyList() {
        var relayProperties =
                new RelayProperties(Duration.ofMinutes(5), List.of(), RECURRING_EVENT_HORIZON, INITIALIZATION);

        var useCases = buildUseCases(relayProperties);

        assertThat(useCases).isEmpty();
    }

    @Test
    void buildUseCases_givenGoogleCalendarConfig_thenReturnsMappedUseCase() {
        var relayProperties = new RelayProperties(
                Duration.ofMinutes(5), List.of(googleCalendarConfig("calendar-google")), RECURRING_EVENT_HORIZON, INITIALIZATION);

        var useCases = buildUseCases(relayProperties);

        assertThat(useCases).hasSize(1);
        assertThat(useCases).allSatisfy(
                useCase -> assertThat(useCase).isInstanceOf(PollAndRelaySourceCalendarUseCase.class));
    }

    @Test
    void buildUseCases_givenMixedCaldavAndGoogleCalendarConfigs_thenReturnsBothMappedAsUseCases() {
        var relayProperties = new RelayProperties(
                Duration.ofMinutes(5),
                List.of(caldavCalendarConfig("calendar-caldav"), googleCalendarConfig("calendar-google")),
                RECURRING_EVENT_HORIZON,
                INITIALIZATION);

        var useCases = buildUseCases(relayProperties);

        assertThat(useCases).hasSize(2);
    }

    @Test
    void relayProperties_givenNullCalendars_thenDefaultsToEmptyList() {
        var relayProperties = new RelayProperties(Duration.ofMinutes(5), null, RECURRING_EVENT_HORIZON, INITIALIZATION);

        assertThat(relayProperties.calendars()).isEmpty();
    }

    private List<PollAndRelaySourceCalendarUseCase> buildUseCases(RelayProperties relayProperties) {
        return RelayWiringConfiguration.buildUseCases(
                relayProperties,
                httpClient,
                clock,
                blockerSink,
                relayStateJpaRepository,
                pendingCreationJpaRepository,
                calendarReplicaResourceJpaRepository,
                calendarSyncTokenJpaRepository,
                googleCalendarReplicaResourceJpaRepository,
                googleCalendarSyncTokenJpaRepository,
                burstBudget,
                transactionManager);
    }

    private static RelayProperties.CalendarConfig caldavCalendarConfig(String id) {
        return new RelayProperties.CalendarConfig(
                id,
                CalendarSourceType.CALDAV,
                "https://caldav.example.com/" + id + "/",
                "user-" + id,
                "password-" + id,
                null,
                null,
                null,
                null,
                "organizer-" + id + "@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer-" + id + "@example.com",
                true);
    }

    private static RelayProperties.CalendarConfig googleCalendarConfig(String id) {
        return new RelayProperties.CalendarConfig(
                id,
                CalendarSourceType.GOOGLE,
                null,
                null,
                null,
                "calendar-id-" + id + "@group.calendar.google.com",
                "client-id-" + id,
                "client-secret-" + id,
                "refresh-token-" + id,
                "organizer-" + id + "@example.com",
                "business@example.com",
                "relay@example.com",
                "organizer-" + id + "@example.com",
                true);
    }
}
