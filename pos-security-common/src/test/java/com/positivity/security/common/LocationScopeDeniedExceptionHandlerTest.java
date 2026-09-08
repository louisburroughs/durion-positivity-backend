package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shared.error.ApiError;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@link LocationScopeDeniedException} must reach the client as {@code LOCATION_SCOPE_DENIED} in
 * the {@code ApiError} envelope with a correlation id — even in a module whose own advice maps
 * the parent {@code AccessDeniedException} to a plain {@code FORBIDDEN} (#1870).
 *
 * <p>Runs a real {@code AnnotationConfigWebApplicationContext} so the precedence goes through
 * Spring's actual advice discovery, the same way pos-web-common's {@code AdvicePrecedenceTest}
 * does. The module-shaped advice is registered <em>first</em>, so only the declared
 * {@code @Order} can be what puts the shared handler ahead of it.
 */
@DisplayName("LocationScopeDeniedExceptionHandler — the envelope for a scope denial")
class LocationScopeDeniedExceptionHandlerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-07T12:00:00Z");
    private static final String LOCATION_ID = "00000000-0000-7000-8000-000000000003";

    @Test
    @DisplayName("a scope denial answers 403 LOCATION_SCOPE_DENIED, not the module's FORBIDDEN")
    void scopeDenialCarriesItsOwnCode() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context()) {
            mockMvc(context)
                    .perform(get("/test/scope-denied"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.code").value("LOCATION_SCOPE_DENIED"))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.timestamp").value(FIXED_INSTANT.toString()))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    @Test
    @DisplayName("the response body never reflects the caller-supplied location id")
    void bodyDoesNotReflectLocationId() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context()) {
            MvcResult result =
                    mockMvc(context).perform(get("/test/scope-denied")).andReturn();

            assertThat(result.getResponse().getContentAsString()).doesNotContain(LOCATION_ID);
        }
    }

    @Test
    @DisplayName("an inbound X-Correlation-Id is echoed in the header and the body")
    void inboundCorrelationIdIsEchoed() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context()) {
            mockMvc(context)
                    .perform(get("/test/scope-denied").header("X-Correlation-Id", "  corr-123  "))
                    .andExpect(header().string("X-Correlation-Id", "corr-123"))
                    .andExpect(jsonPath("$.correlationId").value("corr-123"));
        }
    }

    @Test
    @DisplayName("a blank inbound X-Correlation-Id is replaced by a generated one")
    void blankCorrelationIdIsGenerated() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context()) {
            MvcResult result = mockMvc(context)
                    .perform(get("/test/scope-denied").header("X-Correlation-Id", "   "))
                    .andReturn();

            String generated = result.getResponse().getHeader("X-Correlation-Id");
            assertThat(generated).isNotBlank();
            assertThat(UUID.fromString(generated)).isNotNull();
        }
    }

    @Test
    @DisplayName("a plain AccessDeniedException is untouched and still reaches the module's advice")
    void plainAccessDeniedStillReachesModuleAdvice() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context()) {
            mockMvc(context)
                    .perform(get("/test/plain-denied"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    @DisplayName("the shared handler resolves ahead of the module advice by declared order, not by registration")
    void handlerIsOrderedFirst() {
        try (AnnotationConfigWebApplicationContext context = context()) {
            List<Class<?>> adviceTypes = ControllerAdviceBean.findAnnotatedBeans(context).stream()
                    .map(ControllerAdviceBean::getBeanType)
                    .collect(java.util.stream.Collectors.toList());

            assertThat(adviceTypes).containsSubsequence(LocationScopeDeniedExceptionHandler.class, ModuleAdvice.class);
            assertThat(LocationScopeDeniedExceptionHandler.class
                            .getAnnotation(Order.class)
                            .value())
                    .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    @Test
    @DisplayName("the handler works without a request (null-safe correlation id)")
    void handlesNullRequest() {
        var handler = new LocationScopeDeniedExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        ResponseEntity<ApiError> entity = handler.handleLocationScopeDenied(
                new LocationScopeDeniedException("workorder:wip:view", LOCATION_ID), null, response);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().code()).isEqualTo("LOCATION_SCOPE_DENIED");
        assertThat(entity.getBody().correlationId()).isNotBlank();
        assertThat(response.getHeader("X-Correlation-Id"))
                .isEqualTo(entity.getBody().correlationId());
    }

    private static MockMvc mockMvc(AnnotationConfigWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static AnnotationConfigWebApplicationContext context() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebMvcConfig.class);
        // Module advice first, shared handler second: registration order would put the module
        // advice ahead, so the shared handler wins only through its declared @Order.
        context.register(ModuleAdvice.class);
        context.register(SharedHandlerConfig.class);
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

    static class SharedHandlerConfig {

        @org.springframework.context.annotation.Bean
        LocationScopeDeniedExceptionHandler locationScopeDeniedExceptionHandler() {
            return new LocationScopeDeniedExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        }
    }

    /** Shaped like the reactor's module advices: no {@code @Order}, maps the parent type to FORBIDDEN. */
    @RestControllerAdvice
    static class ModuleAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiError.of(
                            "FORBIDDEN",
                            ex.getMessage(),
                            HttpStatus.FORBIDDEN.value(),
                            FIXED_INSTANT.toString(),
                            "00000000-0000-7000-8000-000000000001"));
        }
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/scope-denied")
        Map<String, String> scopeDenied() {
            throw new LocationScopeDeniedException("workorder:wip:view", LOCATION_ID);
        }

        @GetMapping("/test/plain-denied")
        Map<String, String> plainDenied() {
            throw new AccessDeniedException("plain denial");
        }
    }
}
