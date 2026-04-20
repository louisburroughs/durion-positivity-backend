package com.positivity.bulkloader;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit tests enforcing architecture rules for pos-bulk-loader module.
 *
 * Enforces:
 * - Internal package encapsulation
 * - Service layer as only public API
 * - Controller -> Service -> Repository layering
 * - No circular dependencies
 */
@AnalyzeClasses(packages = "com.positivity.bulkloader", importOptions = ImportOption.DoNotIncludeTests.class)
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
    static final ArchRule entities_should_not_depend_on_services = noClasses()
            .that()
            .resideInAPackage("..internal.entity..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..service..")
            .allowEmptyShould(true)
            .because("entities should be independent of business logic");

    @ArchTest
    static final ArchRule repositories_should_only_be_accessed_from_services_or_config = noClasses()
            .that()
            .resideOutsideOfPackages("..service..", "..internal.repository..", "..internal.config..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.repository..")
            .allowEmptyShould(true)
            .because("repositories should only be accessed from service layer");

    @ArchTest
    static final ArchRule spring_boot_application_should_be_in_root_package = classes()
            .that()
            .areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
            .should()
            .resideInAPackage("com.positivity.bulkloader")
            .andShould()
            .resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that()
            .resideInAPackage("com.positivity.bulkloader.service..")
            .should()
            .bePublic()
            .allowEmptyShould(true)
            .because("service layer is the public API of this module");

    @ArchTest
    static final ArchRule mapped_controller_methods_should_require_authorization = methods()
            .that()
            .areAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
            .or()
            .areAnnotatedWith("org.springframework.web.bind.annotation.GetMapping")
            .or()
            .areAnnotatedWith("org.springframework.web.bind.annotation.PostMapping")
            .or()
            .areAnnotatedWith("org.springframework.web.bind.annotation.PutMapping")
            .or()
            .areAnnotatedWith("org.springframework.web.bind.annotation.DeleteMapping")
            .or()
            .areAnnotatedWith("org.springframework.web.bind.annotation.PatchMapping")
            .should()
            .beAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .orShould()
            .beDeclaredInClassesThat()
            .areAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
            .allowEmptyShould(true)
            .because("all HTTP endpoints must declare authorization guards");

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices().matching(
                    "com.positivity.bulkloader.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("cyclic dependencies make modules harder to maintain and evolve");
}
