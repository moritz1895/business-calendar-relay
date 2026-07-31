package ms.rohde.businesscalendarrelay.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Period;
import java.util.List;
import ms.rohde.businesscalendarrelay.adapters.outbound.caldav.CalDavCalendarSourceAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.CalendarReplicaResourceJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.CalendarSyncTokenJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaCalendarReplicaStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaPendingCreationQueueAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaStateStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.PendingCreationJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.RelayStateJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.throttling.InMemoryBurstBudgetAdapter;
import ms.rohde.businesscalendarrelay.core.app.PollAndRelaySourceCalendarService;
import ms.rohde.businesscalendarrelay.ports.inbound.PollAndRelaySourceCalendarUseCase;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import ms.rohde.businesscalendarrelay.ports.outbound.BurstBudget;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Builds one {@link PollAndRelaySourceCalendarUseCase} per calendar declared in
 * {@link RelayProperties#calendars()}, wiring each one's own
 * {@link CalDavCalendarSourceAdapter}, {@link JpaStateStoreAdapter}, and
 * {@link JpaPendingCreationQueueAdapter} by hand rather than through Spring dependency
 * injection -- all three constructors take per-calendar configuration values that no bean
 * of the right type exists for.
 *
 * <p>Every use-case instance shares one {@link HttpClient}, one {@link Clock}, one
 * {@link BlockerSink} bean (the SMTP adapter, which has no per-calendar state), one
 * {@link RelayStateJpaRepository}, one {@link PendingCreationJpaRepository}, one
 * {@link CalendarReplicaResourceJpaRepository}, one {@link CalendarSyncTokenJpaRepository},
 * and one {@link BurstBudget} bean (the mailbox-wide send budget, shared across every
 * calendar -- see {@code docs/features/burst-filter-initialization.md}, issue #16). See
 * {@link PerCalendarComponentBeanDefinitionPruner} for why {@code @ArchComponentScan}
 * does not also try to register these per-calendar classes as eager Spring singletons.
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
    BurstBudget relayBurstBudget(Clock relayClock, RelayProperties relayProperties) {
        var initialization = relayProperties.initialization();
        return new InMemoryBurstBudgetAdapter(relayClock, initialization.burstSize(), initialization.burstInterval());
    }

    @Bean
    List<PollAndRelaySourceCalendarUseCase> pollAndRelaySourceCalendarUseCases(
            RelayProperties relayProperties,
            HttpClient relayCalDavHttpClient,
            Clock relayClock,
            BlockerSink blockerSink,
            RelayStateJpaRepository relayStateJpaRepository,
            PendingCreationJpaRepository pendingCreationJpaRepository,
            CalendarReplicaResourceJpaRepository calendarReplicaResourceJpaRepository,
            CalendarSyncTokenJpaRepository calendarSyncTokenJpaRepository,
            BurstBudget relayBurstBudget,
            PlatformTransactionManager transactionManager) {
        return buildUseCases(
                relayProperties,
                relayCalDavHttpClient,
                relayClock,
                blockerSink,
                relayStateJpaRepository,
                pendingCreationJpaRepository,
                calendarReplicaResourceJpaRepository,
                calendarSyncTokenJpaRepository,
                relayBurstBudget,
                transactionManager);
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
            RelayStateJpaRepository relayStateJpaRepository,
            PendingCreationJpaRepository pendingCreationJpaRepository,
            CalendarReplicaResourceJpaRepository calendarReplicaResourceJpaRepository,
            CalendarSyncTokenJpaRepository calendarSyncTokenJpaRepository,
            BurstBudget burstBudget,
            PlatformTransactionManager transactionManager) {
        var recurringEventHorizon = relayProperties.recurringEventHorizon();
        return relayProperties.calendars().stream()
                .map(calendar -> buildUseCase(
                        calendar,
                        httpClient,
                        clock,
                        blockerSink,
                        relayStateJpaRepository,
                        pendingCreationJpaRepository,
                        calendarReplicaResourceJpaRepository,
                        calendarSyncTokenJpaRepository,
                        burstBudget,
                        recurringEventHorizon,
                        transactionManager))
                .toList();
    }

    private static PollAndRelaySourceCalendarUseCase buildUseCase(
            RelayProperties.CalendarConfig calendar,
            HttpClient httpClient,
            Clock clock,
            BlockerSink blockerSink,
            RelayStateJpaRepository relayStateJpaRepository,
            PendingCreationJpaRepository pendingCreationJpaRepository,
            CalendarReplicaResourceJpaRepository calendarReplicaResourceJpaRepository,
            CalendarSyncTokenJpaRepository calendarSyncTokenJpaRepository,
            BurstBudget burstBudget,
            Period recurringEventHorizon,
            PlatformTransactionManager transactionManager) {
        var calendarReplicaStore = new JpaCalendarReplicaStoreAdapter(
                calendarReplicaResourceJpaRepository,
                calendarSyncTokenJpaRepository,
                calendar.id(),
                transactionManager);
        var calendarSource = new CalDavCalendarSourceAdapter(
                httpClient,
                URI.create(calendar.caldavUrl()),
                calendar.caldavUsername(),
                calendar.caldavPassword(),
                clock,
                recurringEventHorizon,
                calendarReplicaStore,
                calendar.deltaSyncEnabled());
        var stateStore = new JpaStateStoreAdapter(relayStateJpaRepository, calendar.id());
        var pendingCreationQueue = new JpaPendingCreationQueueAdapter(pendingCreationJpaRepository, calendar.id());

        return new PollAndRelaySourceCalendarService(
                calendarSource,
                blockerSink,
                stateStore,
                pendingCreationQueue,
                burstBudget,
                calendar.organizerEmail(),
                calendar.attendeeEmail(),
                calendar.fromAddress(),
                calendar.replyToAddress(),
                clock,
                recurringEventHorizon);
    }
}
