package com.positivity.archunit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Enforces issue #1471's acceptance criterion: every module with REST controllers has a
 * catch-all exception handler — enforced rather than remembered.
 *
 * <p>Without one, any unmapped exception escapes as Spring's bare default 500 page with no
 * {@code code}, no {@code message} and no {@code correlationId}, violating ADR-0017 §3/§4
 * (every non-2xx body carries the ApiError envelope and a correlation id). A module
 * satisfies the rule by either:
 *
 * <ul>
 *   <li>depending (directly, or transitively via pos-security-common) on pos-web-common,
 *       whose auto-configured {@code GlobalApiExceptionHandler} provides the platform
 *       catch-all at lowest precedence, or</li>
 *   <li>declaring its own {@code @ExceptionHandler(Exception.class)} advice (for example
 *       pos-security-service, which cannot depend on pos-security-common).</li>
 * </ul>
 *
 * <p>This test scans module source trees and poms on disk rather than the ArchUnit classpath
 * import: pos-archunit's classpath only covers the modules declared in its pom, and this rule
 * must hold for every module in the reactor.
 */
class GlobalExceptionHandlerEnforcementTest {

    /**
     * Modules whose controllers are exempt from the servlet-MVC catch-all requirement.
     * pos-api-gateway is a WebFlux application: the servlet {@code @ControllerAdvice} in
     * pos-web-common does not apply, and gateway error rendering is its own concern
     * (ADR-0011 security boundary).
     */
    private static final Set<String> EXEMPT_MODULES = Set.of("pos-api-gateway");

    /**
     * Modules that provide the shared catch-all transitively when depended upon.
     * pos-security-common deliberately re-exports pos-web-common (see its pom).
     */
    private static final Set<String> CATCH_ALL_PROVIDERS = Set.of("pos-web-common", "pos-security-common");

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void everyModuleWithRestControllersHasACatchAllExceptionHandler() throws IOException {
        List<Path> controllerModules;
        try (Stream<Path> modules = Files.list(REPO_ROOT)) {
            controllerModules = modules.filter(
                            module -> module.getFileName().toString().startsWith("pos-"))
                    .filter(module -> Files.isDirectory(module.resolve("src/main/java")))
                    .filter(module ->
                            !EXEMPT_MODULES.contains(module.getFileName().toString()))
                    .filter(GlobalExceptionHandlerEnforcementTest::hasRestControllers)
                    .toList();
        }

        // Guard against a vacuous pass from scanning the wrong directory: the reactor has
        // dozens of controller-bearing modules, so finding none means this test looked in
        // the wrong place, not that the rule holds.
        Assertions.assertFalse(
                controllerModules.isEmpty(),
                () -> "No modules with @RestController endpoints found under " + REPO_ROOT
                        + " — this test must run with the repository root as its parent directory"
                        + " (Maven surefire runs it from pos-archunit/).");

        List<String> violations = controllerModules.stream()
                .filter(module -> !hasOwnCatchAll(module) && !dependsOnCatchAllProvider(module))
                .map(module -> module.getFileName().toString())
                .sorted()
                .toList();

        Assertions.assertTrue(
                violations.isEmpty(),
                () -> "Modules with @RestController endpoints but no catch-all exception handler: " + violations
                        + ". Unmapped exceptions there escape as Spring's bare default 500 page without the"
                        + " ApiError envelope or a correlation id (issue #1471, ADR-0017 §3/§4). Fix: add a"
                        + " dependency on pos-web-common (or pos-security-common, which re-exports it) so the"
                        + " auto-configured GlobalApiExceptionHandler applies, or declare a module-local"
                        + " @ExceptionHandler(Exception.class) advice that returns the ApiError envelope.");
    }

    private static boolean hasRestControllers(Path module) {
        return anyMainSourceContains(module, "@RestController");
    }

    private static boolean hasOwnCatchAll(Path module) {
        return anyMainSourceContains(module, "@ExceptionHandler(Exception.class)");
    }

    private static boolean dependsOnCatchAllProvider(Path module) {
        Path pom = module.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return false;
        }
        try {
            String content = Files.readString(pom);
            return CATCH_ALL_PROVIDERS.stream()
                    .anyMatch(provider -> content.contains("<artifactId>" + provider + "</artifactId>"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + pom, e);
        }
    }

    private static boolean anyMainSourceContains(Path module, String needle) {
        Path sourceRoot = module.resolve("src/main/java");
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            return sources.filter(path -> path.toString().endsWith(".java")).anyMatch(path -> {
                try {
                    return Files.readString(path).contains(needle);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read " + path, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot walk " + sourceRoot, e);
        }
    }
}
