package com.positivity.supplier.internal.adapter.michelins2s;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reusability claim, asserted rather than assumed (CAP-323 #1229).
 *
 * <h2>What the claim is</h2>
 *
 * The architecture says a new vendor costs an adapter plus configuration, and nothing else. Michelin
 * S2S is the first test of it that means anything: every other adapter in this module speaks
 * EDIWheel, so a design that quietly assumed EDIWheel would have looked reusable right up until it
 * was not.
 *
 * <h2>What actually had to change outside this package</h2>
 *
 * Two things, both in the shared transport, and both because no EDIWheel norm had ever needed them:
 *
 * <ul>
 *   <li>{@code SupplierHttpResponse} gained an allowlisted response-header map. The S2S 202
 *       acceptance carries its whole meaning in {@code Location}, and the transport used to discard
 *       every response header.
 *   <li>{@code SupplierRequestSpec} and {@code SupplierHttpRequest} gained a per-request header map,
 *       for {@code API-Version}. The EDIWheel norms identify the buyer through credentials and query
 *       parameters, so no codec had ever needed to set a header.
 * </ul>
 *
 * <p>Both are capability-neutral additions to the transport, not Michelin-shaped ones — any protocol
 * with an asynchronous acceptance or a version header now has them. The orchestration, the registry,
 * the profile model and every consuming domain were untouched, which is the part of the claim that
 * mattered. Recording the exceptions is the point: a reusability claim with no stated cost has not
 * been tested, it has been asserted.
 *
 * <h2>What this test pins</h2>
 *
 * That vendor knowledge stays here. If a Michelin path or wire type ever appears outside this
 * package, the next protocol family will not be a matter of adding a package.
 */
@DisplayName("Michelin S2S — vendor knowledge stays in its adapter (#1229)")
class MichelinS2SReusabilityTest {

    private static final String ADAPTER_PACKAGE = "com.positivity.supplier.internal.adapter.michelins2s";

    private static JavaClasses supplierClasses;

    @BeforeAll
    static void importClasses() {
        supplierClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.positivity.supplier");

        // Guards against the rule below passing vacuously on an empty import, which is how an
        // architecture test comes to protect nothing at all.
        assertThat(supplierClasses).isNotEmpty();
        assertThat(supplierClasses.stream()
                        .filter(clazz -> clazz.getPackageName().equals(ADAPTER_PACKAGE))
                        .count())
                .as("the adapter package was imported")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the vendor's wire records never leave the adapter package")
    void wireTypesStayInTheAdapter() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that()
                .resideOutsideOfPackage(ADAPTER_PACKAGE)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(ADAPTER_PACKAGE + ".WorkorderS2SWire")
                .because("a vendor type outside its adapter is a vendor shape in the domain, and the next "
                        + "vendor then has to match this one's JSON");

        rule.check(supplierClasses);
    }

    @Test
    @DisplayName("the vendor's URL paths appear only in the adapter")
    void vendorPathsStayInTheAdapter() throws IOException {
        Path mainSources = Path.of("src/main/java/com/positivity/supplier");
        assertThat(Files.isDirectory(mainSources))
                .as("source tree found at %s", mainSources.toAbsolutePath())
                .isTrue();

        List<String> offenders;
        try (Stream<Path> sources = Files.walk(mainSources)) {
            offenders = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("michelins2s"))
                    .filter(MichelinS2SReusabilityTest::mentionsAVendorPath)
                    .map(Path::toString)
                    .toList();
        }

        // The whole reusability argument in one assertion: if the orchestration knows the vendor's
        // URLs, then a second vendor is a change to the orchestration, not a new package.
        assertThat(offenders)
                .as("files outside the adapter that hard-code a Michelin S2S path")
                .isEmpty();
    }

    private static boolean mentionsAVendorPath(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return source.contains("/api/v1/workOrders")
                    || source.contains("/api/v1/vehicles")
                    || source.contains("/api/v1/contracts")
                    || source.contains("/api/v1/policies");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
