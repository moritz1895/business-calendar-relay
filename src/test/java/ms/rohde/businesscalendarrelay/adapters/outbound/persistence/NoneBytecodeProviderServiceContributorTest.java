package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;

import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.bytecode.internal.none.BytecodeProviderImpl;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.junit.jupiter.api.Test;

class NoneBytecodeProviderServiceContributorTest {

    @Test
    void contribute_givenRegistryBuilder_thenRegistersNoneBytecodeProvider() {
        StandardServiceRegistryBuilder serviceRegistryBuilder = mock(StandardServiceRegistryBuilder.class);

        new NoneBytecodeProviderServiceContributor().contribute(serviceRegistryBuilder);

        then(serviceRegistryBuilder)
                .should()
                .addService(eq(BytecodeProvider.class), any(BytecodeProviderImpl.class));
    }
}
