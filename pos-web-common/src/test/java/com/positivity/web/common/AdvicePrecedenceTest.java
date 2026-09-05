package com.positivity.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shared.error.ApiError;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Pins the two precedence guarantees ADR-0056 §1 states but nothing enforced (issue #1717).
 *
 * <p>ADR-0056 §1 says service-specific advices "keep precedence" over the shared one.
 * {@link GlobalApiExceptionHandler} is {@code @Order(Ordered.LOWEST_PRECEDENCE)} and no module
 * advice in the reactor declares {@code @Order}, so every advice sits at the same order value
 * and the tie is broken by registration order. It works today only because auto-configured
 * beans register after component-scanned ones — an incidental property no test held down.
 *
 * <p>These tests run a real {@code AnnotationConfigWebApplicationContext} with
 * {@code @EnableWebMvc}, so the ordering goes through Spring's actual
 * {@code ExceptionHandlerExceptionResolver} advice discovery rather than a hand-ordered
 * standalone setup.
 */
@DisplayName("Advice precedence: a module advice wins the tie, the platform advice takes the tail (#1717)")
class AdvicePrecedenceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-23T12:00:00Z");

    /**
     * (a) A module advice that maps a specific type must beat the platform catch-all for that
     * type. Beans are registered in production order — the module advice component-scanned
     * first, the platform advice auto-configured after.
     */
    @Test
    @DisplayName("a module advice's specific mapping beats the platform catch-all")
    void moduleAdviceWinsTheTieForATypeItMaps() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context(ModuleAdviceWithoutIntegrityMapping.class)) {
            mockMvc(context)
                    .perform(get("/test/domain-conflict"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MODULE_CONFLICT"));
        }
    }

    /**
     * (b) The half ADR-0056 §2 depends on: when the module advice does <em>not</em> map
     * {@code DataIntegrityViolationException}, it must still reach the platform's classification
     * (409 for a unique violation), not be swallowed on the way.
     */
    @Test
    @DisplayName("DataIntegrityViolationException still reaches the platform mapping")
    void integrityViolationReachesThePlatformMapping() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context(ModuleAdviceWithoutIntegrityMapping.class)) {
            mockMvc(context)
                    .perform(get("/test/duplicate"))
                    .andExpect(status().isConflict())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }

    /**
     * The failure mode the ArchUnit rule in pos-archunit exists to prevent, pinned here so the
     * two halves of #1717 stay tied together. Spring's {@code ExceptionHandlerExceptionResolver}
     * picks the <em>first applicable advice that has any matching method</em>, not the most
     * specific handler across advices — so a module-local {@code @ExceptionHandler(Exception.class)}
     * swallows {@code DataIntegrityViolationException} and ADR-0056 §2's mapping becomes dead
     * code in that module. This is what happened in six modules until #1694 removed them.
     */
    @Test
    @DisplayName("a module-local catch-all shadows ADR-0056 §2's integrity mapping — the rule's reason to exist")
    void aModuleLocalCatchAllShadowsThePlatformIntegrityMapping() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context(ModuleAdviceWithCatchAll.class)) {
            mockMvc(context)
                    .perform(get("/test/duplicate"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("swallowed by the module catch-all"));
        }
    }

    /**
     * The invariant underneath the three tests above, asserted directly on the resolved advice
     * chain: the module advice must come strictly before {@link GlobalApiExceptionHandler}.
     *
     * <p>Both resolve to {@link Ordered#LOWEST_PRECEDENCE} — the platform advice declares it,
     * module advices declare no {@code @Order} at all and default to it — so
     * {@code OrderComparator}'s stable sort leaves them tied and the order is decided by bean
     * registration. It holds because {@code @Bean} methods on a configuration class are
     * registered by {@code ConfigurationClassPostProcessor} <em>after</em> directly registered
     * and component-scanned classes, which is exactly how the platform advice arrives (an
     * auto-configuration {@code @Bean}) versus a module advice (a component-scanned
     * {@code @RestControllerAdvice}). Verified rather than assumed: reversing the two
     * {@code context.register} calls in {@link #context} does not change the resolved order.
     *
     * <p>So the guarantee is real but incidental — a property of Spring's registration phases,
     * not a declared precedence. Giving module advices an explicit {@code @Order} above
     * {@code LOWEST_PRECEDENCE} would make it declared; this test is what would keep it honest
     * either way.
     */
    @Test
    @DisplayName("the platform advice resolves strictly last in the advice chain")
    void thePlatformAdviceResolvesLast() {
        try (AnnotationConfigWebApplicationContext context = context(ModuleAdviceWithoutIntegrityMapping.class)) {
            List<Class<?>> adviceTypes = ControllerAdviceBean.findAnnotatedBeans(context).stream()
                    .map(ControllerAdviceBean::getBeanType)
                    .collect(Collectors.toList());

            assertThat(adviceTypes)
                    .describedAs("ADR-0056 §1: service advices keep precedence over the shared one")
                    .containsSubsequence(ModuleAdviceWithoutIntegrityMapping.class, GlobalApiExceptionHandler.class);
            assertThat(adviceTypes.getLast()).isEqualTo(GlobalApiExceptionHandler.class);
        }
    }

    /** Records that neither advice separates itself by order value — the gap #1717 describes. */
    @Test
    @DisplayName("the precedence is a tie in order value, not a declared ordering")
    void theOrderingIsATieNotADeclaredPrecedence() {
        Object moduleAdvice = new ModuleAdviceWithoutIntegrityMapping();
        Object platformAdvice = new GlobalApiExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        assertThat(AnnotationAwareOrderComparator.INSTANCE.compare(moduleAdvice, platformAdvice))
                .describedAs("module advices declare no @Order, so both resolve to LOWEST_PRECEDENCE")
                .isZero();
        assertThat(GlobalApiExceptionHandler.class.getAnnotation(Order.class))
                .describedAs("the platform advice declares the lowest precedence explicitly")
                .isNotNull()
                .extracting(Order::value)
                .isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(ModuleAdviceWithoutIntegrityMapping.class.getAnnotation(Order.class))
                .describedAs("module advices declare no order at all — this is the gap #1717 records")
                .isNull();
    }

    private static MockMvc mockMvc(AnnotationConfigWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static AnnotationConfigWebApplicationContext context(Class<?> moduleAdvice) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebMvcConfig.class);
        // Production shape: the module's advice is a component-scanned @RestControllerAdvice, the
        // platform's arrives as an auto-configuration @Bean — which is what puts it last.
        context.register(moduleAdvice);
        context.register(PlatformAdviceConfig.class);
        context.refresh();
        return context;
    }

    @EnableWebMvc
    static class WebMvcConfig {

        @org.springframework.context.annotation.Bean
        ThrowingController throwingController() {
            return new ThrowingController();
        }
    }

    static class PlatformAdviceConfig {

        @org.springframework.context.annotation.Bean
        GlobalApiExceptionHandler globalApiExceptionHandler() {
            return new GlobalApiExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        }
    }

    /** A module advice shaped like the reactor's: no {@code @Order}, no catch-all (post-#1694). */
    @RestControllerAdvice
    static class ModuleAdviceWithoutIntegrityMapping {

        @ExceptionHandler(ModuleConflictException.class)
        ResponseEntity<ApiError> handleModuleConflict(ModuleConflictException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiError.of(
                            "MODULE_CONFLICT",
                            ex.getMessage(),
                            HttpStatus.CONFLICT.value(),
                            FIXED_INSTANT.toString(),
                            "00000000-0000-7000-8000-000000000001"));
        }
    }

    /** The pre-#1694 shape: a module-local catch-all that shadows the platform advice entirely. */
    @RestControllerAdvice
    static class ModuleAdviceWithCatchAll {

        @ExceptionHandler(Exception.class)
        ProblemDetail handleAny(Exception ex) {
            return ProblemDetail.forStatusAndDetail(
                    HttpStatus.INTERNAL_SERVER_ERROR, "swallowed by the module catch-all");
        }
    }

    static class ModuleConflictException extends RuntimeException {

        ModuleConflictException() {
            super("module-specific conflict");
        }
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/domain-conflict")
        Map<String, String> domainConflict() {
            throw new ModuleConflictException();
        }

        @GetMapping("/test/duplicate")
        Map<String, String> duplicate() {
            throw new DataIntegrityViolationException(
                    "could not execute statement",
                    new SQLException(
                            "ERROR: duplicate key value violates unique constraint \"uq_party_customer_number\"",
                            "23505"));
        }
    }
}
