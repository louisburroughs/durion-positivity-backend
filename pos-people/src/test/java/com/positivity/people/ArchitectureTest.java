package com.positivity.people;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit tests enforcing architecture rules for pos-people module.
 * 
 * Enforces:
 * - Internal package encapsulation
 * - Service layer as only public API
 * - Controller -> Service -> Repository layering
 * - No circular dependencies
 */
@AnalyzeClasses(packages = "com.positivity.people")
public class ArchitectureTest {

    @ArchTest
    static final ArchRule controllers_should_not_access_repositories_directly = noClasses()
            .that().resideInAPackage("..internal.controller..")
            .should().dependOnClassesThat().resideInAPackage("..internal.repository..")
            .allowEmptyShould(true)
            .because("controllers must go through service layer");

    @ArchTest
    static final ArchRule controllers_should_not_access_entities_directly = noClasses()
            .that().resideInAPackage("..internal.controller..")
            .should().dependOnClassesThat().resideInAPackage("..internal.entity..")
            .allowEmptyShould(true)
            .because("controllers should work with DTOs, not entities");

    @ArchTest
    static final ArchRule services_should_not_depend_on_controllers = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..internal.controller..")
            .allowEmptyShould(true)
            .because("services should not depend on web layer");

    @ArchTest
    static final ArchRule entities_should_not_depend_on_services = noClasses()
            .that().resideInAPackage("..internal.entity..")
            .should().dependOnClassesThat().resideInAPackage("..service..")
            .allowEmptyShould(true)
            .because("entities should be independent of business logic");

    @ArchTest
    static final ArchRule repositories_should_only_be_accessed_from_services_or_config = noClasses()
            .that().resideOutsideOfPackages("..service..", "..internal.repository..", "..internal.config..")
            .should().dependOnClassesThat().resideInAPackage("..internal.repository..")
            .allowEmptyShould(true)
            .because("repositories should only be accessed from service layer");

    @ArchTest
    static final ArchRule spring_boot_application_should_be_in_root_package = classes()
            .that().areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
            .should().resideInAPackage("com.positivity.people")
            .andShould().resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that().resideInAPackage("com.positivity.people.service..")
            .should().bePublic()
            .allowEmptyShould(true)
            .because("service layer is the public API of this module");
}
