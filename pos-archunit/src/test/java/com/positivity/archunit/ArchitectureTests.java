package com.positivity.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    private static final DescribedPredicate<JavaCall<?>> NO_ARG_NOW_CALLS =
            new DescribedPredicate<>("call no-arg Instant/LocalDateTime now methods") {
                @Override
                public boolean test(JavaCall<?> input) {
                    if (!"now".equals(input.getName())) {
                        return false;
                    }
                    boolean supportedOwner = input.getTargetOwner().isEquivalentTo(Instant.class)
                            || input.getTargetOwner().isEquivalentTo(LocalDateTime.class)
                            || input.getTargetOwner().isEquivalentTo(LocalDate.class)
                            || input.getTargetOwner().isEquivalentTo(LocalTime.class)
                            || input.getTargetOwner().isEquivalentTo(OffsetDateTime.class)
                            || input.getTargetOwner().isEquivalentTo(ZonedDateTime.class);
                    return supportedOwner
                            && input.getTarget().getRawParameterTypes().isEmpty();
                }
            };
    private static final DescribedPredicate<JavaCall<?>> CLOCK_SYSTEM_UTC_CALLS =
            new DescribedPredicate<>("call Clock.systemUTC() or Clock.systemDefaultZone()") {
                @Override
                public boolean test(JavaCall<?> input) {
                    return ("systemUTC".equals(input.getName()) || "systemDefaultZone".equals(input.getName()))
                            && input.getTargetOwner().isEquivalentTo(Clock.class)
                            && input.getTarget().getRawParameterTypes().isEmpty();
                }
            };

    /** Hibernate generates these itself and never consults the Spring {@code Clock} bean. */
    private static final List<String> HIBERNATE_TIMESTAMP_ANNOTATIONS = List.of(
            "org.hibernate.annotations.CreationTimestamp",
            "org.hibernate.annotations.UpdateTimestamp",
            "org.hibernate.annotations.CurrentTimestamp");

    /** A Java string literal, escapes included. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    /**
     * SQL functions that read the database server's clock rather than the application clock.
     */
    private static final Pattern DATABASE_TIME_FUNCTION = Pattern.compile(
            "\\b(now\\s*\\(|current_timestamp|clock_timestamp\\s*\\(|localtimestamp|current_date)",
            Pattern.CASE_INSENSITIVE);

    /** Statements that write a value; a WHERE-clause comparison against database time is fine. */
    private static final Pattern SQL_WRITE_STATEMENT =
            Pattern.compile("\\b(insert\\s+into|update)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Write statements allowed to read database time, each with the reason it is deliberate.
     *
     * <p>Keyed by fully-qualified method name. An entry with a blank justification fails the test:
     * an allowlist without a reason is how the rule quietly stops meaning anything.
     */
    private static final Map<String, String> DATABASE_TIME_WRITE_ALLOWLIST = Map.of(
            "com.positivity.supplier.internal.repository.SupplierScheduleLeaseRepository.claim",
            "leased_until/last_heartbeat_at/last_run_started_at are real-time liveness values;"
                    + " accelerating them would expire live leases and let two runs share a binding",
            "com.positivity.supplier.internal.repository.SupplierScheduleLeaseRepository.heartbeat",
            "leased_until/last_heartbeat_at are real-time liveness values; see claim");

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
                .that()
                .resideInAPackage("com.positivity..")
                .and()
                .resideOutsideOfPackages("..internal..")
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

                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
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
                .that()
                .resideInAPackage("..internal.controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..internal.repository..")
                .because("controllers must go through service layer - no direct repository access");

        // Allow empty check - some modules may not have controllers yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void controllersShouldNotDirectlyAccessEntities() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..internal.controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..internal.entity..")
                .because("controllers should work with DTOs - no direct entity access");

        // Allow empty check - some modules may not have controllers yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void repositoriesShouldOnlyBeAccessedFromServiceLayer() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackages(
                        "..service..", "..dao..", "..internal.dao..", "..internal.repository..", "..internal.config..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..internal.repository..")
                .because("repositories should only be accessed from service/dao layers");

        // Allow empty check - some modules may not have repositories yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void entitiesShouldNotDependOnServices() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..internal.entity..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..service..")
                .because("entities should be independent of business logic - no service dependencies");

        // Allow empty check - some modules may not have entities yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void onlyServicePackagesShouldBePublic() {
        ArchRule rule = classes()
                .that()
                .resideInAPackage("..internal..")
                .and()
                .arePublic()
                .and()
                // Configuration-properties holders are not service implementations. A nested
                // binding type such as TaxProperties.ExternalService names the thing it configures,
                // which is exactly what this rule is not about.
                // Subdomain-split service packages (internal.{subdomain}.service, e.g.
                // pos-supplier's internal.order.service) are legitimate homes for service
                // interfaces beside their implementations per ADR-0026 D3 (issue #1541).
                .resideOutsideOfPackages("..internal.service..", "..internal.*.service..", "..internal.config..")
                .should()
                .haveSimpleNameNotEndingWith("Service")
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
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..internal.controller..")
                .because("services should not depend on controllers - inverted dependency");

        // Allow empty check - some modules may not have services yet
        rule.allowEmptyShould(true).check(allClasses);
    }

    @Test
    void dtosInInternalPackageShouldOnlyBeUsedWithinModule() {
        ArchRule rule = classes()
                .that()
                .resideInAPackage("com.positivity..")
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

                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                        }
                    }
                })
                .because("internal DTOs should not leak across module boundaries");

        rule.check(allClasses);
    }

    @Test
    void springBootApplicationClassesShouldBeInRootPackage() {
        ArchRule rule = classes()
                .that()
                .areAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
                .should()
                .resideInAPackage("com.positivity.(*)")
                .andShould()
                .resideOutsideOfPackages("..internal..", "..service..")
                .because("@SpringBootApplication classes must be at root package for proper component scanning");

        rule.check(allClasses);
    }

    @Test
    void productionCodeShouldNotUseNoArgNowCalls() {
        ArchRule rule = noClasses()
                .should()
                .callMethodWhere(NO_ARG_NOW_CALLS)
                .because("time access must use explicit Clock injection or explicit Clock argument");

        rule.check(allClasses);
    }

    @Test
    void productionCodeShouldNotCallClockSystemUtcOutsideSharedTimeInfrastructure() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackages("com.positivity.time..", "com.positivity.events..")
                .should()
                .callMethodWhere(CLOCK_SYSTEM_UTC_CALLS)
                .because(
                        "application time must flow through shared Clock/TimeSource so the accelerated profile can replace it");

        rule.check(allClasses);
    }

    @Test
    void dtoSuffixMigrationReport() {
        List<JavaClass> dtoClasses = allClasses.stream()
                .filter(javaClass -> javaClass.getPackageName().contains(".internal."))
                .filter(javaClass -> {
                    String simpleName = javaClass.getSimpleName();
                    return simpleName.contains("Dto") || simpleName.contains("DTO");
                })
                .sorted(Comparator.comparing(JavaClass::getFullName))
                .toList();
        long publicCount = dtoClasses.stream()
                .filter(javaClass -> javaClass.getModifiers().contains(JavaModifier.PUBLIC))
                .count();

        Map<String, Long> moduleCounts = dtoClasses.stream()
                .collect(Collectors.groupingBy(
                        javaClass -> {
                            String module = moduleName(javaClass.getPackageName());
                            return module == null ? "unknown" : module;
                        },
                        TreeMap::new,
                        Collectors.counting()));

        System.out.println("[ArchUnit][DTO Migration] Internal classes containing Dto/DTO: " + dtoClasses.size()
                + " (public=" + publicCount + ")");
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

    /**
     * ADR-0026 D5 enforcement switch (#1541). {@code false} = report mode: the rule prints the
     * per-module leak census but never fails the build. Once the #1541 migration has moved every
     * ungranted service interface to {@code internal.service}, flip this single line to
     * {@code true} and the rule gates at <strong>zero</strong> — there is deliberately no
     * threshold parameter to loosen.
     */
    private static final boolean D5_ENFORCED = false;

    /**
     * ADR-0026 D4 grant-surface census (#1541): the exact set of granted types, keyed by fully
     * qualified name, each entry carrying the amendment that granted it. Mirrors the explicit
     * grant maps in {@link DomainWallsTest} — an entry without a justification is how a grant
     * list quietly stops meaning anything.
     *
     * <p>Today the platform has exactly one granted type: {@code SupplierStockService}, per the
     * ADR-0044 amendment dated 2026-08-10 ("Live supplier stock inquiry is the single approved
     * synchronous cross-module supplier read"). Adding an entry here requires an ADR amendment.
     */
    private static final Map<String, String> GRANTED_GRANT_SURFACE_TYPES = Map.of(
            "com.positivity.supplier.service.SupplierStockService",
            "sole granted type — ADR-0044 amendment 2026-08-10: live supplier stock inquiry is the"
                    + " single approved synchronous cross-module supplier read");

    /** Package roots a granted grant-surface type may depend on besides its own grant surface. */
    private static final List<String> GRANT_SURFACE_ALLOWED_SHARED_ROOTS = List.of(
            "com.positivity.shared", // pos-shared-dtos
            "com.positivity.domainevents" // pos-domain-events
            );

    /**
     * ADR-0026 D5 producer-side rule (#1541): no type in a module's PUBLIC service package may
     * depend on that same module's {@code internal.*} packages. The public surface is
     * {@code com.positivity.<module>.service} and {@code com.positivity.<module>.service.model}
     * only — resolved from the module root via {@link #publicServiceModuleOf(JavaClass)}, never
     * via a {@code ..service..} wildcard, because that wildcard also matches
     * {@code ..internal.service..} and would flag every implementation class in the platform.
     *
     * <p><strong>Report mode</strong> while the migration is in flight: prints the per-module
     * census (leaking types and leaked imports) that the #1541 waves burn down. Flipping
     * {@link #D5_ENFORCED} makes it build-failing at zero after migration.
     */
    @Test
    void publicServicePackagesShouldNotDependOnOwnInternalPackages() {
        Map<String, Set<String>> leakingTypesByModule = new TreeMap<>();
        Map<String, Integer> leakedImportsByModule = new TreeMap<>();
        Map<String, Integer> leakedImportsByInternalArea = new TreeMap<>();
        List<String> violationDetails = new ArrayList<>();
        int publicServiceTypeCount = 0;

        for (JavaClass origin : allClasses) {
            String module = publicServiceModuleOf(origin);
            if (module == null) {
                continue;
            }
            publicServiceTypeCount++;
            String internalRoot = "com.positivity." + module + ".internal";
            Set<String> leakedTargets = new TreeSet<>();
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass targetClass = dependency.getTargetClass();
                String targetPackage = targetClass.getPackageName();
                if (!targetPackage.equals(internalRoot) && !targetPackage.startsWith(internalRoot + ".")) {
                    continue;
                }
                if (leakedTargets.add(targetClass.getName())) {
                    leakedImportsByInternalArea.merge(internalArea(internalRoot, targetPackage), 1, Integer::sum);
                    violationDetails.add(origin.getName() + " -> " + targetClass.getName());
                }
            }
            if (!leakedTargets.isEmpty()) {
                leakingTypesByModule
                        .computeIfAbsent(module, key -> new TreeSet<>())
                        .add(origin.getName());
                leakedImportsByModule.merge(module, leakedTargets.size(), Integer::sum);
            }
        }

        int totalLeakingTypes =
                leakingTypesByModule.values().stream().mapToInt(Set::size).sum();
        int totalLeakedImports = leakedImportsByModule.values().stream()
                .mapToInt(Integer::intValue)
                .sum();

        StringBuilder report = new StringBuilder("[ArchUnit][ADR-0026 D5] Public service/service.model types depending"
                + " on their own module's internal.* (issue #1541):\n");
        leakingTypesByModule.forEach((module, types) -> report.append("[ArchUnit][ADR-0026 D5]   module=")
                .append(module)
                .append(" leakingTypes=")
                .append(types.size())
                .append(" leakedImports=")
                .append(leakedImportsByModule.get(module))
                .append('\n'));
        report.append("[ArchUnit][ADR-0026 D5]   leaked imports by internal area: ")
                .append(leakedImportsByInternalArea)
                .append('\n');
        report.append("[ArchUnit][ADR-0026 D5] TOTAL publicServiceTypes=")
                .append(publicServiceTypeCount)
                .append(" leakingTypes=")
                .append(totalLeakingTypes)
                .append(" leakedImports=")
                .append(totalLeakedImports);
        System.out.println(report);

        if (D5_ENFORCED) {
            Assertions.assertTrue(
                    violationDetails.isEmpty(),
                    "ADR-0026 D5: public service packages must not depend on their own module's internal"
                            + " packages. Offenders:\n" + String.join("\n", violationDetails));
        }
    }

    /**
     * ADR-0026 D4 regression guard (#1541), build-failing: every granted grant-surface type in
     * {@link #GRANTED_GRANT_SURFACE_TYPES} may depend only on its own public grant surface
     * ({@code <module>.service} / {@code <module>.service.model}), the shared contract libraries
     * (pos-shared-dtos, pos-domain-events), and non-platform types (JDK, annotations). In
     * particular it may not reach any {@code com.positivity} internal package or another
     * module's packages.
     */
    @Test
    void grantedGrantSurfaceTypesShouldOnlyDependOnGrantSurfaceAndSharedContracts() {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, String> granted : GRANTED_GRANT_SURFACE_TYPES.entrySet()) {
            Assertions.assertFalse(
                    granted.getValue() == null || granted.getValue().isBlank(),
                    "Grant entry " + granted.getKey() + " must record the ADR amendment that granted it");
            Assertions.assertTrue(
                    allClasses.contain(granted.getKey()),
                    "Granted grant-surface type " + granted.getKey()
                            + " no longer exists — update GRANTED_GRANT_SURFACE_TYPES (and the granting ADR)");

            JavaClass grantedType = allClasses.get(granted.getKey());
            String module = publicServiceModuleOf(grantedType);
            Assertions.assertNotNull(
                    module,
                    "Granted type " + granted.getKey()
                            + " must live in its module's public service package (ADR-0026 D1)");

            String publicRoot = "com.positivity." + module + ".service";
            for (Dependency dependency : grantedType.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName();
                if (isAllowedGrantSurfaceDependency(targetPackage, publicRoot)) {
                    continue;
                }
                violations.add(granted.getKey() + " -> "
                        + dependency.getTargetClass().getName() + " (" + dependency.getDescription() + ")");
            }
        }

        Assertions.assertTrue(
                violations.isEmpty(),
                "ADR-0026 D4: granted grant-surface types may depend only on their own service.model,"
                        + " pos-shared-dtos, pos-domain-events, and non-platform types. Offenders:\n"
                        + String.join("\n", violations));
    }

    /** Whether a granted grant-surface type may depend on a class in this package (ADR-0026 D4). */
    private static boolean isAllowedGrantSurfaceDependency(String targetPackage, String publicRoot) {
        if (!targetPackage.startsWith("com.positivity.")) {
            // JDK and third-party types (annotations, etc.) — not platform surface.
            return true;
        }
        if (targetPackage.contains(".internal.") || targetPackage.endsWith(".internal")) {
            return false;
        }
        if (targetPackage.equals(publicRoot)
                || targetPackage.equals(publicRoot + ".model")
                || targetPackage.startsWith(publicRoot + ".model.")) {
            return true;
        }
        return GRANT_SURFACE_ALLOWED_SHARED_ROOTS.stream()
                .anyMatch(root -> targetPackage.equals(root) || targetPackage.startsWith(root + "."));
    }

    /**
     * The module root when this class sits in a module's PUBLIC service surface, else
     * {@code null}. Anchored exactly: the package must be {@code com.positivity.<root>.service},
     * {@code com.positivity.<root>.service.model}, or below {@code service.model} — where
     * {@code <root>} is the single package segment directly after {@code com.positivity}. A
     * package such as {@code com.positivity.supplier.internal.service} therefore never matches
     * (its module root is {@code supplier}, and its package is not
     * {@code com.positivity.supplier.service}); the explicit {@code .internal.} check is
     * belt-and-braces on top of that.
     */
    private static String publicServiceModuleOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (packageName.contains(".internal.") || packageName.endsWith(".internal")) {
            return null;
        }
        String module = moduleName(packageName);
        if (module == null) {
            return null;
        }
        String publicRoot = "com.positivity." + module + ".service";
        boolean isPublicSurface = packageName.equals(publicRoot)
                || packageName.equals(publicRoot + ".model")
                || packageName.startsWith(publicRoot + ".model.");
        return isPublicSurface ? module : null;
    }

    /** First package segment under {@code <module>.internal}, for the leak census breakdown. */
    private static String internalArea(String internalRoot, String targetPackage) {
        if (targetPackage.equals(internalRoot)) {
            return "(root)";
        }
        String remainder = targetPackage.substring(internalRoot.length() + 1);
        int nextDot = remainder.indexOf('.');
        return "internal." + (nextDot < 0 ? remainder : remainder.substring(0, nextDot));
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

    @Test
    void productionCodeShouldNotUseHibernateTimestampGenerators() {
        for (String annotation : HIBERNATE_TIMESTAMP_ANNOTATIONS) {
            ArchRule rule = noFields()
                    .should()
                    .beAnnotatedWith(annotation)
                    .because("Hibernate generates " + annotation + " values itself and never consults the Spring"
                            + " Clock bean, so the field would carry wall time while the rest of the row carries"
                            + " application time; use @CreatedDate/@LastModifiedDate instead");
            rule.allowEmptyShould(true).check(allClasses);
        }
    }

    /**
     * The bytecode rules above cannot see SQL written as text. This reads the {@code @Query}
     * annotation values, which is exactly how the pos-supplier lease writes escaped earlier review.
     */
    @Test
    void queryAnnotationsShouldNotWriteDatabaseTime() {
        List<String> violations = new ArrayList<>();

        allClasses.forEach(javaClass -> javaClass
                .getMethods()
                .forEach(method -> method.getAnnotations().stream()
                        .filter(annotation -> annotation.getRawType().getName().endsWith("Query"))
                        .forEach(annotation -> annotation.get("value").ifPresent(value -> {
                            String sql = String.valueOf(value);
                            String fullName = javaClass.getName() + "." + method.getName();
                            if (writesDatabaseTime(sql) && !DATABASE_TIME_WRITE_ALLOWLIST.containsKey(fullName)) {
                                violations.add(fullName + " writes database time: " + sql);
                            }
                        }))));

        Assertions.assertTrue(
                violations.isEmpty(),
                "@Query write statements must bind the application clock rather than reading database time."
                        + " Offenders:\n" + String.join("\n", violations));
    }

    /**
     * ArchUnit reads annotation values but not arbitrary string literals, so this walks the source
     * instead. It is what catches {@code entityManager.createNativeQuery(...)} and SQL assembled from
     * concatenated constants, which the bytecode rules miss entirely.
     */
    /**
     * ArchUnit reads annotation values but not arbitrary string literals, so this walks the source
     * instead. It is what catches {@code entityManager.createNativeQuery(...)} and SQL assembled from
     * concatenated constants, which the bytecode rules miss entirely.
     */
    @Test
    void sourceLevelSqlShouldNotWriteDatabaseTime() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> modules = Files.list(repositoryRoot)) {
            List<Path> sourceRoots = modules.filter(
                            path -> path.getFileName().toString().startsWith("pos-"))
                    .map(path -> path.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .toList();

            for (Path sourceRoot : sourceRoots) {
                try (Stream<Path> files = Files.walk(sourceRoot)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                            .toList()) {
                        String source = Files.readString(file);
                        for (LiteralChain chain : concatenatedStringLiterals(source)) {
                            if (writesDatabaseTime(chain.value()) && !isAllowlistedLiteral(file, source, chain)) {
                                violations.add(repositoryRoot.relativize(file) + ": "
                                        + chain.value().trim());
                            }
                        }
                    }
                }
            }
        }

        Assertions.assertTrue(
                violations.isEmpty(),
                "SQL written as string literals must bind the application clock rather than reading database"
                        + " time. Offenders:\n" + String.join("\n", violations));
    }

    /**
     * Every string literal in the source, with {@code +}-joined chains flattened into one value.
     *
     * <p>Flattening matters: a multi-line native query keeps its UPDATE keyword in the first literal
     * and its {@code now()} in a later one, so checking literals individually would see neither
     * together. Scanned character by character rather than by regex — a literal pattern backtracks
     * catastrophically on large sources, and this also skips comments and handles text blocks.
     */
    /** A flattened literal chain with the source offsets it spans, for method attribution. */
    private record LiteralChain(String value, int start, int end) {}

    private static List<LiteralChain> concatenatedStringLiterals(String source) {
        List<LiteralChain> chains = new ArrayList<>();
        StringBuilder chain = new StringBuilder();
        boolean chainOpen = false;
        int chainStart = 0;
        int chainEnd = 0;
        int index = 0;

        while (index < source.length()) {
            char current = source.charAt(index);

            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    index = endOfLineComment(source, index);
                    continue;
                }
                if (next == '*') {
                    index = endOfBlockComment(source, index);
                    continue;
                }
            }

            if (current == '"') {
                boolean textBlock = source.startsWith("\"\"\"", index);
                int close = textBlock ? endOfTextBlock(source, index) : endOfStringLiteral(source, index);
                String value = textBlock
                        ? source.substring(index + 3, Math.max(index + 3, close - 3))
                        : source.substring(index + 1, Math.max(index + 1, close - 1));
                if (chainOpen) {
                    chain.append(value);
                } else {
                    if (chain.length() > 0) {
                        chains.add(new LiteralChain(chain.toString(), chainStart, chainEnd));
                    }
                    chain = new StringBuilder(value);
                    chainStart = index;
                }
                chainEnd = close;
                index = close;
                chainOpen = continuesConcatenation(source, index);
                continue;
            }

            index++;
        }

        if (chain.length() > 0) {
            chains.add(new LiteralChain(chain.toString(), chainStart, chainEnd));
        }
        return chains;
    }

    /** Whether only whitespace and a {@code +} separate this position from the next literal. */
    private static boolean continuesConcatenation(String source, int index) {
        int cursor = index;
        boolean sawPlus = false;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
            } else if (current == '+' && !sawPlus) {
                sawPlus = true;
                cursor++;
            } else {
                return sawPlus && current == '"';
            }
        }
        return false;
    }

    private static int endOfStringLiteral(String source, int start) {
        int cursor = start + 1;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (current == '\\') {
                cursor += 2;
                continue;
            }
            if (current == '"' || current == '\n') {
                return cursor + 1;
            }
            cursor++;
        }
        return source.length();
    }

    private static int endOfTextBlock(String source, int start) {
        int close = source.indexOf("\"\"\"", start + 3);
        return close < 0 ? source.length() : close + 3;
    }

    private static int endOfLineComment(String source, int start) {
        int newline = source.indexOf('\n', start);
        return newline < 0 ? source.length() : newline + 1;
    }

    private static int endOfBlockComment(String source, int start) {
        int close = source.indexOf("*/", start + 2);
        return close < 0 ? source.length() : close + 2;
    }

    @Test
    void databaseTimeAllowlistEntriesShouldCarryAJustification() {
        DATABASE_TIME_WRITE_ALLOWLIST.forEach((method, justification) -> Assertions.assertFalse(
                justification == null || justification.isBlank(),
                "Allowlist entry " + method + " must record why reading database time is deliberate"));
    }

    /**
     * True when the text contains a write statement that also reads database time. A read-side
     * comparison in a WHERE clause is deliberately allowed: with application time trailing wall time
     * those filters widen the active set rather than hiding rows.
     */
    private static boolean writesDatabaseTime(String sql) {
        if (!SQL_WRITE_STATEMENT.matcher(sql).find()) {
            return false;
        }
        Matcher matcher = DATABASE_TIME_FUNCTION.matcher(sql);
        while (matcher.find()) {
            String preceding = sql.substring(0, matcher.start()).toLowerCase(Locale.ROOT);
            int lastWrite = Math.max(preceding.lastIndexOf("insert into"), preceding.lastIndexOf("update"));
            int lastWhere = preceding.lastIndexOf("where");
            // Only flag a database-time read that belongs to the write half of the statement.
            if (lastWrite >= 0 && lastWhere < lastWrite) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this literal chain belongs to a method the allowlist names. The allowlist is
     * method-keyed, so the exemption must be too: a chain is exempt only when it sits in an
     * annotation on an allowlisted method's declaration (the declaration text between the chain
     * and the semicolon or brace that ends it names the method), or inside that method's body. Any
     * other
     * database-time write in the same file still fails.
     */
    private static boolean isAllowlistedLiteral(Path file, String source, LiteralChain chain) {
        String className = file.getFileName().toString().replace(".java", "");
        List<String> methods = DATABASE_TIME_WRITE_ALLOWLIST.keySet().stream()
                .filter(entry -> entry.contains("." + className + "."))
                .map(entry -> entry.substring(entry.lastIndexOf('.') + 1))
                .toList();
        for (String method : methods) {
            if (declarationAfter(source, chain.end()).matches("(?s).*\\b" + Pattern.quote(method) + "\\s*\\(.*")
                    || isWithinMethodBody(source, method, chain.start())) {
                return true;
            }
        }
        return false;
    }

    /** The declaration text from an annotation's literal to the {@code ;} or body that ends it. */
    private static String declarationAfter(String source, int offset) {
        for (int cursor = offset; cursor < source.length(); cursor++) {
            char current = source.charAt(cursor);
            if (current == ';' || current == '{') {
                return source.substring(offset, cursor);
            }
        }
        return source.substring(offset);
    }

    /** Whether the offset lies inside the brace-matched body of a method with this name. */
    private static boolean isWithinMethodBody(String source, String method, int offset) {
        Matcher declarations =
                Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(source);
        while (declarations.find()) {
            int parameters = source.indexOf('(', declarations.start());
            int close = matchDelimiter(source, parameters, '(', ')');
            if (close < 0) {
                continue;
            }
            // The next structural token decides: '{' opens a body, ';' is abstract or a call.
            int bodyStart = -1;
            for (int cursor = close + 1; cursor < source.length(); cursor++) {
                char current = source.charAt(cursor);
                if (current == '{') {
                    bodyStart = cursor;
                    break;
                }
                if (current == ';' || current == ')' || current == ',') {
                    break;
                }
            }
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = matchDelimiter(source, bodyStart, '{', '}');
            if (bodyEnd > bodyStart && offset > bodyStart && offset < bodyEnd) {
                return true;
            }
        }
        return false;
    }

    /** Index of the closing delimiter matching the opener at {@code open}, or -1. */
    private static int matchDelimiter(String source, int open, char opener, char closer) {
        int depth = 0;
        for (int cursor = open; cursor < source.length(); cursor++) {
            char current = source.charAt(cursor);
            if (current == opener) {
                depth++;
            } else if (current == closer) {
                depth--;
                if (depth == 0) {
                    return cursor;
                }
            }
        }
        return -1;
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("pos-archunit"))) {
            current = current.getParent();
        }
        Assertions.assertNotNull(
                current,
                "could not locate the repository root from " + Paths.get("").toAbsolutePath());
        return current;
    }
}
