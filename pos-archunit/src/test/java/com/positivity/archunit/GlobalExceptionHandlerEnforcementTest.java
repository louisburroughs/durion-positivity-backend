package com.positivity.archunit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces issue #1471's acceptance criterion, tightened by #1717: every module with REST
 * controllers has a catch-all exception handler, <em>and</em> any module-local catch-all answers
 * with the canonical {@code ApiError} envelope.
 *
 * <p>Without a catch-all, any unmapped exception escapes as Spring's bare default 500 page with
 * no {@code code}, no {@code message} and no {@code correlationId}, violating ADR-0017 §3/§4. A
 * module satisfies the coverage half by either:
 *
 * <ul>
 *   <li>depending (directly, or transitively via pos-security-common) on pos-web-common,
 *       whose auto-configured {@code GlobalApiExceptionHandler} provides the platform
 *       catch-all at lowest precedence, or</li>
 *   <li>declaring its own {@code @ExceptionHandler(Exception.class)} advice.</li>
 * </ul>
 *
 * <p><strong>Why the shape half exists (#1717).</strong> Until #1717 this rule accepted
 * "declares its own catch-all" regardless of what that advice returned, so {@code pos-people}
 * and {@code pos-people-contact} passed the build while answering 500s as bare
 * {@code ProblemDetail} with no {@code code} and no {@code correlationId} — exactly what
 * ADR-0017 §3/§4 forbid and what this rule exists to prevent. The shape check applies to
 * <em>every</em> module-local catch-all, including in modules that also depend on a provider:
 * Spring's {@code ExceptionHandlerExceptionResolver} picks the first applicable advice that has
 * any matching method, so a module-local catch-all shadows the platform one whether or not
 * pos-web-common is on the classpath.
 *
 * <p>{@link CatchAllAdviceScanner} holds the analysis, and
 * {@link CatchAllAdviceScannerTest} proves against fixtures that it rejects the shapes it is
 * meant to reject. This test asserts the live tree.
 *
 * <p>It scans module source trees and poms on disk rather than the ArchUnit classpath import:
 * pos-archunit's classpath only covers the modules declared in its pom, and this rule must hold
 * for every module in the reactor.
 */
@DisplayName("ADR-0056 §1/§4: every controller module has a catch-all, and it speaks ApiError")
class GlobalExceptionHandlerEnforcementTest {

    /**
     * Modules whose controllers are exempt from the servlet-MVC catch-all requirement.
     * pos-api-gateway is a WebFlux application: the servlet {@code @ControllerAdvice} in
     * pos-web-common does not apply, and gateway error rendering is its own concern
     * (ADR-0011 security boundary).
     */
    private static final Set<String> EXEMPT_MODULES = Set.of("pos-api-gateway");

    /**
     * Modules whose catch-all is deliberately not the platform envelope.
     *
     * <p>pos-reference-mock simulates an EXTERNAL vendor outside the platform mesh, so it
     * speaks a vendor-shaped error body on purpose — which is also a truer test double, since
     * adapters must survive vendor error shapes rather than platform ones. What the rule
     * protects against still holds there: no unmapped exception escapes as Spring's bare
     * default 500 page, and every error carries a reference id that appears in the mock's log.
     * See {@code VendorErrorAdvice}'s own javadoc.
     */
    private static final Set<String> EXEMPT_FROM_ENVELOPE_SHAPE = Set.of("pos-reference-mock");

    /**
     * Modules that provide the shared catch-all when depended upon. pos-security-common
     * deliberately re-exports pos-web-common (see its pom) — including for pos-security-service,
     * which does depend on it ({@code pos-security-service/pom.xml}).
     */
    private static final Set<String> CATCH_ALL_PROVIDERS = Set.of("pos-web-common", "pos-security-common");

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    @DisplayName("every module with @RestController endpoints is covered by a catch-all")
    void everyModuleWithRestControllersHasACatchAllExceptionHandler() throws IOException {
        List<Path> controllerModules = controllerModules();

        List<String> violations = controllerModules.stream()
                .filter(module -> CatchAllAdviceScanner.catchAllsIn(module).isEmpty()
                        && !CatchAllAdviceScanner.dependsOnCatchAllProvider(module, CATCH_ALL_PROVIDERS))
                .map(module -> module.getFileName().toString())
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

    @Test
    @DisplayName("a module-local catch-all answers with the ApiError envelope")
    void everyModuleLocalCatchAllReturnsTheApiErrorEnvelope() throws IOException {
        List<CatchAllAdviceScanner.CatchAll> violations = controllerModules().stream()
                .filter(module -> !EXEMPT_FROM_ENVELOPE_SHAPE.contains(
                        module.getFileName().toString()))
                .flatMap(module -> CatchAllAdviceScanner.catchAllsIn(module).stream())
                .filter(catchAll -> !catchAll.returnsApiError())
                .toList();

        Assertions.assertTrue(
                violations.isEmpty(),
                () -> "Module-local @ExceptionHandler(Exception.class) advices that do not answer with ApiError: "
                        + violations
                        + ". ADR-0017 §3 makes ApiError the envelope for every non-2xx body and §4 requires the"
                        + " correlation id in the body and the X-Correlation-Id header; a ProblemDetail or raw Map"
                        + " catch-all carries neither a code nor a correlation id, so a failure cannot be tied to"
                        + " its log entry (issue #1717). Fix: delete the module-local catch-all so pos-web-common's"
                        + " GlobalApiExceptionHandler takes the unmapped tail — which also restores ADR-0056 §2's"
                        + " DataIntegrityViolationException mapping, dead in any module that shadows it — or, if it"
                        + " is deliberately retained, return ResponseEntity<ApiError> with code INTERNAL_ERROR and"
                        + " the X-Correlation-Id header.");
    }

    /**
     * ADR-0056 §1: a module covered by the shared catch-all must not declare its own.
     *
     * <p>Shape conformance is not enough, which is why this rule is separate from
     * {@link #everyModuleLocalCatchAllReturnsTheApiErrorEnvelope()}. Spring's
     * {@code ExceptionHandlerExceptionResolver} picks the first applicable advice bean that has
     * ANY matching method — not the most specific handler across advices — so a module-local
     * {@code @ExceptionHandler(Exception.class)} swallows every unmapped exception before
     * pos-web-common's {@code GlobalApiExceptionHandler} runs. That takes ADR-0056 §2's
     * {@code DataIntegrityViolationException} mapping with it: a unique-constraint or FK
     * collision answers 500 INTERNAL_ERROR instead of 409 DUPLICATE_RESOURCE.
     *
     * <p>An ApiError-shaped catch-all passes the envelope rule while still doing this, which is
     * how pos-inventory kept the defect through the #1694 sweep and how the #1717 enforcement
     * came to vouch for it (issue #1768). The six modules #1694 remediated all deleted theirs
     * outright; this rule is what makes that the enforced answer rather than the remembered one.
     */
    @Test
    @DisplayName("a module covered by the shared catch-all does not declare its own")
    void noModuleShadowsTheSharedCatchAll() throws IOException {
        List<String> violations = controllerModules().stream()
                .filter(module -> CatchAllAdviceScanner.dependsOnCatchAllProvider(module, CATCH_ALL_PROVIDERS))
                .filter(module -> !CatchAllAdviceScanner.catchAllsIn(module).isEmpty())
                .map(module -> module.getFileName().toString())
                .toList();

        Assertions.assertTrue(
                violations.isEmpty(),
                () -> "Modules that depend on a catch-all provider AND declare their own catch-all: " + violations
                        + ". Spring resolves advices by bean order, not by handler specificity, so the module-local"
                        + " @ExceptionHandler(Exception.class) wins and pos-web-common's GlobalApiExceptionHandler"
                        + " never runs for that module — disabling ADR-0056 §2's DataIntegrityViolationException"
                        + " mapping, so a duplicate key answers 500 INTERNAL_ERROR rather than 409"
                        + " DUPLICATE_RESOURCE (issue #1768). Being ApiError-shaped does not help: the shape is"
                        + " right and the classification is still gone. Fix: delete the module-local catch-all and"
                        + " let the shared advice take the unmapped tail, as the six modules remediated in #1694"
                        + " did.");
    }

    private static List<Path> controllerModules() throws IOException {
        List<Path> controllerModules = CatchAllAdviceScanner.controllerModules(REPO_ROOT, EXEMPT_MODULES);

        // Guard against a vacuous pass from scanning the wrong directory: the reactor has
        // dozens of controller-bearing modules, so finding none means this test looked in
        // the wrong place, not that the rule holds.
        Assertions.assertFalse(
                controllerModules.isEmpty(),
                () -> "No modules with @RestController endpoints found under " + REPO_ROOT
                        + " — this test must run with the repository root as its parent directory"
                        + " (Maven surefire runs it from pos-archunit/).");
        return controllerModules;
    }
}
