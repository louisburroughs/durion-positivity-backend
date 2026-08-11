package com.positivity.supplier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit tests enforcing architecture rules for the pos-supplier module.
 *
 * <p>Enforces, beyond the platform-wide rules in pos-archunit:
 *
 * <ul>
 *   <li>ADR-0051 §1/§2 — canonical model ({@code internal.domain..}) and capability ports
 *       ({@code internal.spi..}) stay framework-clean (no Spring/JPA/web types), and adapters
 *       depend on spi/domain, never the reverse
 *   <li>ADR-0026 — only the {@code service} contract interfaces and the application class
 *       live outside {@code internal}
 * </ul>
 */
@AnalyzeClasses(packages = "com.positivity.supplier", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final String[] FRAMEWORK_PACKAGES = {
        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..", "org.hibernate.."
    };

    @ArchTest
    static final ArchRule canonical_model_should_be_framework_clean = noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FRAMEWORK_PACKAGES)
            .allowEmptyShould(true)
            .because("the canonical supplier model is framework-clean by mandate (ADR-0051 §2)");

    @ArchTest
    static final ArchRule spi_ports_should_be_framework_clean = noClasses()
            .that()
            .resideInAPackage("..internal.spi..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FRAMEWORK_PACKAGES)
            .allowEmptyShould(true)
            .because("capability ports are framework-clean by mandate (ADR-0051 §2)");

    @ArchTest
    static final ArchRule spi_and_domain_should_not_depend_on_adapters = noClasses()
            .that()
            .resideInAnyPackage("..internal.spi..", "..internal.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.adapter..")
            .allowEmptyShould(true)
            .because("adapters depend on internal.spi and internal.domain, never the reverse (ADR-0051 §2)");

    @ArchTest
    static final ArchRule adapters_should_not_depend_on_registry_or_web_or_persistence = noClasses()
            .that()
            .resideInAPackage("..internal.adapter..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..internal.registry..",
                    "..internal.controller..",
                    "..internal.repository..",
                    "..internal.entity..")
            .allowEmptyShould(true)
            .because("adapter code may depend on internal.spi and internal.domain model types only (ADR-0051 §2)");

    @ArchTest
    static final ArchRule controllers_should_not_access_repositories_directly = noClasses()
            .that()
            .resideInAPackage("..internal.controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.repository..")
            .allowEmptyShould(true)
            .because("controllers must go through service layer");

    @ArchTest
    static final ArchRule services_should_not_depend_on_controllers = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.controller..")
            .allowEmptyShould(true)
            .because("services should not depend on web layer");

    @ArchTest
    static final ArchRule spring_boot_application_should_be_in_root_package = classes()
            .that()
            .areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
            .should()
            .resideInAPackage("com.positivity.supplier")
            .andShould()
            .resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that()
            .resideInAPackage("com.positivity.supplier.service..")
            .and()
            .doNotHaveSimpleName("package-info")
            .should()
            .bePublic()
            .allowEmptyShould(true)
            .because("service layer is the public API of this module (ADR-0026)");

    @ArchTest
    static final ArchRule service_root_package_should_contain_only_interfaces = classes()
            .that()
            .resideInAPackage("com.positivity.supplier.service")
            .should()
            .beInterfaces()
            .allowEmptyShould(true)
            .because("com.positivity.supplier.service is the contract-only interface surface (ADR-0026);"
                    + " contract DTO records live in service.model, mirroring the pos-invoice convention");

    @ArchTest
    static final ArchRule service_model_should_contain_only_records_and_enums = classes()
            .that()
            .resideInAPackage("com.positivity.supplier.service.model")
            .and()
            .doNotHaveSimpleName("package-info")
            .should()
            .beRecords()
            .orShould()
            .beEnums()
            .allowEmptyShould(true)
            .because("service.model carries immutable contract DTOs (records/enums) only,"
                    + " mirroring the pos-invoice service.model convention (ADR-0026)");

    @ArchTest
    static final ArchRule service_contract_should_not_depend_on_internal = noClasses()
            .that()
            .resideInAPackage("com.positivity.supplier.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.supplier.internal..")
            .allowEmptyShould(true)
            .because("the service contract surface is the cross-module public API and must not leak"
                    + " internal canonical-model or implementation types (ADR-0026, ADR-0049 §4)");

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices().matching(
                    "com.positivity.supplier.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("cyclic dependencies make modules harder to maintain and evolve");

    /**
     * Inverse encapsulation rule (slice-1 review debt): nothing may live outside
     * {@code internal..} and {@code com.positivity.supplier.service..} except the
     * {@code @SpringBootApplication} shell and package-info markers — otherwise
     * implementation types silently become public API (ADR-0026).
     */
    @ArchTest
    static final ArchRule nothing_outside_internal_and_service_but_the_application_shell = classes()
            .that()
            .resideOutsideOfPackages("com.positivity.supplier.internal..", "com.positivity.supplier.service..")
            .should()
            .beAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
            .orShould()
            .haveSimpleName("package-info")
            .allowEmptyShould(true)
            .because("only the application shell and package-info markers may exist outside internal.."
                    + " and the service.. contract surface (ADR-0026, slice-1 review)");
}
