package com.positivity.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.positivity.tenancy.PlatformScoped;
import com.positivity.tenancy.TenantAudited;
import com.positivity.tenancy.TenantGlobal;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantScopedEntity;
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
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.data.jpa.repository.Query;

/**
 * ADR-0062 §5 build-time rules, scoped to the modules that have adopted {@code pos-tenancy-common}.
 * A module joins {@link #ADOPTED_MODULES} in its WS3 wave; until then its entities, schedulers and
 * repositories are not judged, so the rules never pass vacuously for a module that simply has not
 * been retrofitted (see {@code ClasspathVisibilityGuardTest} for the same idea on entity packages).
 */
@AnalyzeClasses(packages = "com.positivity", importOptions = ImportOption.DoNotIncludeTests.class)
class TenancyArchitectureTest {

    /** Root packages of the modules retrofitted so far (plan WS1 pilot, then WS3 waves). */
    static final String[] ADOPTED_MODULES = {
        "com.positivity.location..",
        "com.positivity.tenant..",
        "com.positivity.securityservice..",
        "com.positivity.inventory..",
        "com.positivity.accounting.."
    };

    private static final String ENTITY_ANNOTATION = "jakarta.persistence.Entity";
    private static final String SCHEDULED_ANNOTATION = "org.springframework.scheduling.annotation.Scheduled";

    @ArchTest
    static final ArchRule every_entity_is_tenant_scoped_or_declared_global = classes()
            .that()
            .resideInAnyPackage(ADOPTED_MODULES)
            .and()
            .areAnnotatedWith(ENTITY_ANNOTATION)
            .should(beTenantScopedOrDeclaredGlobal())
            .because("ADR-0062 section 5: a table is tenant-scoped (entity extends TenantScopedEntity) unless it is"
                    + " listed in tenancy-global-tables.txt with a reason (entity carries @TenantGlobal)");

    @ArchTest
    static final ArchRule nothing_assigns_the_tenant_of_an_entity = noClasses()
            .that()
            .resideOutsideOfPackage("com.positivity.tenancy..")
            .should()
            .accessField(TenantScopedEntity.class, "tenantId")
            .because("ADR-0062 section 3: Hibernate stamps tenant_id from the bound context; application code never"
                    + " chooses a tenant");

    @ArchTest
    static final ArchRule every_scheduler_is_classified = methods()
            .that()
            .areAnnotatedWith(SCHEDULED_ANNOTATION)
            .and()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(ADOPTED_MODULES)
            .should(bePlatformScopedOrIterateTenants())
            .allowEmptyShould(true)
            .because("ADR-0062 section 3: a scheduled job is per-tenant (TenantIterator.forEachActiveTenant) or"
                    + " @PlatformScoped, so an unclassified job cannot silently run unbound");

    @ArchTest
    static final ArchRule native_queries_on_scoped_repositories_are_reviewed = methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(ADOPTED_MODULES)
            .and()
            .areAnnotatedWith(Query.class)
            .should(beReviewedWhenNative())
            .allowEmptyShould(true)
            .because("ADR-0062 section 5: Hibernate's tenant filter does not apply to native SQL, so a native query"
                    + " carries @TenantAudited with the reviewer's reason (RLS still has the final word)");

    @ArchTest
    static final ArchRule only_the_tenancy_library_resolves_tenants_for_hibernate = noClasses()
            .that()
            .resideOutsideOfPackage("com.positivity.tenancy..")
            .should()
            .implement(CurrentTenantIdentifierResolver.class)
            .because("ADR-0062 section 3: one resolver, whose isRoot is never true (a Hibernate-level bypass that RLS"
                    + " would disagree with)");

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

    private static ArchCondition<JavaMethod> beReviewedWhenNative() {
        return new ArchCondition<>("carry @TenantAudited when nativeQuery = true") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean nativeQuery = method.getAnnotationOfType(Query.class).nativeQuery();
                boolean audited = method.isAnnotatedWith(TenantAudited.class)
                        || method.getOwner().isAnnotatedWith(TenantAudited.class);
                if (nativeQuery && !audited) {
                    events.add(SimpleConditionEvent.violated(
                            method, method.getFullName() + " is a native query without @TenantAudited"));
                }
            }
        };
    }
}
