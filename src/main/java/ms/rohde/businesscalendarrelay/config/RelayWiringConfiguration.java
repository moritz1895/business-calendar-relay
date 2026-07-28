package ms.rohde.businesscalendarrelay.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import ms.rohde.businesscalendarrelay.adapters.outbound.caldav.CalDavCalendarSourceAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaStateStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.RelayStateJpaRepository;
import ms.rohde.businesscalendarrelay.core.app.PollAndRelaySourceCalendarService;
import ms.rohde.businesscalendarrelay.ports.inbound.PollAndRelaySourceCalendarUseCase;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Builds one {@link PollAndRelaySourceCalendarUseCase} per calendar declared in
 * {@link RelayProperties#calendars()}, wiring each one's own
 * {@link CalDavCalendarSourceAdapter} and {@link JpaStateStoreAdapter} by hand rather
 * than through Spring dependency injection -- both constructors take per-calendar
 * configuration values that no bean of the right type exists for.
 *
 * <p>Every use-case instance shares one {@link HttpClient}, one {@link Clock}, one
 * {@link BlockerSink} bean (the SMTP adapter, which has no per-calendar state), and one
 * {@link RelayStateJpaRepository}. See {@link PerCalendarComponentBeanDefinitionPruner}
 * for why {@code @ArchComponentScan} does not also try to register these per-calendar
 * classes as eager Spring singletons.
 */
@Configuration
@EnableConfigurationProperties(RelayProperties.class)
public class RelayWiringConfiguration {

    private static final int MINIMUM_TASK_SCHEDULER_POOL_SIZE = 1;

    @Bean
    HttpClient relayCalDavHttpClient() {
        return HttpClient.newHttpClient();
    }

    @Bean
    Clock relayClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    TaskScheduler relayTaskScheduler(RelayProperties relayProperties) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(MINIMUM_TASK_SCHEDULER_POOL_SIZE, relayProperties.calendars().size()));
        scheduler.setThreadNamePrefix("relay-poll-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    List<PollAndRelaySourceCalendarUseCase> pollAndRelaySourceCalendarUseCases(
            RelayProperties relayProperties,
            HttpClient relayCalDavHttpClient,
            Clock relayClock,
            BlockerSink blockerSink,
            RelayStateJpaRepository relayStateJpaRepository) {
        return buildUseCases(relayProperties, relayCalDavHttpClient, relayClock, blockerSink, relayStateJpaRepository);
    }

    /**
     * Pure mapping from configuration to use-case instances, kept as a static method so
     * it can be unit-tested as a plain function without booting a Spring context.
     */
    static List<PollAndRelaySourceCalendarUseCase> buildUseCases(
            RelayProperties relayProperties,
            HttpClient httpClient,
            Clock clock,
            BlockerSink blockerSink,
            RelayStateJpaRepository relayStateJpaRepository) {
        return relayProperties.calendars().stream()
                .map(calendar -> buildUseCase(calendar, httpClient, clock, blockerSink, relayStateJpaRepository))
                .toList();
    }

    private static PollAndRelaySourceCalendarUseCase buildUseCase(
            RelayProperties.CalendarConfig calendar,
            HttpClient httpClient,
            Clock clock,
            BlockerSink blockerSink,
            RelayStateJpaRepository relayStateJpaRepository) {
        var calendarSource = new CalDavCalendarSourceAdapter(
                httpClient, URI.create(calendar.caldavUrl()), calendar.caldavUsername(), calendar.caldavPassword());
        var stateStore = new JpaStateStoreAdapter(relayStateJpaRepository, calendar.id());

        return new PollAndRelaySourceCalendarService(
                calendarSource,
                blockerSink,
                stateStore,
                calendar.organizerEmail(),
                calendar.attendeeEmail(),
                calendar.fromAddress(),
                calendar.replyToAddress(),
                clock);
    }
}
