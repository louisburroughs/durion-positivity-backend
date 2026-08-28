package com.positivity.documents;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.util.UUID;

@AnalyzeClasses(packages = "com.positivity.documents", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final DescribedPredicate<JavaCall<?>> SYSTEM_CLOCK_CALL =
            new DescribedPredicate<>("call Clock.systemUTC() or Clock.systemDefaultZone()") {

                @Override
                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(Clock.class)
                            && ("systemUTC".equals(input.getName()) || "systemDefaultZone".equals(input.getName()));
                }
            };

    private ArchitectureTest() {}

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL =
            new DescribedPredicate<>("call UUID.randomUUID()") {

                @Override
                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(UUID.class) && "randomUUID".equals(input.getName());
                }
            };

    @ArchTest
    static final ArchRule controllersShouldNotDependOnRepositoryLayer = noClasses()
            .that()
            .resideInAPackage("..internal.controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.repository..");

    // ADR-0026 D4: the public service package is a grant surface. Grant-surface types may not
    // depend on this module's internal implementation. pos-documents holds no grant, so this package
    // is empty; the rule (with allowEmptyShould) keeps it honest if a grant is ever added.
    // Package patterns are exact-anchored on purpose: "com.positivity.documents.service.." must NOT
    // match "com.positivity.documents.internal.service".
    @ArchTest
    static final ArchRule public_service_surface_should_not_depend_on_internal = noClasses()
            .that()
            .resideInAPackage("com.positivity.documents.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.documents.internal..")
            .allowEmptyShould(true)
            .because("ADR-0026 D4: grant-surface types must not leak internal.* types to consuming modules");

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices().matching(
                    "com.positivity.documents.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("cyclic dependencies make modules harder to maintain and evolve");

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
    static final ArchRule entities_should_depend_on_uuidv7_generator = classes()
            .that()
            .resideInAnyPackage("..internal.entity..", "..internal.model..")
            .and()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.positivity.shared.id.UUIDv7Generator")
            .allowEmptyShould(true)
            .because("ADR-0013 mandates UUID v7 generation for all entity identifiers");

    @ArchTest
    static final ArchRule entities_should_not_call_uuid_randomUUID = noClasses()
            .that()
            .resideInAnyPackage("..internal.entity..", "..internal.model..")
            .and()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .should()
            .callMethodWhere(UUID_RANDOM_UUID_CALL)
            .allowEmptyShould(true)
            .because("UUIDv7Generator centralizes ID creation; direct randomUUID calls are not allowed");

    @ArchTest
    static final ArchRule production_code_should_not_read_the_system_clock = noClasses()
            .should()
            .callMethodWhere(SYSTEM_CLOCK_CALL)
            .because("pos-events owns the application Clock; reading the system clock here keeps this module on wall"
                    + " time while the rest of the deployment runs on the accelerated clock");

    @ArchTest
    static final ArchRule module_should_not_declare_its_own_clock_bean = noMethods()
            .that()
            .areAnnotatedWith("org.springframework.context.annotation.Bean")
            .should()
            .haveRawReturnType(Clock.class)
            .allowEmptyShould(true)
            .because("a competing Clock bean wins over the accelerated ScaledClock and drags this module's JPA"
                    + " auditing provider onto wall time");
}
