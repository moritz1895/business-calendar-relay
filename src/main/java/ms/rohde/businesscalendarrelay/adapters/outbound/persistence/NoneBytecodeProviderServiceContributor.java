package ms.rohde.businesscalendarrelay.adapters.outbound.persistence;

import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.bytecode.internal.none.BytecodeProviderImpl;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.service.spi.ServiceContributor;

/**
 * Hands Hibernate the no-op {@link BytecodeProvider} directly, bypassing its
 * {@code ServiceLoader}-based default (ByteBuddy). This application's entities carry no
 * {@code @ManyToOne}/{@code @OneToOne}/{@code @OneToMany} associations, so neither
 * ByteBuddy's proxy factories nor its reflection-optimizer fast accessors are needed; both
 * are pure unconditional Hibernate bootstrap overhead here, and the latter generates classes
 * at runtime, which GraalVM native-image forbids by default. The test suite's
 * {@code NoLazyJpaAssociationsTest} guards the no-lazy-association assumption this relies on.
 *
 * <p>{@link BytecodeProviderImpl} lives in Hibernate's {@code .internal.none} package, not
 * its {@code .spi} contract — an upgrade could rename or remove it without a deprecation
 * cycle; this class's own unit test and the full context-startup test in this suite exist to
 * catch that immediately rather than at first production JPA bootstrap.
 */
public final class NoneBytecodeProviderServiceContributor implements ServiceContributor {

    @Override
    public void contribute(StandardServiceRegistryBuilder serviceRegistryBuilder) {
        serviceRegistryBuilder.addService(BytecodeProvider.class, new BytecodeProviderImpl());
    }
}
