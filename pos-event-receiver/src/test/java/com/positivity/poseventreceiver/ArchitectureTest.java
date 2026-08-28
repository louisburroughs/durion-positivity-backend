package com.positivity.poseventreceiver;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ArchUnit tests enforcing architecture rules for pos-event-receiver module.
 *
 * Enforces:
 * - Internal package encapsulation
 * - Service layer as only public API
 * - Controller -> Service -> Dao -> Repository layering
 * - No circular dependencies
 */
@AnalyzeClasses(packages = "com.positivity.poseventreceiver", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL =
            new DescribedPredicate<>("call UUID.randomUUID()") {

                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(UUID.class) && "randomUUID".equals(input.getName());
                }
            };

    // --- Support for mapped_controller_methods_should_require_authorization ---
    //
    // pos-event-receiver has no spring-security dependency, so its controllers cannot carry
    // @PreAuthorize. Instead EventsApiSecurityFilter (a plain servlet filter, see
    // internal/config/EventsApiSecurityFilter.java) enforces the shared X-Events-Api-Secret
    // header. That filter's actual guard is narrower than "every controller in this module":
    // it lets all GET/HEAD/OPTIONS requests through unauthenticated (any path), and otherwise
    // only checks the secret for requests whose path starts with /v1/events or /v1/eventTypes.
    // A mutating endpoint mapped anywhere else would be unauthenticated in production AND, if the
    // exemption were keyed on the controller package instead of the mapping path, invisible to
    // this rule. So the exemption below is keyed on the method's own declared request-mapping
    // path (class-level @RequestMapping path + method-level mapping path), mirroring the filter's
    // real routing logic instead of the package the controller happens to live in.
    private static final List<Class<? extends Annotation>> REQUEST_MAPPING_ANNOTATIONS =
            List.of(RequestMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    private static String[] mappingPathAttribute(Annotation mapping, String attribute) {
        try {
            return (String[]) mapping.annotationType().getMethod(attribute).invoke(mapping);
        } catch (ReflectiveOperationException e) {
            return new String[0];
        }
    }

    private static String firstMappingPath(Annotation mapping) {
        String[] values = mappingPathAttribute(mapping, "value");
        if (values.length == 0) {
            values = mappingPathAttribute(mapping, "path");
        }
        return values.length > 0 ? values[0] : "";
    }

    private static String classLevelMappingPath(JavaClass owner) {
        return owner.tryGetAnnotationOfType(RequestMapping.class)
                .map(ArchitectureTest::firstMappingPath)
                .orElse("");
    }

    private static String methodLevelMappingPath(JavaMethod method) {
        for (Class<? extends Annotation> mappingType : REQUEST_MAPPING_ANNOTATIONS) {
            if (method.isAnnotatedWith(mappingType)) {
                return firstMappingPath(method.getAnnotationOfType(mappingType));
            }
        }
        return "";
    }

    // Endpoints EventsApiSecurityFilter actually protects: GET-mapped methods are exempt on any
    // path (the filter's SAFE_METHODS allowlist lets all reads through unauthenticated), and every
    // other mapped method is exempt only when its resolved path is under /v1/events or
    // /v1/eventTypes -- the two prefixes the filter checks the shared secret against.
    private static final DescribedPredicate<JavaMethod> GUARDED_BY_EVENTS_API_SECURITY_FILTER =
            new DescribedPredicate<>("is mapped to GET (exempt on any path, per EventsApiSecurityFilter's SAFE_METHODS "
                    + "allowlist) or to a path under /v1/events or /v1/eventTypes (the paths "
                    + "EventsApiSecurityFilter checks the shared secret against)") {

                @Override
                public boolean test(JavaMethod method) {
                    if (method.isAnnotatedWith(GetMapping.class)) {
                        return true;
                    }
                    String path = classLevelMappingPath(method.getOwner()) + methodLevelMappingPath(method);
                    return path.startsWith("/v1/events") || path.startsWith("/v1/eventTypes");
                }
            };

    private static final DescribedPredicate<JavaMethod> ANNOTATED_WITH_PRE_AUTHORIZE =
            new DescribedPredicate<>(
                    "annotated with @PreAuthorize, or declared in a class annotated with @PreAuthorize") {

                @Override
                public boolean test(JavaMethod method) {
                    return method.isAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize")
                            || method.getOwner()
                                    .isAnnotatedWith("org.springframework.security.access.prepost.PreAuthorize");
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

    // pos-event-receiver's dao layer (EventDaoImpl) sits between service and repository and is
    // expected to depend on ..internal.repository..; it is added to the allow-list alongside
    // ..service.. and ..internal.config.. rather than treated as a violation (mirrors
    // pos-marketing / pos-vehicle-inventory / pos-warranty, which have the same dao layer).
    @ArchTest
    static final ArchRule repositories_should_only_be_accessed_from_services_or_config = noClasses()
            .that()
            .resideOutsideOfPackages(
                    "..service..", "..internal.dao..", "..internal.repository..", "..internal.config..")
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
            .resideInAPackage("com.positivity.poseventreceiver")
            .andShould()
            .resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that()
            .resideInAPackage("com.positivity.poseventreceiver.service..")
            .should()
            .bePublic()
            .allowEmptyShould(true)
            .because("service layer is the public API of this module");

    // ADR-0026 D4: the public service package is a grant surface. Grant-surface types may not
    // depend on this module's internal implementation. pos-event-receiver holds no grant, so this
    // package is empty; the rule (with allowEmptyShould) keeps it honest if a grant is ever added.
    // Package patterns are exact-anchored on purpose: "com.positivity.poseventreceiver.service.."
    // must NOT match "com.positivity.poseventreceiver.internal.service".
    @ArchTest
    static final ArchRule public_service_surface_should_not_depend_on_internal = noClasses()
            .that()
            .resideInAPackage("com.positivity.poseventreceiver.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.poseventreceiver.internal..")
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
            // Documented exemption: pos-event-receiver is the internal event-ingestion hub
            // (not reached through the API gateway; see the architecture diagram in
            // CLAUDE.md) and carries no spring-security dependency at all, so its controllers
            // cannot declare @PreAuthorize. Authorization instead lives at the servlet-filter
            // layer in EventsApiSecurityFilter. The exemption below is keyed on each method's
            // own declared request-mapping path (GUARDED_BY_EVENTS_API_SECURITY_FILTER above),
            // not on the controller package, so a mapped method the filter does not actually
            // guard -- e.g. a mutating endpoint added under some other path -- fails this rule
            // instead of being silently exempted.
            .should(ArchCondition.from(ANNOTATED_WITH_PRE_AUTHORIZE.or(GUARDED_BY_EVENTS_API_SECURITY_FILTER)))
            .allowEmptyShould(true)
            .because("all HTTP endpoints must declare authorization guards, except pos-event-receiver's"
                    + " methods that EventsApiSecurityFilter actually protects: GET-mapped methods (its"
                    + " SAFE_METHODS allowlist lets all reads through unauthenticated, on any path) and"
                    + " methods mapped under /v1/events or /v1/eventTypes (the only paths the filter"
                    + " checks the shared X-Events-Api-Secret header against); this module has no"
                    + " spring-security dependency to carry a @PreAuthorize annotation with");

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices().matching(
                    "com.positivity.poseventreceiver.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("cyclic dependencies make modules harder to maintain and evolve");

    // Accepts either the modern @UUIDv7Id Hibernate id-generator annotation (used by
    // EmittedEvent and EventType) or the legacy UUIDv7Generator.generate() @PrePersist call,
    // mirroring pos-order's rule -- pos-event-receiver's UUID-keyed entities use @UUIDv7Id.
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
}
