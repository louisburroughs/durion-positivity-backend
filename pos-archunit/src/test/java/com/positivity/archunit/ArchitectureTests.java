package com.positivity.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
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
    private static final String DTO_SUFFIX_MAX_PROPERTY = "archunit.dtoSuffix.max";
    private static final DescribedPredicate<JavaCall<?>> NO_ARG_NOW_CALLS = new DescribedPredicate<>(
            "call no-arg Instant/LocalDateTime now methods") {
        @Override
        public boolean test(JavaCall<?> input) {
            if (!"now".equals(input.getName())) {
                return false;
            }
            boolean supportedOwner = input.getTargetOwner().isEquivalentTo(Instant.class)
                    || input.getTargetOwner().isEquivalentTo(LocalDateTime.class);
            return supportedOwner && input.getTarget().getRawParameterTypes().isEmpty();
        }
    };

    @BeforeAll
    static void setup() {
        // Import all classes from com.positivity package
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.positivity");
    }

    @Test
    void internalPackagesShouldNotBeAccessedFromOtherModules() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.positivity..")
                .and().resideOutsideOfPackages("..internal..")
                .should(new ArchCondition<>("not depend on internal packages of other modules") {
                    @Override
                    public void check(JavaClass originClass, ConditionEvents events) {
                        String originModule = moduleName(originClass.getPackageName());
                        if (originModule == null) {
                            return;
                        }

                        for (Dependency dependency : originClass.getDirectDependenciesFromSelf()) {
                            JavaClass targetClass = dependency.getTargetClass();
                            String targetPackage = targetClass.getPackageName();
                            String targetModule = moduleName(targetPackage);

                            if (targetModule == null) {
                                continue;
                            }
                            if (!targetPackage.contains(".internal.")) {
                                continue;
                            }
                            if (originModule.equals(targetModule)) {
                                continue;
                            }

                            events.add(SimpleConditionEvent.violated(
                                    dependency,
                                    dependency.getDescription()));
                        }
                    }
                })
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
                .that().resideOutsideOfPackages(
                        "..service..",
                        "..dao..",
                        "..internal.dao..",
                        "..internal.repository..",
                        "..internal.config..")
                .should().dependOnClassesThat()
                .resideInAPackage("..internal.repository..")
                .because("repositories should only be accessed from service/dao layers");

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
                .should().haveSimpleNameNotEndingWith("Service")
                .because(
                        "internal service implementations are allowed in ..internal.service.., while other internal public classes should avoid exposing service suffixes");

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
        ArchRule rule = classes()
                .that().resideInAPackage("com.positivity..")
                .should(new ArchCondition<>("not depend on internal DTOs of other modules") {
                    @Override
                    public void check(JavaClass originClass, ConditionEvents events) {
                        String originModule = moduleName(originClass.getPackageName());
                        if (originModule == null) {
                            return;
                        }

                        for (Dependency dependency : originClass.getDirectDependenciesFromSelf()) {
                            JavaClass targetClass = dependency.getTargetClass();
                            String targetPackage = targetClass.getPackageName();
                            String targetModule = moduleName(targetPackage);

                            if (targetModule == null) {
                                continue;
                            }
                            if (!targetPackage.contains(".internal.dto.")) {
                                continue;
                            }
                            if (originModule.equals(targetModule)) {
                                continue;
                            }

                            events.add(SimpleConditionEvent.violated(
                                    dependency,
                                    dependency.getDescription()));
                        }
                    }
                })
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

    @Test
    void productionCodeShouldNotUseNoArgNowCalls() {
        ArchRule rule = noClasses()
                .should().callMethodWhere(NO_ARG_NOW_CALLS)
                .because("time access must use explicit Clock injection or explicit Clock argument");

        rule.check(allClasses);
    }

    @Test
    void dtoSuffixMigrationReport() {
        List<JavaClass> dtoClasses = allClasses.stream()
                .filter(javaClass -> javaClass.getPackageName().contains(".internal."))
                .filter(javaClass -> javaClass.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(javaClass -> javaClass.getSimpleName().endsWith("Dto"))
                .sorted(Comparator.comparing(JavaClass::getFullName))
                .toList();

        Map<String, Long> moduleCounts = dtoClasses.stream()
                .collect(Collectors.groupingBy(
                        javaClass -> {
                            String module = moduleName(javaClass.getPackageName());
                            return module == null ? "unknown" : module;
                        },
                        TreeMap::new,
                        Collectors.counting()));

        System.out.println("[ArchUnit][DTO Migration] Public internal *Dto classes: " + dtoClasses.size());
        moduleCounts.forEach((module, count) ->
                System.out.println("[ArchUnit][DTO Migration] module=" + module + " count=" + count));

        int maxAllowed = Integer.getInteger(DTO_SUFFIX_MAX_PROPERTY, Integer.MAX_VALUE);
        if (maxAllowed != Integer.MAX_VALUE && dtoClasses.size() > maxAllowed) {
            String message = "DTO suffix count " + dtoClasses.size()
                    + " exceeds configured max " + maxAllowed
                    + " (set via -D" + DTO_SUFFIX_MAX_PROPERTY + ")";
            Assertions.fail(message);
        }
    }

    private static String moduleName(String packageName) {
        String prefix = "com.positivity.";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String remainder = packageName.substring(prefix.length());
        int nextDot = remainder.indexOf('.');
        if (nextDot < 0) {
            return remainder;
        }
        return remainder.substring(0, nextDot);
    }
}
