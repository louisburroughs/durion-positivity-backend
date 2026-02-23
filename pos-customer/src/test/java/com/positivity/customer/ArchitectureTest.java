package com.positivity.customer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;

/**
 * ArchUnit tests enforcing architecture rules for pos-customer module.
 * 
 * Enforces:
 * - Internal package encapsulation
 * - Service layer as only public API
 * - Controller -> Service -> Repository layering
 * - No circular dependencies
 * 
 * Note: Test classes are excluded from analysis as they legitimately access
 * repositories for setup and verification purposes.
 */
@AnalyzeClasses(packages = "com.positivity.customer", importOptions = ImportOption.DoNotIncludeTests.class)
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
                        .should().resideInAPackage("com.positivity.customer")
                        .andShould().resideOutsideOfPackages("..internal..", "..service..")
                        .allowEmptyShould(true)
                        .because("@SpringBootApplication must be at root for component scanning");

        @ArchTest
        static final ArchRule only_service_layer_should_be_public_api = classes()
                        .that().resideInAPackage("com.positivity.customer.service..")
                        .should().bePublic()
                        .allowEmptyShould(true)
                        .because("service layer is the public API of this module");

        @ArchTest
        static final ArchRule mapped_controller_methods_should_require_authorization = methods()
                        .that().areAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
                        .or().areAnnotatedWith("org.springframework.web.bind.annotation.GetMapping")
                        .or().areAnnotatedWith("org.springframework.web.bind.annotation.PostMapping")
                        .or().areAnnotatedWith("org.springframework.web.bind.annotation.PutMapping")
                        .or().areAnnotatedWith("org.springframework.web.bind.annotation.DeleteMapping")
                        .or().areAnnotatedWith("org.springframework.web.bind.annotation.PatchMapping")
                        .should().beAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
                        .orShould().beDeclaredInClassesThat()
                        .areAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
                        .allowEmptyShould(true)
                        .because("all HTTP endpoints must declare authorization guards");

        @ArchTest
        static final ArchRule packages_should_be_free_of_cycles = slices()
                        .matching("com.positivity.customer.internal.(*)..")
                        .should().beFreeOfCycles()
                        .allowEmptyShould(true)
                        .because("cyclic dependencies make modules harder to maintain and evolve");
        private static final DescribedPredicate<JavaClass> NOT_ABSTRACT = new DescribedPredicate<>("not abstract") {
                @Override
                public boolean test(JavaClass javaClass) {
                        return !javaClass.getModifiers().contains(JavaModifier.ABSTRACT);
                }
        };

        @ArchTest
        static final ArchRule entities_should_depend_on_uuidv7_generator = classes()
                        .that().resideInAnyPackage("..internal.entity..", "..internal.model..")
                        .and(NOT_ABSTRACT)
                        .and().areAnnotatedWith("jakarta.persistence.Entity")
                        .should().dependOnClassesThat()
                        .haveFullyQualifiedName("com.positivity.shared.id.UUIDv7Generator")
                        .orShould().dependOnClassesThat()
                        .haveFullyQualifiedName("com.positivity.shared.id.UUIDv7Id")
                        .allowEmptyShould(true)
                        .because("ADR-0013 mandates UUID v7 generation for all entity identifiers");
        @ArchTest
        static final ArchRule entities_should_not_call_uuid_randomUUID = noClasses()
                        .that().resideInAnyPackage("..internal.entity..", "..internal.model..")
                        .and().areAnnotatedWith("jakarta.persistence.Entity")
                        .should().callMethodWhere(UUID_RANDOM_UUID_CALL)
                        .allowEmptyShould(true)
                        .because("UUIDv7Generator centralizes ID creation; direct randomUUID calls are not allowed");
}
