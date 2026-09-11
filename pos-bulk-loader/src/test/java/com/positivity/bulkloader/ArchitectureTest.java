package com.positivity.bulkloader;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.positivity.tenancy.PlatformScoped;
import com.positivity.tenancy.TenantGlobal;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantScopedEntity;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;

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

    private static final DescribedPredicate<JavaCall<?>> SYSTEM_CLOCK_CALL =
            new DescribedPredicate<>("call Clock.systemUTC() or Clock.systemDefaultZone()") {

                @Override
                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(Clock.class)
                            && ("systemUTC".equals(input.getName()) || "systemDefaultZone".equals(input.getName()));
                }
            };

    private static final String INTERNAL_CONTROLLER_PACKAGE = "..internal.controller..";
    private static final String INTERNAL_REPOSITORY_PACKAGE = "..internal.repository..";
    private static final String SERVICE_PACKAGE = "..service..";

    private ArchitectureTest() {
        // Utility class
    }

    @ArchTest
    static final ArchRule controllers_should_not_access_repositories_directly = noClasses()
            .that()
            .resideInAPackage(INTERNAL_CONTROLLER_PACKAGE)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(INTERNAL_REPOSITORY_PACKAGE)
            .allowEmptyShould(true)
            .because("controllers must go through service layer");

    @ArchTest
    static final ArchRule controllers_should_not_access_entities_directly = noClasses()
            .that()
            .resideInAPackage(INTERNAL_CONTROLLER_PACKAGE)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.entity..")
            .allowEmptyShould(true)
            .because("controllers should work with DTOs, not entities");

    @ArchTest
    static final ArchRule services_should_not_depend_on_controllers = noClasses()
            .that()
            .resideInAPackage(SERVICE_PACKAGE)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(INTERNAL_CONTROLLER_PACKAGE)
            .allowEmptyShould(true)
            .because("services should not depend on web layer");

    @ArchTest
    static final ArchRule entities_should_not_depend_on_services = noClasses()
            .that()
            .resideInAPackage("..internal.entity..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(SERVICE_PACKAGE)
            .allowEmptyShould(true)
            .because("entities should be independent of business logic");

    @ArchTest
    static final ArchRule repositories_should_only_be_accessed_from_services_or_config = noClasses()
            .that()
            .resideOutsideOfPackages(SERVICE_PACKAGE, INTERNAL_REPOSITORY_PACKAGE, "..internal.config..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(INTERNAL_REPOSITORY_PACKAGE)
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

    // ADR-0026 D4: the public service package is a grant surface. Grant-surface types may not
    // depend on this module's internal implementation. pos-bulk-loader holds no grant, so this package
    // is empty; the rule (with allowEmptyShould) keeps it honest if a grant is ever added.
    // Package patterns are exact-anchored on purpose: "com.positivity.bulkloader.service.." must NOT
    // match "com.positivity.bulkloader.internal.service".
    @ArchTest
    static final ArchRule public_service_surface_should_not_depend_on_internal = noClasses()
            .that()
            .resideInAPackage("com.positivity.bulkloader.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.bulkloader.internal..")
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
    static final ArchRule packages_should_be_free_of_cycles = slices().matching(
                    "com.positivity.bulkloader.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("cyclic dependencies make modules harder to maintain and evolve");

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

    // ADR-0062 (plan WS8): this module has adopted pos-tenancy-common. The cross-module rules in
    // pos-archunit's TenancyArchitectureTest judge it too; these module-local copies fail the module's
    // own build first, before the reactor-wide test runs.

    @ArchTest
    static final ArchRule every_entity_is_tenant_scoped_or_declared_global = classes()
            .that()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .should(beTenantScopedOrDeclaredGlobal())
            .because("ADR-0062 section 5: a table is tenant-scoped (entity extends TenantScopedEntity) unless it is"
                    + " listed in db/tenancy-global-tables.txt with a reason (entity carries @TenantGlobal)");

    @ArchTest
    static final ArchRule nothing_assigns_the_tenant_of_an_entity = noClasses()
            .should()
            .accessField(TenantScopedEntity.class, "tenantId")
            .because("ADR-0062 section 3: Hibernate stamps tenant_id from the bound context; the loader binds the"
                    + " job's tenant (TenantContext.runAs) and never chooses a row's tenant itself");

    @ArchTest
    static final ArchRule every_scheduler_is_classified = methods()
            .that()
            .areAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
            .should(bePlatformScopedOrIterateTenants())
            .allowEmptyShould(true)
            .because("ADR-0062 section 3: a scheduled job is per-tenant (TenantIterator.forEachActiveTenant) or"
                    + " @PlatformScoped; tus_upload is tenant-scoped, so the expiry sweep iterates tenants");

    private static ArchCondition<JavaClass> beTenantScopedOrDeclaredGlobal() {
        return new ArchCondition<>("extend TenantScopedEntity or be annotated with @TenantGlobal") {
            @Override
            public void check(JavaClass entity, ConditionEvents events) {
                boolean scoped = entity.isAssignableTo(TenantScopedEntity.class);
                boolean global = entity.isAnnotatedWith(TenantGlobal.class);
                if (scoped == global) {
                    events.add(SimpleConditionEvent.violated(
                            entity,
                            entity.getName()
                                    + (scoped
                                            ? " is both tenant-scoped and @TenantGlobal"
                                            : " neither extends TenantScopedEntity nor carries @TenantGlobal")));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> bePlatformScopedOrIterateTenants() {
        return new ArchCondition<>("be annotated with @PlatformScoped or call TenantIterator.forEachActiveTenant") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.isAnnotatedWith(PlatformScoped.class)) {
                    return;
                }
                boolean iterates = method.getMethodCallsFromSelf().stream()
                        .map(JavaMethodCall::getTarget)
                        .anyMatch(target -> target.getOwner().isEquivalentTo(TenantIterator.class)
                                && target.getName().equals("forEachActiveTenant"));
                if (!iterates) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " is @Scheduled but neither @PlatformScoped nor per-tenant"));
                }
            }
        };
    }
}
