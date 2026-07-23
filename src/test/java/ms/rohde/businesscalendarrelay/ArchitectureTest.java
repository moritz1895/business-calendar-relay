package ms.rohde.businesscalendarrelay;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import ms.rohde.hexagonalarch.archunit.HexagonalArchitectureRules;

@AnalyzeClasses(packages = "ms.rohde.businesscalendarrelay")
class ArchitectureTest {

    @ArchTest
    static final ArchRule drivingAdaptersMustNotDependOnApplicationServices =
            HexagonalArchitectureRules.drivingAdaptersMustNotDependOnApplicationServices();

    @ArchTest
    static final ArchRule applicationServicesMustNotDependOnDrivingAdapters =
            HexagonalArchitectureRules.applicationServicesMustNotDependOnDrivingAdapters();

    @ArchTest
    static final ArchRule applicationServicesMustNotDependOnInfrastructureAdapters =
            HexagonalArchitectureRules.applicationServicesMustNotDependOnInfrastructureAdapters();

    @ArchTest
    static final ArchRule domainModelMustNotDependOnApplicationServices =
            HexagonalArchitectureRules.domainModelMustNotDependOnApplicationServices();

    @ArchTest
    static final ArchRule domainModelMustNotDependOnAdapters =
            HexagonalArchitectureRules.domainModelMustNotDependOnAdapters();

    @ArchTest
    static final ArchRule drivingPortsMustBeInterfaces =
            HexagonalArchitectureRules.drivingPortsMustBeInterfaces();

    @ArchTest
    static final ArchRule infrastructureServicePortsMustBeInterfaces =
            HexagonalArchitectureRules.infrastructureServicePortsMustBeInterfaces();
}
