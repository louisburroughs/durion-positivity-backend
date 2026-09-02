package com.positivity.referencemock;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit tests enforcing architecture rules for pos-reference-mock.
 *
 * <p>The module keeps the standard layout for uniformity per service-time-sourcing-plan §11
 * ("give it the standard layout anyway ... it's cheap") even though it is a mock vendor with no
 * grant surface. Two fleet rules are deliberately absent: the {@code @PreAuthorize} endpoint rule
 * (the mock simulates an external vendor OUTSIDE the gateway/JWT boundary — plan §10 forbids
 * security here so nothing can mistake mock data for a platform API) and the UUIDv7 entity rules
 * (the module has no database and no entities).
 *
 * <p>Enforces:
 *
 * <ul>
 *   <li>Internal package encapsulation
 *   <li>Controller -&gt; Service layering
 *   <li>No circular dependencies
 * </ul>
 */
@AnalyzeClasses(packages = "com.positivity.referencemock", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

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
    static final ArchRule controllers_should_not_access_entities_directly = noClasses()
            .that()
            .resideInAPackage("..internal.controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.entity..")
            .allowEmptyShould(true)
            .because("controllers should work with DTOs, not entities");

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
    static final ArchRule domain_should_not_depend_on_services_or_web = noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..internal.service..", "..internal.controller..")
            .allowEmptyShould(true)
            .because("fixture domain records should be independent of business logic and web layers");

    @ArchTest
    static final ArchRule spring_boot_application_should_be_in_root_package = classes()
            .that()
            .areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
            .should()
            .resideInAPackage("com.positivity.referencemock")
            .andShould()
            .resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that()
            .resideInAPackage("com.positivity.referencemock.service..")
            .should()
            .bePublic()
            .allowEmptyShould(true)
            .because("service layer is the public API of this module; this mock grants none (plan §10)");

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices().matching(
                    "com.positivity.referencemock.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("cyclic dependencies make modules harder to maintain and evolve");
}
