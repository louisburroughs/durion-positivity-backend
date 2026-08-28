package com.positivity.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;

/**
 * ArchUnit tests enforcing architecture rules for pos-inventory module.
 *
 * Enforces:
 * - Internal package encapsulation
 * - Service layer as only public API
 * - Controller -> Service -> Repository layering
 * - No circular dependencies
 */
@AnalyzeClasses(packages = "com.positivity.inventory", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL =
            new DescribedPredicate<>("call UUID.randomUUID()") {

                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(UUID.class) && "randomUUID".equals(input.getName());
                }
            };

    private static final DescribedPredicate<JavaCall<?>> LEDGER_REPOSITORY_SAVE_CALL =
            new DescribedPredicate<>("call InventoryLedgerEntryRepository.save/saveAll") {

                public boolean test(JavaCall<?> input) {
                    return "com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository"
                                    .equals(input.getTargetOwner().getFullName())
                            && ("save".equals(input.getName()) || "saveAll".equals(input.getName()));
                }
            };

    @ArchTest
    static final ArchRule inventory_should_not_depend_on_pos_location_classes = noClasses()
            .that()
            .resideInAPackage("com.positivity.inventory..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.location..")
            .allowEmptyShould(true)
            .because("pos-inventory must consume pos-location only via REST contracts with"
                    + " consumer-side DTOs (ADR-0016; CAP-218 #658)");

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
            .resideInAPackage("com.positivity.inventory")
            .andShould()
            .resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that()
            .resideInAPackage("com.positivity.inventory.service..")
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
    static final ArchRule service_package_should_define_interfaces_only = noClasses()
            .that()
            .resideInAPackage("com.positivity.inventory.service..")
            .should()
            .notBeInterfaces()
            .allowEmptyShould(true)
            .because("service package must expose contracts only; implementations belong in internal.service");

    // ADR-0026 D4: the public service package is a grant surface. Grant-surface types may not
    // depend on this module's internal implementation. pos-inventory holds no grant, so this
    // package is empty; the rule (with allowEmptyShould) keeps it honest if a grant is ever added.
    // Package patterns are exact-anchored on purpose: "com.positivity.inventory.service.." must
    // NOT match "com.positivity.inventory.internal.service".
    @ArchTest
    static final ArchRule public_service_surface_should_not_depend_on_internal = noClasses()
            .that()
            .resideInAPackage("com.positivity.inventory.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.inventory.internal..")
            .allowEmptyShould(true)
            .because("ADR-0026 D4: grant-surface types must not leak internal.* types to consuming modules");

    @ArchTest
    static final ArchRule packages_should_be_free_of_cycles = slices().matching(
                    "com.positivity.inventory.internal.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("internal package cycles make the implementation harder to maintain and evolve");

    @ArchTest
    static final ArchRule entities_should_use_uuidv7_id_or_generator = classes()
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
    static final ArchRule ledger_entry_writes_must_go_through_posting_funnel = noClasses()
            .that()
            .doNotHaveFullyQualifiedName("com.positivity.inventory.internal.service.LedgerPostingServiceImpl")
            .should()
            .callMethodWhere(LEDGER_REPOSITORY_SAVE_CALL)
            .allowEmptyShould(true)
            .because("all inventory_ledger_entry appends must flow through LedgerPostingService so the"
                    + " stock summary read model stays consistent (#1024, A1) and the negative-stock"
                    + " policy matrix has a single enforcement point (#1027, K1)");

    /**
     * The classes that own supplier availability hints. Everything else in the module — every
     * valuation, costing, ledger, stock-summary and availability path included — is held away from
     * the hint tables by {@link #supplier_stock_hints_must_not_be_read_outside_their_own_slice}.
     */
    private static final String[] SUPPLIER_HINT_CLASSES = {
        "com.positivity.inventory.internal.service.SupplierStockHintEventsListener",
        "com.positivity.inventory.internal.service.SupplierStockHintServiceImpl",
        "com.positivity.inventory.internal.service.SupplierStockHintResolver"
    };

    @ArchTest
    static final ArchRule supplier_stock_hints_must_not_be_read_outside_their_own_slice = noClasses()
            .that()
            .resideOutsideOfPackages("..internal.repository..", "..internal.entity..")
            .and()
            .doNotHaveFullyQualifiedName(SUPPLIER_HINT_CLASSES[0])
            .and()
            .doNotHaveFullyQualifiedName(SUPPLIER_HINT_CLASSES[1])
            .and()
            .doNotHaveFullyQualifiedName(SUPPLIER_HINT_CLASSES[2])
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.positivity.inventory.internal.repository.SupplierStockHintRepository")
            .allowEmptyShould(true)
            .because("supplier availability is what a VENDOR says about ITS OWN stock (CAP-322, #1312):"
                    + " it is not owned stock, so no valuation path (ADR-0048) and no on-hand ATP path may"
                    + " read it. Keeping the hints in their own tables makes that structural — nothing"
                    + " joins them — and this rule is what keeps it structural as the module grows");

    @ArchTest
    static final ArchRule valuation_and_atp_must_not_read_supplier_stock_hints = noClasses()
            .that()
            .haveSimpleNameContaining("Valuation")
            .or()
            .haveSimpleNameContaining("Costing")
            .or()
            .haveSimpleNameContaining("Availability")
            .or()
            .haveSimpleNameContaining("StockSummary")
            .or()
            .haveSimpleNameContaining("LedgerPosting")
            .or()
            .haveSimpleNameContaining("Revaluation")
            .should()
            .dependOnClassesThat()
            .haveNameMatching("com\\.positivity\\.inventory\\.internal\\.(repository|entity)\\.SupplierStockHint.*")
            .allowEmptyShould(true)
            .because("a vendor's warehouse must never be mistaken for ours: valuation figures and"
                    + " availability-to-promise are computed from owned stock alone, and a supplier hint"
                    + " reaching either of them would misstate what we own and what we can promise");

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
