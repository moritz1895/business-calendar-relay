package ms.rohde.businesscalendarrelay.config;

import java.util.Set;
import ms.rohde.businesscalendarrelay.adapters.outbound.caldav.CalDavCalendarSourceAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaPendingCreationQueueAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaStateStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.throttling.InMemoryBurstBudgetAdapter;
import ms.rohde.businesscalendarrelay.core.app.PollAndRelaySourceCalendarService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

/**
 * Removes the eager-singleton bean definitions that {@code @ArchComponentScan} would
 * otherwise register for classes whose constructors need configuration values (per-
 * calendar {@code String}/{@code URI}, or plain value types like {@code int}/
 * {@code Duration}) rather than Spring-resolvable dependencies.
 *
 * <p>{@code @ArchComponentScan} (declared on {@code BusinessCalendarRelayApplication})
 * is, under the hood, a {@code @ComponentScan} whose include filter matches any class
 * meta-annotated with the hexagonal-arch library's {@code @ArchComponent} -- which both
 * {@code @ApplicationService} and {@code @InfrastructureServiceAdapter} carry. That
 * scan registers a bean definition for every such class purely based on the annotation,
 * regardless of whether the class actually has a no-arg-resolvable constructor.
 *
 * <p>{@link PollAndRelaySourceCalendarService}, {@link JpaStateStoreAdapter}, and
 * {@link JpaPendingCreationQueueAdapter} were never meant to be resolved as Spring beans
 * by type -- {@link RelayWiringConfiguration} constructs one instance of each per
 * configured calendar directly via {@code new}. {@link CalDavCalendarSourceAdapter} is
 * the same story. {@link InMemoryBurstBudgetAdapter} is not per-calendar (exactly one
 * shared instance exists), but hits the identical pitfall for a different reason: its
 * constructor takes {@code int}/{@code Duration} configuration values sourced from
 * {@code RelayProperties.initialization()}, which {@link RelayWiringConfiguration}'s
 * {@code relayBurstBudget} {@code @Bean} factory method already constructs explicitly --
 * an auto-scanned duplicate bean definition for the same class has no {@code int}/
 * {@code Duration} bean to satisfy those constructor parameters with. Left in place, any
 * of these five classes' auto-scanned bean definitions would still be eagerly
 * instantiated during context refresh and fail with an
 * {@code UnsatisfiedDependencyException}.
 *
 * <p>Removing the bean definitions here (rather than e.g. {@code @Lazy} on the classes
 * themselves) keeps that intent honest: nothing in the application context ever
 * requests these types by type, so no bean definition should exist for them at all. It
 * also keeps the four non-shared, per-calendar classes free of any
 * {@code org.springframework.*} annotation -- {@link PollAndRelaySourceCalendarService}
 * in particular lives in {@code core/app}, where {@code CLAUDE.md} forbids Spring
 * dependencies outright.
 */
final class PerCalendarComponentBeanDefinitionPruner implements BeanDefinitionRegistryPostProcessor {

    private static final Logger LOG = LogManager.getLogger(PerCalendarComponentBeanDefinitionPruner.class);

    private static final Set<String> UNRESOLVABLE_CONSTRUCTOR_COMPONENT_CLASS_NAMES = Set.of(
            PollAndRelaySourceCalendarService.class.getName(),
            JpaStateStoreAdapter.class.getName(),
            JpaPendingCreationQueueAdapter.class.getName(),
            CalDavCalendarSourceAdapter.class.getName(),
            InMemoryBurstBudgetAdapter.class.getName());

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        for (var beanName : registry.getBeanDefinitionNames()) {
            var beanClassName = registry.getBeanDefinition(beanName).getBeanClassName();
            if (beanClassName != null && UNRESOLVABLE_CONSTRUCTOR_COMPONENT_CLASS_NAMES.contains(beanClassName)) {
                registry.removeBeanDefinition(beanName);
                LOG.debug(
                        "Removed auto-scanned bean definition '{}' for component with a non-Spring-resolvable"
                                + " constructor: {}",
                        beanName,
                        beanClassName);
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // Pruning already happened in postProcessBeanDefinitionRegistry, before any
        // singleton pre-instantiation could occur.
    }
}
