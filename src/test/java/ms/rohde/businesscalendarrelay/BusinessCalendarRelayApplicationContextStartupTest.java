package ms.rohde.businesscalendarrelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.List;
import ms.rohde.businesscalendarrelay.adapters.inbound.scheduling.PollAndRelaySchedulerAdapter;
import ms.rohde.businesscalendarrelay.ports.inbound.PollAndRelaySourceCalendarUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the full application context -- the first test in this stack to actually do so
 * with {@code @ArchComponentScan} active. Proves two things at once:
 *
 * <ul>
 *   <li>{@code relay.calendars} binds correctly and {@code RelayWiringConfiguration}
 *       exposes exactly one {@link PollAndRelaySourceCalendarUseCase} bean per
 *       configured calendar.
 *   <li>The fix in {@code config.PerCalendarComponentBeanDefinitionPruner} actually
 *       works: if it didn't, context refresh would fail with an
 *       {@code UnsatisfiedDependencyException} while eagerly instantiating the
 *       auto-scanned {@code PollAndRelaySourceCalendarService} /
 *       {@code JpaStateStoreAdapter} / {@code CalDavCalendarSourceAdapter} bean
 *       definitions, since none of their constructors are Spring-resolvable.
 * </ul>
 *
 * <p>The real {@link TaskScheduler} bean is replaced with a Mockito mock so that
 * {@link PollAndRelaySchedulerAdapter}'s {@code ApplicationReadyEvent} handler -- which
 * fires for real during a {@code @SpringBootTest} boot -- never actually executes a poll
 * cycle. Without this, the test would attempt a real CalDAV HTTP call against the fake
 * URLs in {@code application-test.yml}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BusinessCalendarRelayApplicationContextStartupTest {

    @MockitoBean
    private TaskScheduler taskScheduler;

    @Autowired
    private List<PollAndRelaySourceCalendarUseCase> pollAndRelaySourceCalendarUseCases;

    @Test
    void contextLoads_givenTwoConfiguredCalendars_thenExposesTwoUseCaseBeansAndSchedulesEachOnce() {
        assertThat(pollAndRelaySourceCalendarUseCases).hasSize(2);

        then(taskScheduler).should(times(2)).scheduleWithFixedDelay(any(), eq(Duration.ofHours(1)));
    }
}
