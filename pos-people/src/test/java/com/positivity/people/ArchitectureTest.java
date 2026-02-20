package com.positivity.people;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.positivity.shared.id.UUIDv7Generator;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import java.util.UUID;

/**
 * ArchUnit tests enforcing architecture rules for pos-people module.
 * 
 * Enforces:
 * - Internal package encapsulation
 * - Service layer as only public API
 * - Controller -> Service -> Repository layering
 * - No circular dependencies
 */
@AnalyzeClasses(packages = "com.positivity.people", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL = new DescribedPredicate<>(
            "call UUID.randomUUID()") {
        
        public boolean test(JavaCall<?> input) {
            return input.getTargetOwner().isEquivalentTo(UUID.class)
                    && "randomUUID".equals(input.getName());
        }
    };

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
                        .that().resideInAnyPackage(
                                        "com.positivity.people.service..",
                                        "com.positivity.people.internal.service..")
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
                        .that()
                        .resideOutsideOfPackages(
                                        "com.positivity.people.service..",
                                        "com.positivity.people.internal.service..",
                                        "..internal.repository..",
                                        "..internal.config..")
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
                        .and().areNotAnonymousClasses()
                        .and().areNotInnerClasses()
                        .should().bePublic()
                        .allowEmptyShould(true)
                        .because("service layer is the public API of this module");

        @ArchTest
        static final ArchRule classes_outside_internal_should_use_service_naming = classes()
                        .that().resideInAPackage("com.positivity.people..")
                        .and().resideOutsideOfPackages("..internal..", "com.positivity.people")
                        .and().areNotAnonymousClasses()
                        .and().areNotInnerClasses()
                        .and().haveSimpleNameNotContaining("Dto")
                        .and().haveSimpleNameNotContaining("Exception")
                        .should().haveSimpleNameEndingWith("Service")
                        .allowEmptyShould(true)
                        .because("outside internal packages, exposed module types should be service contracts");


    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices()
            .matching("com.positivity.people.(*)..")
            .should().beFreeOfCycles()
            .because("cyclic dependencies make modules harder to maintain and evolve");
    @ArchTest
    static final ArchRule entities_should_depend_on_uuidv7_generator = classes()
            .that().resideInAnyPackage("..internal.entity..", "..internal.model..")
            .and().areAnnotatedWith(Entity.class)
            .should().dependOnClassesThat().belongToAnyOf(UUIDv7Generator.class)
            .allowEmptyShould(true)
            .because("ADR-0013 mandates UUID v7 generation for all entity identifiers");
    @ArchTest
    static final ArchRule entities_should_not_call_uuid_randomUUID = noClasses()
            .that().resideInAnyPackage("..internal.entity..", "..internal.model..")
            .and().areAnnotatedWith(Entity.class)
            .should().callMethodWhere(UUID_RANDOM_UUID_CALL)
            .allowEmptyShould(true)
            .because("UUIDv7Generator centralizes ID creation; direct randomUUID calls are not allowed");
}
