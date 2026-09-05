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
    @DisplayName("a type merely prefixed ApiError does not satisfy the envelope rule")
    void aTypeNamedLikeApiErrorIsNotApiError(@TempDir Path root) throws IOException {
        Path module = moduleWithAdvice(root, "pos-fake-lookalike", """
                @ExceptionHandler(Exception.class)
                public ResponseEntity<ApiErrorResponse> handleAny(Exception ex) {
                    return ResponseEntity.internalServerError().body(new ApiErrorResponse("boom"));
                }
                """);

        assertThat(CatchAllAdviceScanner.catchAllsIn(module))
                .singleElement()
                .extracting(CatchAllAdviceScanner.CatchAll::returnsApiError)
                .describedAs("ApiErrorResponse is a different type — a substring match would wave it through")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("a fully qualified ApiError still satisfies the envelope rule")
    void aFullyQualifiedApiErrorIsAccepted(@TempDir Path root) throws IOException {
        Path module = moduleWithAdvice(root, "pos-fake-qualified", """
                @ExceptionHandler(Exception.class)
                public org.springframework.http.ResponseEntity<com.positivity.shared.error.ApiError> handleAny(
                        Exception ex) {
                    return null;
                }
                """);

        assertThat(CatchAllAdviceScanner.catchAllsIn(module))
                .singleElement()
                .extracting(CatchAllAdviceScanner.CatchAll::returnsApiError)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("the brace, value= and Throwable spellings are all caught")
    void alternativeCatchAllSpellingsAreDetected(@TempDir Path root) throws IOException {
        // Spring treats all of these as the same catch-all. A rule that only knows the bare form is
        // evadable by adding two characters, which would make the shape assertion worthless.
        Path braces = moduleWithAdvice(root, "pos-fake-braces", """
                @ExceptionHandler({Exception.class})
                public ProblemDetail handleAny(Exception ex) { return null; }
                """);
        Path valueForm = moduleWithAdvice(root, "pos-fake-value", """
                @ExceptionHandler(value = Exception.class)
                public ProblemDetail handleAny(Exception ex) { return null; }
                """);
        Path throwable = moduleWithAdvice(root, "pos-fake-throwable", """
                @ExceptionHandler(Throwable.class)
                public ProblemDetail handleAny(Throwable ex) { return null; }
                """);
        Path multi = moduleWithAdvice(root, "pos-fake-multi", """
                @ExceptionHandler({Exception.class, IllegalStateException.class})
                public ProblemDetail handleAny(Exception ex) { return null; }
                """);

        for (Path module : List.of(braces, valueForm, throwable, multi)) {
            assertThat(CatchAllAdviceScanner.catchAllsIn(module))
                    .describedAs("catch-all in %s", module.getFileName())
                    .singleElement()
                    .extracting(CatchAllAdviceScanner.CatchAll::returnsApiError)
                    .isEqualTo(false);
        }
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
        // A module that only declares an advice serves no endpoints, so ADR-0056 coverage does not
        // apply to it — @RestControllerAdvice must not be read as a @RestController declaration.
        moduleWithAdvice(root, "pos-fake-advice-only", "@RestControllerAdvice\nclass A {}\n");

        assertThat(CatchAllAdviceScanner.controllerModules(root, Set.of())).containsExactly(withController);
    }

    /**
     * The combination #1768 was about: a module that both depends on a provider and declares its
     * own catch-all. Each half is legitimate on its own — the provider dependency is how most
     * modules get coverage, and a standalone catch-all is how a module without the dependency
     * gets it — so the scanner must report the two independently and let the rule judge the pair.
     * An ApiError-shaped catch-all makes this indistinguishable from a compliant module by shape
     * alone, which is how pos-inventory survived the #1717 enforcement.
     */
    @Test
    @DisplayName("a provider dependency and a local catch-all are reported independently")
    void aProviderDependencyAndALocalCatchAllAreBothVisible(@TempDir Path root) throws IOException {
        Path shadowing = moduleWithAdvice(root, "pos-fake-shadowing", """
                @RestController
                class C {}

                @RestControllerAdvice
                class A {
                    @ExceptionHandler(Exception.class)
                    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
                        return null;
                    }
                }
                """);
        writePom(shadowing, "pos-web-common");

        assertThat(CatchAllAdviceScanner.dependsOnCatchAllProvider(shadowing, PROVIDERS))
                .isTrue();
        assertThat(CatchAllAdviceScanner.catchAllsIn(shadowing)).isNotEmpty();
        // Shape-conformant, so the ADR-0017 §3 envelope rule alone would pass it.
        assertThat(CatchAllAdviceScanner.catchAllsIn(shadowing))
                .allMatch(CatchAllAdviceScanner.CatchAll::returnsApiError);
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
