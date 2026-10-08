package com.positivity.accounting;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import java.util.UUID;

@AnalyzeClasses(
        packages = "com.positivity.accounting",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public class ArchitectureTest {

    private static final DescribedPredicate<JavaCall<?>> UUID_RANDOM_UUID_CALL =
            new DescribedPredicate<>("call UUID.randomUUID()") {
                @Override
                public boolean test(JavaCall<?> input) {
                    return input.getTargetOwner().isEquivalentTo(UUID.class) && "randomUUID".equals(input.getName());
                }
            };

    /** The date-time types whose {@code now()} / {@code now(Clock)} reads a zone implicitly. */
    private static final Set<String> ZONED_NOW_TYPES = Set.of(
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.YearMonth",
            "java.time.Year",
            "java.time.MonthDay",
            "java.time.ZonedDateTime",
            "java.time.OffsetDateTime",
            "java.time.OffsetTime");

    /**
     * A call that reads a zone the tenant never chose (#2558): {@code Clock.getZone()} (the clock bean is UTC),
     * {@code Clock.systemDefaultZone()}, {@code ZoneId/ZoneOffset.systemDefault()} and {@code TimeZone.getDefault()}
     * (the JVM's), and {@code now()} / {@code now(Clock)} on a date-time type, which reads the clock's or the JVM's
     * zone implicitly. A business date comes from {@code AccountingCalendarZoneResolver}; a technical UTC value is
     * written {@code LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)}. Matched by owner name only, so the rule
     * never resolves a call target's class hierarchy (the CI test fork has 1 GiB).
     */
    static final DescribedPredicate<JavaCall<?>> GUESSED_ZONE_CALL =
            new DescribedPredicate<>("read the clock's or the JVM's time zone") {
                @Override
                public boolean test(JavaCall<?> input) {
                    String owner = input.getTargetOwner().getName();
                    String name = input.getName();
                    return switch (owner) {
                        case "java.time.Clock" -> "getZone".equals(name) || "systemDefaultZone".equals(name);
                        case "java.time.ZoneId", "java.time.ZoneOffset" -> "systemDefault".equals(name);
                        case "java.util.TimeZone" -> "getDefault".equals(name);
                        default -> ZONED_NOW_TYPES.contains(owner) && "now".equals(name);
                    };
                }
            };

    /** The one class that turns the clock into a date: in the tenant's zone, {@code now(clock.withZone(zone))}. */
    static final String ZONE_RESOLVER = "com.positivity.accounting.internal.service.AccountingCalendarZoneResolver";

    static final String CUSTOMER_CREDIT = "com.positivity.accounting.internal.entity.CustomerCredit";

    /**
     * The one class that creates a {@code CustomerCredit} (#2554): it refuses one on the CASH walk-in account
     * ({@code CASH_CUSTOMER_CREDIT_NOT_ALLOWED}, #2508) and enqueues its Dr 1090 / Cr 2300 issuance posting in the
     * same transaction.
     */
    static final String CREDIT_ISSUER = "com.positivity.accounting.internal.service.PaymentApplicationServiceImpl";

    /** Any {@code new CustomerCredit(..)}. Matched by owner name only, so no class hierarchy is resolved. */
    static final DescribedPredicate<JavaConstructorCall> CUSTOMER_CREDIT_CONSTRUCTION =
            new DescribedPredicate<>("construct a CustomerCredit") {
                @Override
                public boolean test(JavaConstructorCall input) {
                    return CUSTOMER_CREDIT.equals(input.getTargetOwner().getName());
                }
            };

    // Layer packages. The bank reconciliation core (internal.bankrec) and its adapters (internal.bankfeed.*)
    // carry their own layer sub-packages (SPEC-manual-bank-reconciliation §2.1, #2300); the layering rules
    // below bind them exactly as they bind the module's flat internal.* layers.
    static final String[] CONTROLLER_PACKAGES = {
        "..internal.controller..", "..internal.bankrec.controller..", "..internal.bankfeed..controller.."
    };
    static final String[] REPOSITORY_PACKAGES = {
        "..internal.repository..", "..internal.bankrec.repository..", "..internal.bankfeed..repository.."
    };
    static final String[] ENTITY_PACKAGES = {
        "..internal.entity..", "..internal.bankrec.entity..", "..internal.bankfeed..entity.."
    };

    /**
     * Statement-format and provider libraries: only the file adapter ({@code ..bankfeed.file..}) may import
     * a format library, and no class in this module may import a provider SDK (SPEC §2.1).
     */
    static final String[] FORMAT_AND_PROVIDER_LIBRARY_PACKAGES = {
        "com.plaid..",
        "org.apache.commons.csv..",
        "com.opencsv..",
        "de.siegmar.fastcsv..",
        "com.univocity.parsers..",
        "com.webcohesion.ofx4j..",
        "net.sf.ofx4j..",
        "com.prowidesoftware.."
    };

    /** File access and HTTP clients the reconciliation core never uses: statement bytes reach it only through the intake. */
    static final String[] FILE_IO_AND_HTTP_CLIENT_PACKAGES = {
        "java.nio.file..",
        "java.net.http..",
        "org.springframework.web.client..",
        "org.springframework.web.reactive.function.client..",
        "okhttp3..",
        "org.apache.hc..",
        "org.apache.http..",
        "com.plaid.."
    };

    @ArchTest
    static final ArchRule controllers_should_not_access_repositories_directly = noClasses()
            .that()
            .resideInAnyPackage(CONTROLLER_PACKAGES)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(REPOSITORY_PACKAGES)
            .allowEmptyShould(true)
            .because("controllers must go through service layer");

    @ArchTest
    static final ArchRule controllers_should_not_access_entities_directly = noClasses()
            .that()
            .resideInAnyPackage(CONTROLLER_PACKAGES)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(ENTITY_PACKAGES)
            .allowEmptyShould(true)
            .because("controllers should work with DTOs, not entities");

    @ArchTest
    static final ArchRule services_should_not_depend_on_controllers = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(CONTROLLER_PACKAGES)
            .allowEmptyShould(true)
            .because("services should not depend on web layer");

    @ArchTest
    static final ArchRule entities_should_not_depend_on_services = noClasses()
            .that()
            .resideInAnyPackage(ENTITY_PACKAGES)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..service..")
            .allowEmptyShould(true)
            .because("entities should be independent of business logic");

    @ArchTest
    static final ArchRule repositories_should_only_be_accessed_from_services_or_config = noClasses()
            .that()
            .resideOutsideOfPackages(
                    "..service..",
                    // The intake port's implementation is the core's write service for bank lines
                    // (SPEC §2.1: normalization, upsert, duplicate flagging, statement creation; #2301).
                    "..internal.bankrec.intake..",
                    // The close-readiness read model reads the core's and the ledger's rows directly
                    // (SPEC §2.1 lists readmodel among the core's sub-packages; story S6, #2305).
                    "..internal.bankrec.readmodel..",
                    "..internal.repository..",
                    "..internal.bankrec.repository..",
                    "..internal.bankfeed..repository..",
                    "..internal.config..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(REPOSITORY_PACKAGES)
            .allowEmptyShould(true)
            .because("repositories should only be accessed from service layer");

    @ArchTest
    static final ArchRule config_should_not_depend_on_internal_service_implementations = noClasses()
            .that()
            .resideInAPackage("..internal.config..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..internal.service..")
            .allowEmptyShould(true)
            .because("config should depend on service interfaces, not internal service implementations");

    @ArchTest
    static final ArchRule spring_boot_application_should_be_in_root_package = classes()
            .that()
            .areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
            .should()
            .resideInAPackage("com.positivity.accounting")
            .andShould()
            .resideOutsideOfPackages("..internal..", "..service..")
            .allowEmptyShould(true)
            .because("@SpringBootApplication must be at root for component scanning");

    @ArchTest
    static final ArchRule only_service_layer_should_be_public_api = classes()
            .that()
            .resideInAPackage("com.positivity.accounting.service..")
            .and()
            .areNotAnonymousClasses()
            .and()
            .areNotInnerClasses()
            .should()
            .bePublic()
            .allowEmptyShould(true)
            .because("service layer is the public API of this module");

    @ArchTest
    static final ArchRule service_package_should_only_contain_interfaces = classes()
            .that()
            .resideInAPackage("com.positivity.accounting.service..")
            .and()
            .areNotInterfaces()
            .and()
            .areNotEnums()
            .and()
            .areNotAnnotations()
            .and()
            .areNotAnonymousClasses()
            .and()
            .areNotInnerClasses()
            .should()
            .beInterfaces()
            .allowEmptyShould(true)
            .because("only interfaces are allowed in com.positivity.accounting.service");

    // ADR-0026 D4: the public service package is a grant surface. Grant-surface types may not
    // depend on this module's internal implementation. pos-accounting holds no grant, so this package
    // is empty; the rule (with allowEmptyShould) keeps it honest if a grant is ever added.
    // Package patterns are exact-anchored on purpose: "com.positivity.accounting.service.." must NOT
    // match "com.positivity.accounting.internal.service".
    @ArchTest
    static final ArchRule public_service_surface_should_not_depend_on_internal = noClasses()
            .that()
            .resideInAPackage("com.positivity.accounting.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.positivity.accounting.internal..")
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
                    "com.positivity.accounting.internal.service.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true)
            .because("service package cycles make the module harder to maintain and evolve");

    @ArchTest
    static final ArchRule entities_should_use_uuidv7_id_annotation = classes()
            .that()
            .resideInAnyPackage(
                    "..internal.entity..",
                    "..internal.model..",
                    "..internal.bankrec.entity..",
                    "..internal.bankfeed..entity..")
            .and()
            .areAnnotatedWith("jakarta.persistence.Entity")
            // Replica (Ext*) and idempotency-guard entities carry externally assigned identifiers
            // (owner-domain aggregate ids / envelope eventIds per ADR-0044 R3), so generating a
            // local UUID v7 for them would be incorrect.
            .and()
            .haveSimpleNameNotStartingWith("Ext")
            .and()
            .doNotHaveSimpleName("ProcessedEvent")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.positivity.shared.id.UUIDv7Id")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.positivity.shared.id.UUIDv7Generator")
            // A natural key assigned by the caller (a 1:1 profile keyed by its GL account, a membership row
            // keyed by the rows it links) says so with @AssignedIdentifier, as pos-archunit's
            // EntityStandardsArchitectureTest accepts (#1261).
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.positivity.shared.id.AssignedIdentifier")
            .allowEmptyShould(true)
            .because("ADR-0013 mandates UUID v7 IDs via shared generator strategy");

    @ArchTest
    static final ArchRule entities_should_not_call_uuid_random_uuid = noClasses()
            .that()
            .resideInAnyPackage(
                    "..internal.entity..",
                    "..internal.model..",
                    "..internal.bankrec.entity..",
                    "..internal.bankfeed..entity..")
            .and()
            .areAnnotatedWith("jakarta.persistence.Entity")
            .should()
            .callMethodWhere(UUID_RANDOM_UUID_CALL)
            .allowEmptyShould(true)
            .because("UUIDv7Generator centralizes ID creation; direct randomUUID calls are not allowed");

    @ArchTest
    static final ArchRule posting_dates_use_the_accounting_calendar_zone = noClasses()
            .that()
            .resideInAPackage("com.positivity.accounting..")
            .and()
            .doNotHaveFullyQualifiedName(ZONE_RESOLVER)
            .should()
            .callMethodWhere(GUESSED_ZONE_CALL)
            .because("#2558: a posting date and a period are cut in the tenant's accounting-calendar zone"
                    + " (AccountingCalendarZoneResolver), never in the clock's or the JVM's zone; a technical UTC"
                    + " value states ZoneOffset.UTC explicitly");

    /** The two entry points that post under an override recorded earlier, with no caller to check (CAP:550 S42). */
    static final java.util.Set<String> RECORDED_OVERRIDE_METHODS =
            java.util.Set.of("postJournalEntryWithRecordedOverride", "assertPostingAllowedWithRecordedOverride");

    /** The only classes that may call them: the AP payment's outbox posting, and the journal-entry service it uses. */
    static final java.util.Set<String> RECORDED_OVERRIDE_CALLERS = java.util.Set.of(
            "com.positivity.accounting.internal.service.APPaymentPostingService",
            "com.positivity.accounting.internal.service.JournalEntryServiceImpl");

    private static final DescribedPredicate<JavaCall<?>> RECORDED_OVERRIDE_CALL =
            new DescribedPredicate<>("post under an override recorded earlier") {
                @Override
                public boolean test(JavaCall<?> input) {
                    return RECORDED_OVERRIDE_METHODS.contains(input.getName());
                }
            };

    /**
     * #2641 review (LOW 5; ruling 5 of #2603): an override recorded on the AP pay command is applied without a caller,
     * so only the AP payment's outbox posting may reach it; a {@code gl-posting-retry} or any other path checks the
     * caller's own authority through {@code postJournalEntry}.
     */
    @ArchTest
    static final ArchRule recorded_overrides_are_applied_only_by_the_ap_payment_outbox_posting = noClasses()
            .that()
            .resideInAPackage("com.positivity.accounting..")
            .and(new DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("are not the allowed callers") {
                @Override
                public boolean test(com.tngtech.archunit.core.domain.JavaClass input) {
                    return !RECORDED_OVERRIDE_CALLERS.contains(input.getName());
                }
            })
            .should()
            .callMethodWhere(RECORDED_OVERRIDE_CALL)
            .because("an override recorded on the AP pay command is the payer's authority; only the AP payment's"
                    + " outbox delivery applies it (ruling 5 of #2603; a retry never does, ruling 1 of 2026-10-08)");

    /** CustomerCreditIssuanceRuleTest proves this rule catches a second issuer and passes the real one. */
    @ArchTest
    static final ArchRule customer_credits_are_issued_only_on_the_guarded_posted_path = noClasses()
            .that()
            .resideInAPackage("com.positivity.accounting..")
            .and()
            .doNotHaveFullyQualifiedName(CREDIT_ISSUER)
            .should()
            .callConstructorWhere(CUSTOMER_CREDIT_CONSTRUCTION)
            .because("#2554: a CustomerCredit is created only by PaymentApplicationServiceImpl, which refuses one on"
                    + " the CASH walk-in account (CASH_CUSTOMER_CREDIT_NOT_ALLOWED) and posts its Dr 1090 / Cr 2300"
                    + " issuance in the same transaction");

    // ---- Bank reconciliation core ↔ adapter walls (SPEC-manual-bank-reconciliation §2.1, §8.4; #2300) ----
    // The core (internal.bankrec) is provider- and format-neutral; the adapters (internal.bankfeed.*) reach it
    // only through its intake port and contract DTOs. BankrecWallsFixtureTest proves each rule catches a violation.

    @ArchTest
    static final ArchRule bankrec_must_not_access_bankfeed = noClasses()
            .that()
            .resideInAPackage("..bankrec..")
            .should()
            .dependOnClassesThat(resideInAPackage("..bankfeed..")
                    // The provider-neutral contract (pos-domain-events ..bankfeed..) is the intake type the
                    // core is meant to depend on (SPEC §2.1, #2301); only the module's adapters are walled off.
                    .and(not(resideInAPackage("com.positivity.domainevents.."))))
            .because("SPEC §2.1: the reconciliation core never depends on an adapter; adapters call its intake port");

    /**
     * The period close reaches the reconciliation core only through its close-readiness read model and the
     * read model's DTOs — the one {@code PERIOD --> CORE} edge of SPEC §2 (story S6, #2305).
     */
    @ArchTest
    static final ArchRule period_close_reaches_bankrec_only_through_readmodel = noClasses()
            .that()
            .resideInAPackage("..internal.service..")
            .and()
            .haveSimpleNameStartingWith("AccountingPeriod")
            .should()
            .dependOnClassesThat(resideInAPackage("..bankrec..")
                    .and(not(resideInAnyPackage("..bankrec.readmodel..", "..bankrec.dto.."))))
            .allowEmptyShould(true)
            .because("SPEC §2: PERIOD --> CORE goes through ..bankrec.readmodel.. only");

    @ArchTest
    static final ArchRule bankfeed_may_only_use_intake_and_dto = noClasses()
            .that()
            .resideInAPackage("..bankfeed..")
            .should()
            .dependOnClassesThat(resideInAPackage("..bankrec..")
                    .and(not(resideInAnyPackage("..bankrec.intake..", "..bankrec.dto.."))))
            .allowEmptyShould(true)
            .because("SPEC §2.1: an adapter reaches the core only through ..bankrec.intake.. and ..bankrec.dto..");

    /**
     * The intake port depends only on the rest of the core, the bank-feed contract, the tenancy runtime and
     * the platform libraries (#2301): it never reaches the ledger, the outbox or the audit log directly —
     * those go through {@code ..bankrec.service..} — so the port an adapter calls carries no module-wide
     * coupling.
     */
    @ArchTest
    static final ArchRule bankrec_intake_depends_only_on_core_contract_and_platform = classes()
            .that()
            .resideInAPackage("..bankrec.intake..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "..bankrec..",
                    "com.positivity.domainevents..",
                    "com.positivity.tenancy..",
                    "java..",
                    "javax..",
                    "jakarta..",
                    "org.springframework..",
                    "org.jspecify..",
                    "org.slf4j..",
                    "lombok..")
            .because("SPEC §2.1, #2301: the intake port is part of the core; it depends on ..bankrec.., the"
                    + " pos-domain-events contract and pos-tenancy-common only");

    @ArchTest
    static final ArchRule format_libraries_only_in_bankfeed_file = noClasses()
            .that()
            .resideInAPackage("com.positivity.accounting..")
            .and()
            .resideOutsideOfPackage("..bankfeed.file..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FORMAT_AND_PROVIDER_LIBRARY_PACKAGES)
            .because("SPEC §2.1: statement formats and provider SDKs stay inside the file adapter; the core sees only"
                    + " the provider-neutral contract");

    @ArchTest
    static final ArchRule bankrec_must_not_use_file_io_or_http_clients = noClasses()
            .that()
            .resideInAPackage("..bankrec..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FILE_IO_AND_HTTP_CLIENT_PACKAGES)
            .because("SPEC §2.1: statement bytes reach the core only through the intake; it reads no files and calls"
                    + " no provider");
}
