package com.positivity.securityservice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.service.EffectiveGrantResolverImpl;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;

/**
 * ArchUnit tests enforcing architecture rules for pos-security-service module.
 *
 * Enforces:
 * - Internal package encapsulation
 * - Service layer as only public API
 * - Controller -> Service -> Repository layering
 * - No circular dependencies
 */
@AnalyzeClasses(packages = "com.positivity.securityservice", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL =
            new DescribedPredicate<>("call UUID.randomUUID()") {

                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(UUID.class) && "randomUUID".equals(input.getName());
                }
            };

    // ADR-0061 amendment (2026-09-09, #1914): every authorization decision must resolve through
    // EffectiveGrantResolver, the single place that unions user.getRoles() (undated user_roles)
    // with the effective-dated role_assignments a decision point is evaluated against. These two
    // predicates keep that true by construction rather than by convention: nothing outside
    // EffectiveGrantResolverImpl may read either store's raw "effective as of now" shape directly.
    private static final DescribedPredicate<JavaCall<?>> FIND_EFFECTIVE_ASSIGNMENTS_CALL =
            new DescribedPredicate<>("call a RoleAssignmentRepository.findEffectiveAssignments* method") {

                public boolean test(JavaCall<?> input) {
                    return "com.positivity.securityservice.internal.repository.RoleAssignmentRepository"
                                    .equals(input.getTargetOwner().getFullName())
                            && input.getName().startsWith("findEffectiveAssignments");
                }
            };

    private static final DescribedPredicate<JavaCall<?>> USER_GET_ROLES_CALL =
            new DescribedPredicate<>("call User.getRoles()") {

                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(User.class) && "getRoles".equals(input.getName());
                }
            };

    private static final DescribedPredicate<JavaClass> NOT_EFFECTIVE_GRANT_RESOLVER_IMPL =
            new DescribedPredicate<>("not EffectiveGrantResolverImpl") {

                public boolean test(JavaClass input) {
                    return !input.isEquivalentTo(EffectiveGrantResolverImpl.class);
                }
            };

    // User itself is exempted here too: Lombok's generated equals/hashCode/toString read every
    // field, including roles, on `this` — that is not a decision point reaching around the
    // resolver, just the entity describing its own state.
    private static final DescribedPredicate<JavaClass> NOT_EFFECTIVE_GRANT_RESOLVER_IMPL_OR_USER =
            new DescribedPredicate<>("not EffectiveGrantResolverImpl or User") {

                public boolean test(JavaClass input) {
                    return !input.isEquivalentTo(EffectiveGrantResolverImpl.class) && !input.isEquivalentTo(User.class);
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
                    "..service..", "..internal.repository..", "..internal.config..", "..internal.security..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.repository..")
            .allowEmptyShould(true)
            .because("repositories should only be accessed from service layer or security components");

    @ArchTest
    static final ArchRule spring_boot_application_should_be_in_root_package = classes()
            .that()
            .areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
            .should()
            .resideInAPackage("com.positivity.securityservice")
            .andShould()
            .resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that()
            .resideInAPackage("com.positivity.securityservice.service..")
            .should()
            .bePublic()
            .allowEmptyShould(true)
            .because("service layer is the public API of this module");

    // ADR-0026 D4: the public service package is a grant surface. Grant-surface types may not
    // depend on this module's internal implementation. pos-security-service holds no grant, so this package
    // is empty; the rule (with allowEmptyShould) keeps it honest if a grant is ever added.
    // Package patterns are exact-anchored on purpose: "com.positivity.securityservice.service.." must NOT
    // match "com.positivity.securityservice.internal.service".
    @ArchTest
    static final ArchRule public_service_surface_should_not_depend_on_internal = noClasses()
            .that()
            .resideInAPackage("com.positivity.securityservice.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.securityservice.internal..")
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
                    "com.positivity.securityservice.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because(
                    "cyclic dependencies inside internal implementation packages make modules harder to maintain and evolve");

    @ArchTest
    static final ArchRule entities_should_depend_on_uuidv7_generator = classes()
            .that()
            .resideInAnyPackage("..internal.entity..", "..internal.model..")
            .and()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.positivity.shared.id.UUIDv7Generator")
            .orShould()
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
            .because("UUIDv7Generator centralizes ID creation; direct randomUUID calls are not allowed");

    @ArchTest
    static final ArchRule only_effective_grant_resolver_should_read_effective_assignments = noClasses()
            .that(NOT_EFFECTIVE_GRANT_RESOLVER_IMPL)
            .should()
            .callMethodWhere(FIND_EFFECTIVE_ASSIGNMENTS_CALL)
            .allowEmptyShould(true)
            .because("ADR-0061 amendment (#1914): every decision point resolves effective role assignments through "
                    + "EffectiveGrantResolver, not by re-querying findEffectiveAssignments* itself");

    @ArchTest
    static final ArchRule only_effective_grant_resolver_should_read_user_getRoles = noClasses()
            .that(NOT_EFFECTIVE_GRANT_RESOLVER_IMPL_OR_USER)
            .should()
            .callMethodWhere(USER_GET_ROLES_CALL)
            .allowEmptyShould(true)
            .because("ADR-0061 amendment (#1914): every decision point resolves a user's directly-assigned roles "
                    + "through EffectiveGrantResolver, not by reading User.getRoles() itself; writers still "
                    + "call User.setRoles until user_roles is retired in phase 2");
}
