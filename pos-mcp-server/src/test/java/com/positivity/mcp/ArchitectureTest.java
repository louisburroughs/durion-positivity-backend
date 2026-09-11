package com.positivity.mcp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.positivity.tenancy.PlatformScoped;
import com.positivity.tenancy.TenantIterator;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.UUID;

/**
 * ArchUnit tests enforcing architecture rules for pos-mcp-server module.
 *
 * Enforces:
 * - Internal package encapsulation
 * - Service layer as only public API
 * - Controller -> Service -> Repository layering
 * - No circular dependencies
 */
@AnalyzeClasses(packages = "com.positivity.mcp", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL =
            new DescribedPredicate<>("call UUID.randomUUID()") {

                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(UUID.class) && "randomUUID".equals(input.getName());
                }
            };

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
            .resideOutsideOfPackages(
                    "..service..", "..internal.repository..", "..internal.config..", "..internal.service..")
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
            .resideInAPackage("com.positivity.mcp")
            .andShould()
            .resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that()
            .resideInAPackage("com.positivity.mcp.service..")
            .should()
            .bePublic()
            .allowEmptyShould(true)
            .because("service layer is the public API of this module");

    // ADR-0026 D4: the public service package is a grant surface. Grant-surface types may not
    // depend on this module's internal implementation. pos-mcp-server holds no grant, so this
    // package is empty; the rule (with allowEmptyShould) keeps it honest if a grant is ever added.
    // Package patterns are exact-anchored on purpose: "com.positivity.mcp.service.." must NOT
    // match "com.positivity.mcp.internal.service".
    @ArchTest
    static final ArchRule public_service_surface_should_not_depend_on_internal = noClasses()
            .that()
            .resideInAPackage("com.positivity.mcp.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.mcp.internal..")
            .allowEmptyShould(true)
            .because("ADR-0026 D4: grant-surface types must not leak internal.* types to consuming modules");

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
    static final ArchRule packages_should_be_free_of_cycles = slices().matching("com.positivity.mcp.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("cyclic dependencies make modules harder to maintain and evolve");

    // ADR-0062 §3 (plan WS6): every scheduled job in this module is classified — per tenant through
    // TenantIterator.forEachActiveTenant or sweep, or @PlatformScoped over global tables only. pos-archunit
    // enforces the same rule across modules; this copy keeps the module's own suite red on a new
    // unclassified job. The scheduler table in README.md lists each job's classification.
    @ArchTest
    static final ArchRule scheduled_jobs_should_be_classified_for_tenancy = methods()
            .that()
            .areAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
            .should(bePlatformScopedOrIterateTenants())
            .allowEmptyShould(true)
            .because(
                    "ADR-0062 section 3: a scheduled job is per-tenant (TenantIterator.forEachActiveTenant or sweep) or"
                            + " @PlatformScoped, so an unclassified job cannot silently run unbound");

    // Jobs registered programmatically (SchedulingConfigurer.configureTasks -> addFixedDelayTask and
    // friends) carry no @Scheduled and would slip past the rule above; the registering method is
    // classified instead (SiteMapEmbeddingWarmupRunner).
    @ArchTest
    static final ArchRule programmatically_scheduled_jobs_should_be_classified_for_tenancy = methods()
            .that()
            .haveName("configureTasks")
            .and()
            .areDeclaredInClassesThat()
            .implement("org.springframework.scheduling.annotation.SchedulingConfigurer")
            .should(bePlatformScopedOrIterateTenants())
            .allowEmptyShould(true)
            .because(
                    "ADR-0062 section 3: a job registered through SchedulingConfigurer is classified on the"
                            + " registering method, per-tenant (TenantIterator.forEachActiveTenant or sweep) or @PlatformScoped");

    /** The {@link TenantIterator} entry points that bind each active tenant in turn. */
    private static final java.util.Set<String> PER_TENANT_ITERATION = java.util.Set.of("forEachActiveTenant", "sweep");

    private static ArchCondition<JavaMethod> bePlatformScopedOrIterateTenants() {
        return new ArchCondition<>(
                "be annotated with @PlatformScoped or call TenantIterator.forEachActiveTenant or sweep") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.isAnnotatedWith(PlatformScoped.class)) {
                    return;
                }
                // Either entry point classifies a job as per-tenant: forEachActiveTenant and sweep read
                // the same active-tenant list and bind each tenant in turn. sweep additionally reports
                // whether that list was complete, which a caller needs before writing a cross-tenant
                // rollup (plan WS6-b); it is the same iteration, so it satisfies the same rule.
                boolean iterates = method.getMethodCallsFromSelf().stream()
                        .map(JavaMethodCall::getTarget)
                        .anyMatch(target -> target.getOwner().isEquivalentTo(TenantIterator.class)
                                && PER_TENANT_ITERATION.contains(target.getName()));
                if (!iterates) {
                    events.add(SimpleConditionEvent.violated(
                            method, method.getFullName() + " is scheduled but neither @PlatformScoped nor per-tenant"));
                }
            }
        };
    }

    @ArchTest
    static final ArchRule entities_should_depend_on_uuidv7_id = classes()
            .that()
            .resideInAnyPackage("..internal.entity..", "..internal.model..")
            .and()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.positivity.shared.id.UUIDv7Id")
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
            .because("UUIDv7Id centralizes ID creation; direct randomUUID calls are not allowed");
}
