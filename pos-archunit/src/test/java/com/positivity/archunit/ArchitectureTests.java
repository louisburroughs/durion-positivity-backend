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
import java.util.TreeMap;
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
                .resideOutsideOfPackages("..internal.service..", "..internal.config..")
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
                        if (isAllowlistedSource(file)) {
                            continue;
                        }
                        for (String literal : concatenatedStringLiterals(Files.readString(file))) {
                            if (writesDatabaseTime(literal)) {
                                violations.add(repositoryRoot.relativize(file) + ": " + literal.trim());
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
    private static List<String> concatenatedStringLiterals(String source) {
        List<String> chains = new ArrayList<>();
        StringBuilder chain = new StringBuilder();
        boolean chainOpen = false;
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
                        chains.add(chain.toString());
                    }
                    chain = new StringBuilder(value);
                }
                index = close;
                chainOpen = continuesConcatenation(source, index);
                continue;
            }

            index++;
        }

        if (chain.length() > 0) {
            chains.add(chain.toString());
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

    private static boolean isAllowlistedSource(Path file) {
        String className = file.getFileName().toString().replace(".java", "");
        return DATABASE_TIME_WRITE_ALLOWLIST.keySet().stream().anyMatch(entry -> entry.contains("." + className + "."));
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
