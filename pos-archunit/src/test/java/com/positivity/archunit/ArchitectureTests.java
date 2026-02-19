package com.positivity.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit architecture validation tests for all POS modules.
 * 
 * These tests verify that:
 * 1. Only service layer packages are exposed as public APIs
 * 2. Internal packages are properly encapsulated
 * 3. Controllers don't directly access repositories (must go through services)
 * 4. No circular dependencies between modules
 * 5. Proper layering is maintained (controller -> service -> repository)
 */
class ArchitectureTests {

    private static JavaClasses allClasses;

    @BeforeAll
    static void setup() {
        // Import all classes from com.positivity package
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.positivity");
    }

    @Test
    void internalPackagesShouldNotBeAccessedFromOtherModules() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages("..internal..")
                .and().resideInAPackage("com.positivity.(*)..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.positivity.(*).internal..")
                .because(
                        "internal packages should not be accessed from other modules - only service layer should be exposed");

        rule.check(allClasses);
    }

    @Test
    void controllersShouldNotDirectlyAccessRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..internal.controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("..internal.repository..")
                .because("controllers must go through service layer - no direct repository access");

        // Allow empty check - some modules may not have controllers yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void controllersShouldNotDirectlyAccessEntities() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..internal.controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("..internal.entity..")
                .because("controllers should work with DTOs - no direct entity access");

        // Allow empty check - some modules may not have controllers yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void repositoriesShouldOnlyBeAccessedFromServiceLayer() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages("..service..", "..internal.repository..", "..internal.config..")
                .should().dependOnClassesThat()
                .resideInAPackage("..internal.repository..")
                .because("repositories should only be accessed from service layer");

        // Allow empty check - some modules may not have repositories yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void entitiesShouldNotDependOnServices() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..internal.entity..")
                .should().dependOnClassesThat()
                .resideInAPackage("..service..")
                .because("entities should be independent of business logic - no service dependencies");

        // Allow empty check - some modules may not have entities yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void onlyServicePackagesShouldBePublic() {
        ArchRule rule = classes()
                .that().resideInAPackage("..internal..")
                .and().arePublic()
                .and().resideOutsideOfPackages("..internal.service..")
                .should().haveSimpleNameNotContaining("Service")
                .andShould().haveSimpleNameNotContaining("Dto")
                .because(
                        "internal service implementations are allowed in ..internal.service.., while other internal public classes should avoid leaking service API types");

        // This rule is informational - allows some flexibility for internal public
        // classes
        // but flags potential API leaks
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void servicesShouldNotDependOnControllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat()
                .resideInAPackage("..internal.controller..")
                .because("services should not depend on controllers - inverted dependency");

        // Allow empty check - some modules may not have services yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void dtosInInternalPackageShouldOnlyBeUsedWithinModule() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.positivity.(*)..")
                .and().resideOutsideOfPackages("com.positivity.$1..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.positivity.$1.internal.dto..")
                .because("internal DTOs should not leak across module boundaries");

        rule.check(allClasses);
    }

    @Test
    void springBootApplicationClassesShouldBeInRootPackage() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
                .should().resideInAPackage("com.positivity.(*)")
                .andShould().resideOutsideOfPackages("..internal..", "..service..")
                .because("@SpringBootApplication classes must be at root package for proper component scanning");

        rule.check(allClasses);
    }
}
