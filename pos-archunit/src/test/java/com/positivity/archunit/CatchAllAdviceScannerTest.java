package com.positivity.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fixture-driven proof that the ADR-0056 §4 enforcement rejects the shapes it is meant to reject
 * (issue #1717). Before this, {@link GlobalExceptionHandlerEnforcementTest} asserted only that
 * the live tree passes — which says nothing about whether the rule would notice a violation, and
 * is precisely how a {@code ProblemDetail} catch-all survived the build in two modules.
 */
@DisplayName("The ADR-0056 catch-all rule notices the shapes it exists to reject (#1717)")
class CatchAllAdviceScannerTest {

    private static final Set<String> PROVIDERS = Set.of("pos-web-common", "pos-security-common");

    @Test
    @DisplayName("a ProblemDetail catch-all is found and reported as not returning ApiError")
    void problemDetailCatchAllIsNotApiError(@TempDir Path root) throws IOException {
        Path module = moduleWithAdvice(root, "pos-fake-people", """
                @ExceptionHandler(Exception.class)
                public ProblemDetail handleAny(Exception ex) {
                    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "boom");
                }
                """);

        List<CatchAllAdviceScanner.CatchAll> found = CatchAllAdviceScanner.catchAllsIn(module);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().returnType()).isEqualTo("ProblemDetail");
        assertThat(found.getFirst().returnsApiError()).isFalse();
    }

    @Test
    @DisplayName("a raw Map catch-all is also rejected")
    void rawMapCatchAllIsNotApiError(@TempDir Path root) throws IOException {
        Path module = moduleWithAdvice(root, "pos-fake-vendor", """
                @ExceptionHandler(Exception.class)
                public ResponseEntity<Map<String, Object>> handleAny(Exception e) {
                    return ResponseEntity.internalServerError().body(Map.of("error", "boom"));
                }
                """);

        assertThat(CatchAllAdviceScanner.catchAllsIn(module))
                .singleElement()
                .extracting(CatchAllAdviceScanner.CatchAll::returnsApiError)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("an ApiError catch-all satisfies the rule")
    void apiErrorCatchAllIsAccepted(@TempDir Path root) throws IOException {
        Path module = moduleWithAdvice(root, "pos-fake-inventory", """
                @ExceptionHandler(Exception.class)
                public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
                    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error occurred");
                }
                """);

        assertThat(CatchAllAdviceScanner.catchAllsIn(module))
                .singleElement()
                .extracting(CatchAllAdviceScanner.CatchAll::returnsApiError)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("prose mentioning the annotation in a comment is not mistaken for a declaration")
    void tombstoneCommentsAreNotCatchAlls(@TempDir Path root) throws IOException {
        Path module = moduleWithAdvice(root, "pos-fake-people-contact", """
                // No @ExceptionHandler(Exception.class) catch-all here (issue #1694): a module-local
                // blanket handler would shadow ADR-0056 §2's DataIntegrityViolationException mapping.
                /* Historic note: the removed advice was @ExceptionHandler(Exception.class). */
                """);

        assertThat(CatchAllAdviceScanner.catchAllsIn(module)).isEmpty();
    }

    @Test
    @DisplayName("a module with no catch-all is covered only by a dependency on a provider")
    void providerDependencySuppliesCoverage(@TempDir Path root) throws IOException {
        Path withProvider = moduleWithAdvice(root, "pos-fake-covered", "// nothing here\n");
        writePom(withProvider, "pos-security-common");
        Path without = moduleWithAdvice(root, "pos-fake-uncovered", "// nothing here\n");
        writePom(without, "spring-boot-starter-web");

        assertThat(CatchAllAdviceScanner.dependsOnCatchAllProvider(withProvider, PROVIDERS))
                .isTrue();
        assertThat(CatchAllAdviceScanner.dependsOnCatchAllProvider(without, PROVIDERS))
                .isFalse();
    }

    @Test
    @DisplayName("only modules that declare a @RestController are scanned")
    void onlyControllerModulesAreScanned(@TempDir Path root) throws IOException {
        Path withController = moduleWithAdvice(root, "pos-fake-api", "@RestController\nclass C {}\n");
        moduleWithAdvice(root, "pos-fake-library", "class NotAController {}\n");

        assertThat(CatchAllAdviceScanner.controllerModules(root, Set.of())).containsExactly(withController);
    }

    private static Path moduleWithAdvice(Path root, String moduleName, String body) throws IOException {
        Path module = root.resolve(moduleName);
        Path pkg = module.resolve("src/main/java/com/positivity/fake");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Advice.java"), "package com.positivity.fake;\n\n" + body);
        return module;
    }

    private static void writePom(Path module, String dependencyArtifactId) throws IOException {
        Files.writeString(
                module.resolve("pom.xml"),
                "<project><dependencies><dependency><artifactId>" + dependencyArtifactId
                        + "</artifactId></dependency></dependencies></project>");
    }
}
