package ms.rohde.businesscalendarrelay.config;

import static org.assertj.core.api.Assertions.assertThat;

import ms.rohde.businesscalendarrelay.adapters.outbound.caldav.CalDavCalendarSourceAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.mail.SmtpBlockerSinkAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaCalendarReplicaStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaPendingCreationQueueAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.persistence.JpaStateStoreAdapter;
import ms.rohde.businesscalendarrelay.adapters.outbound.throttling.InMemoryBurstBudgetAdapter;
import ms.rohde.businesscalendarrelay.core.app.PollAndRelaySourceCalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Verifies the fix for the eager-singleton pitfall described in
 * {@link PerCalendarComponentBeanDefinitionPruner}'s Javadoc: an auto-scanned bean
 * definition for a component with a non-Spring-resolvable constructor must be removed,
 * while an unrelated auto-scanned bean definition (a genuinely no-arg-resolvable
 * component) must survive untouched.
 */
class PerCalendarComponentBeanDefinitionPrunerTest {

    @Test
    void postProcessBeanDefinitionRegistry_givenUnresolvableConstructorComponentBeanDefinitions_thenRemovesOnlyThose() {
        var registry = new DefaultListableBeanFactory();
        registry.registerBeanDefinition(
                "pollAndRelaySourceCalendarService", new RootBeanDefinition(PollAndRelaySourceCalendarService.class));
        registry.registerBeanDefinition("jpaStateStoreAdapter", new RootBeanDefinition(JpaStateStoreAdapter.class));
        registry.registerBeanDefinition(
                "jpaPendingCreationQueueAdapter", new RootBeanDefinition(JpaPendingCreationQueueAdapter.class));
        registry.registerBeanDefinition(
                "jpaCalendarReplicaStoreAdapter", new RootBeanDefinition(JpaCalendarReplicaStoreAdapter.class));
        registry.registerBeanDefinition(
                "calDavCalendarSourceAdapter", new RootBeanDefinition(CalDavCalendarSourceAdapter.class));
        registry.registerBeanDefinition(
                "inMemoryBurstBudgetAdapter", new RootBeanDefinition(InMemoryBurstBudgetAdapter.class));
        registry.registerBeanDefinition("smtpBlockerSinkAdapter", new RootBeanDefinition(SmtpBlockerSinkAdapter.class));

        new PerCalendarComponentBeanDefinitionPruner().postProcessBeanDefinitionRegistry(registry);

        assertThat(registry.getBeanDefinitionNames()).containsExactly("smtpBlockerSinkAdapter");
    }

    @Test
    void postProcessBeanDefinitionRegistry_givenNoUnresolvableConstructorComponentBeanDefinitions_thenRemovesNothing() {
        var registry = new DefaultListableBeanFactory();
        registry.registerBeanDefinition("smtpBlockerSinkAdapter", new RootBeanDefinition(SmtpBlockerSinkAdapter.class));

        new PerCalendarComponentBeanDefinitionPruner().postProcessBeanDefinitionRegistry(registry);

        assertThat(registry.getBeanDefinitionNames()).containsExactly("smtpBlockerSinkAdapter");
    }
}
