package com.positivity.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guards against vacuous passes of the cross-module ArchUnit rules (#909).
 *
 * <p>When pos-archunit is built outside the Maven reactor (e.g. {@code mvn -pl pos-archunit test}),
 * its module dependencies resolve to the installed Spring Boot repackaged fat jars, whose
 * application classes live under {@code BOOT-INF/classes/} where ArchUnit's classpath importer
 * cannot see them. Every rule then matches zero classes and {@code allowEmptyShould(true)} turns
 * the run into a silent pass. This test fails loudly instead: it asserts that every module
 * dependency contributes at least one class to the imported class set, and that every package
 * enforced by {@link EntityStandardsArchitectureTest} contains at least one {@code @Entity}.
 *
 * <p>If this test fails, run pos-archunit as a reactor build so sibling modules resolve to their
 * {@code target/classes} directories: {@code ./mvnw -pl pos-archunit -am test}.
 */
class ClasspathVisibilityGuardTest {

    private static final String REMEDY = " — ArchUnit cannot see application classes inside Spring Boot repackaged"
            + " fat jars (BOOT-INF/classes). Run pos-archunit as a reactor build so module dependencies"
            + " resolve to target/classes: ./mvnw -pl pos-archunit -am test (see issue #909).";

    private static final String ENTITY_ANNOTATION = "jakarta.persistence.Entity";

    /**
     * Root package contributed by each com.positivity module dependency declared in
     * pos-archunit/pom.xml. Keep in sync with the pom when adding or removing module dependencies.
     */
    private static final Map<String, String> MODULE_ROOT_PACKAGES = new TreeMap<>(Map.ofEntries(
            Map.entry("pos-accounting", "com.positivity.accounting"),
            Map.entry("pos-catalog", "com.positivity.catalog"),
            Map.entry("pos-customer", "com.positivity.customer"),
            Map.entry("pos-event-receiver", "com.positivity.poseventreceiver"),
            Map.entry("pos-events", "com.positivity.events"),
            Map.entry("pos-image", "com.positivity.image"),
            Map.entry("pos-inquiry", "com.positivity.inquiry"),
            Map.entry("pos-inventory", "com.positivity.inventory"),
            Map.entry("pos-invoice", "com.positivity.invoice"),
            Map.entry("pos-location", "com.positivity.location"),
            Map.entry("pos-mcp-server", "com.positivity.mcp"),
            Map.entry("pos-order", "com.positivity.order"),
            Map.entry("pos-people", "com.positivity.people"),
            Map.entry("pos-people-contact", "com.positivity.peoplecontact"),
            Map.entry("pos-security-service", "com.positivity.securityservice"),
            Map.entry("pos-shop-manager", "com.positivity.shopmanager"),
            Map.entry("pos-vehicle-fitment", "com.positivity.vehiclefitment"),
            Map.entry("pos-vehicle-inventory", "com.positivity.vehicle"),
            Map.entry("pos-vehicle-reference-carapi", "com.positivity.vehiclereferencecarapi"),
            Map.entry("pos-vehicle-reference-nhtsa", "com.positivity.nhtsa"),
            Map.entry("pos-workorder", "com.positivity.workorder")));

    private static JavaClasses allClasses;

    @BeforeAll
    static void setup() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.positivity");
    }

    @Test
    void everyModuleDependencyShouldContributeClasses() {
        List<String> hiddenModules = MODULE_ROOT_PACKAGES.entrySet().stream()
                .filter(entry -> allClasses.stream().noneMatch(javaClass -> residesIn(javaClass, entry.getValue())))
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .toList();

        Assertions.assertTrue(
                hiddenModules.isEmpty(),
                () -> "No classes imported from module dependencies: " + String.join(", ", hiddenModules) + REMEDY);
    }

    @Test
    void everyEnforcedEntityPackageShouldContainEntities() {
        List<String> emptyPackages = List.of(EntityStandardsArchitectureTest.ENFORCED_ENTITY_PACKAGES).stream()
                .map(pattern -> pattern.substring(0, pattern.length() - "..".length()))
                .filter(packagePrefix -> allClasses.stream()
                        .noneMatch(javaClass ->
                                residesIn(javaClass, packagePrefix) && javaClass.isAnnotatedWith(ENTITY_ANNOTATION)))
                .toList();

        Assertions.assertTrue(
                emptyPackages.isEmpty(),
                () -> "No @Entity classes imported from enforced entity packages: "
                        + String.join(", ", emptyPackages)
                        + ". Either the module's classes are invisible" + REMEDY
                        + " Or the module no longer has entities — then remove the package from"
                        + " EntityStandardsArchitectureTest.ENFORCED_ENTITY_PACKAGES.");
    }

    private static boolean residesIn(JavaClass javaClass, String packagePrefix) {
        String packageName = javaClass.getPackageName();
        return packageName.equals(packagePrefix) || packageName.startsWith(packagePrefix + ".");
    }
}
