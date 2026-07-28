package ms.rohde.businesscalendarrelay.config;

import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PerCalendarComponentBeanDefinitionPruner} as a Spring bean.
 *
 * <p>Kept as its own tiny {@code @Configuration} class, separate from
 * {@link RelayWiringConfiguration}, so the {@code @Bean} factory method can stay
 * {@code static} without constraining the rest of the wiring configuration. A
 * {@code static} factory method for a {@link BeanDefinitionRegistryPostProcessor} bean
 * avoids forcing early instantiation of its declaring {@code @Configuration} class,
 * which is the documented Spring pattern for this kind of bean.
 */
@Configuration
public class PerCalendarComponentPruningConfiguration {

    @Bean
    static BeanDefinitionRegistryPostProcessor perCalendarComponentBeanDefinitionPruner() {
        return new PerCalendarComponentBeanDefinitionPruner();
    }
}
