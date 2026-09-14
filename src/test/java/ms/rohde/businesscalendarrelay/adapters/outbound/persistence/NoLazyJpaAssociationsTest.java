package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Basic;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the invariant {@link NoneBytecodeProviderServiceContributor} relies on:
 * {@code org.hibernate.bytecode.internal.none.BytecodeProviderImpl} refuses to build lazy
 * proxies at all, so a lazy association or explicitly lazy basic field would fail at
 * Hibernate bootstrap rather than at compile time. Fails loudly here instead, the moment
 * such a field is added to any of this application's {@code @Entity} classes.
 */
class NoLazyJpaAssociationsTest {

    private static final List<Class<?>> ENTITY_CLASSES = List.of(
            CalendarReplicaResourceEntity.class,
            CalendarSyncTokenEntity.class,
            GoogleCalendarReplicaResourceEntity.class,
            GoogleCalendarSyncTokenEntity.class,
            PendingCreationEntity.class,
            RelayStateEntity.class);

    @Test
    void entities_givenAnyEntityClass_thenDeclareNoLazyAssociationOrLazyBasicField() {
        for (Class<?> entityClass : ENTITY_CLASSES) {
            for (Field field : entityClass.getDeclaredFields()) {
                assertThat(field.isAnnotationPresent(OneToMany.class))
                        .as("%s.%s must not be a @OneToMany association", entityClass.getSimpleName(), field.getName())
                        .isFalse();
                assertThat(field.isAnnotationPresent(ManyToOne.class))
                        .as("%s.%s must not be a @ManyToOne association", entityClass.getSimpleName(), field.getName())
                        .isFalse();
                assertThat(field.isAnnotationPresent(OneToOne.class))
                        .as("%s.%s must not be a @OneToOne association", entityClass.getSimpleName(), field.getName())
                        .isFalse();
                assertThat(field.isAnnotationPresent(ManyToMany.class))
                        .as("%s.%s must not be a @ManyToMany association", entityClass.getSimpleName(), field.getName())
                        .isFalse();

                Basic basic = field.getAnnotation(Basic.class);
                if (basic != null) {
                    assertThat(basic.fetch())
                            .as("%s.%s must not be an explicitly lazy @Basic field", entityClass.getSimpleName(), field.getName())
                            .isEqualTo(FetchType.EAGER);
                }
            }
        }
    }
}
