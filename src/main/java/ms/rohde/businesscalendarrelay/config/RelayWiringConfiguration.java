package ms.rohde.businesscalendarrelay.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Period;
import java.util.List;
import java.util.Objects;
import ms.rohde.businesscalendarrelay.adapters.outbound.caldav.CalDavCalendarSourceAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.google.GoogleCalendarSourceAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.CalendarReplicaResourceJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.CalendarSyncTokenJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.GoogleCalendarReplicaResourceJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.GoogleCalendarSyncTokenJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaCalendarReplicaStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaGoogleCalendarReplicaStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaPendingCreationQueueAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaStateStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.PendingCreationJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.RelayStateJpaRepository;
import ms.rohde.businesscalendarrelay.adapters.outbound.throttling.InMemoryBurstBudgetAdapter;
import ms.rohde.businesscalendarrelay.core.app.PollAndRelaySourceCalendarService;
import ms.rohde.businesscalendarrelay.ports.inbound.PollAndRelaySourceCalendarUseCase;
import ms.rohde.businesscalendarrelay.ports.outbound.BlockerSink;
import ms.rohde.businesscalendarrelay.ports.outbound.BurstBudget;
import ms.rohde.businesscalendarrelay.ports.outbound.CalendarSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Builds one {@link PollAndRelaySourceCalendarUseCase} per calendar declared in
 * {@link RelayProperties#calendars()}, wiring each one's own {@link CalendarSource}
 * adapter (either {@link CalDavCalendarSourceAdapter} or {@link GoogleCalendarSourceAdapter},
 * chosen by {@link RelayProperties.CalendarConfig#type()} -- see {@link #buildUseCase}),
 * {@link JpaStateStoreAdapter}, and {@link JpaPendingCreationQueueAdapter} by hand rather
 * than through Spring dependency injection -- all of these constructors take per-calendar
 * configuration values that no bean of the right type exists for.
 *
 * <p>Every use-case instance shares one {@link HttpClient}, one {@link Clock}, one
 * {@link BlockerSink} bean (the SMTP adapter, which has no per-calendar state), one
 * {@link RelayStateJpaRepository}, one {@link PendingCreationJpaRepository}, one
 * {@link CalendarReplicaResourceJpaRepository}/{@link CalendarSyncTokenJpaRepository} pair
 * (CalDAV replica), one {@link GoogleCalendarReplicaResourceJpaRepository}/
 * {@link GoogleCalendarSyncTokenJpaRepository} pair (Google replica -- see
 * {@code docs/features/google-calendar-integration.md}), and one {@link BurstBudget} bean
 * (the mailbox-wide send budget, shared across every calendar -- see
 * {@code docs/features/burst-filter-initialization.md}, issue #16). The single shared
 * {@link HttpClient} bean is deliberately reused for both CalDAV and Google traffic --
 * {@code java.net.http.HttpClient} is already protocol-agnostic, so no second bean is
 * warranted. See {@link PerCalendarComponentBeanDefinitionPruner} for why
 * {@code @ArchComponentScan} does not also try to register these per-calendar classes as
 * eager Spring singletons.
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
            GoogleCalendarReplicaResourceJpaRepository googleCalendarReplicaResourceJpaRepository,
            GoogleCalendarSyncTokenJpaRepository googleCalendarSyncTokenJpaRepository,
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
                googleCalendarReplicaResourceJpaRepository,
                googleCalendarSyncTokenJpaRepository,
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
            GoogleCalendarReplicaResourceJpaRepository googleCalendarReplicaResourceJpaRepository,
            GoogleCalendarSyncTokenJpaRepository googleCalendarSyncTokenJpaRepository,
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
                        googleCalendarReplicaResourceJpaRepository,
                        googleCalendarSyncTokenJpaRepository,
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
            GoogleCalendarReplicaResourceJpaRepository googleCalendarReplicaResourceJpaRepository,
            GoogleCalendarSyncTokenJpaRepository googleCalendarSyncTokenJpaRepository,
            BurstBudget burstBudget,
            Period recurringEventHorizon,
            PlatformTransactionManager transactionManager) {
        CalendarSource calendarSource = switch (calendar.type()) {
            case CALDAV -> buildCalDavCalendarSource(
                    calendar,
                    httpClient,
                    clock,
                    recurringEventHorizon,
                    calendarReplicaResourceJpaRepository,
                    calendarSyncTokenJpaRepository,
                    transactionManager);
            case GOOGLE -> buildGoogleCalendarSource(
                    calendar,
                    httpClient,
                    clock,
                    recurringEventHorizon,
                    googleCalendarReplicaResourceJpaRepository,
                    googleCalendarSyncTokenJpaRepository,
                    transactionManager);
        };
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

    private static CalDavCalendarSourceAdapter buildCalDavCalendarSource(
            RelayProperties.CalendarConfig calendar,
            HttpClient httpClient,
            Clock clock,
            Period recurringEventHorizon,
            CalendarReplicaResourceJpaRepository calendarReplicaResourceJpaRepository,
            CalendarSyncTokenJpaRepository calendarSyncTokenJpaRepository,
            PlatformTransactionManager transactionManager) {
        var calendarReplicaStore = new JpaCalendarReplicaStoreAdapter(
                calendarReplicaResourceJpaRepository,
                calendarSyncTokenJpaRepository,
                calendar.id(),
                transactionManager);
        // @ConsistentCalendarSourceFields has already rejected any CALDAV entry with a blank
        // caldavUrl/caldavUsername/caldavPassword during Spring Boot property binding, so these
        // @Nullable fields are guaranteed non-null here -- requireNonNull documents that invariant
        // at the point it's relied upon rather than leaving it implicit.
        return new CalDavCalendarSourceAdapter(
                httpClient,
                URI.create(Objects.requireNonNull(calendar.caldavUrl())),
                Objects.requireNonNull(calendar.caldavUsername()),
                Objects.requireNonNull(calendar.caldavPassword()),
                clock,
                recurringEventHorizon,
                calendarReplicaStore,
                calendar.deltaSyncEnabled());
    }

    private static GoogleCalendarSourceAdapter buildGoogleCalendarSource(
            RelayProperties.CalendarConfig calendar,
            HttpClient httpClient,
            Clock clock,
            Period recurringEventHorizon,
            GoogleCalendarReplicaResourceJpaRepository googleCalendarReplicaResourceJpaRepository,
            GoogleCalendarSyncTokenJpaRepository googleCalendarSyncTokenJpaRepository,
            PlatformTransactionManager transactionManager) {
        var googleCalendarReplicaStore = new JpaGoogleCalendarReplicaStoreAdapter(
                googleCalendarReplicaResourceJpaRepository,
                googleCalendarSyncTokenJpaRepository,
                calendar.id(),
                transactionManager);
        // @ConsistentCalendarSourceFields has already rejected any GOOGLE entry with a blank
        // googleCalendarId/googleClientId/googleClientSecret/googleRefreshToken during Spring
        // Boot property binding, so these @Nullable fields are guaranteed non-null here --
        // requireNonNull documents that invariant at the point it's relied upon.
        return new GoogleCalendarSourceAdapter(
                httpClient,
                Objects.requireNonNull(calendar.googleCalendarId()),
                Objects.requireNonNull(calendar.googleClientId()),
                Objects.requireNonNull(calendar.googleClientSecret()),
                Objects.requireNonNull(calendar.googleRefreshToken()),
                clock,
                recurringEventHorizon,
                googleCalendarReplicaStore,
                calendar.deltaSyncEnabled());
    }
}
