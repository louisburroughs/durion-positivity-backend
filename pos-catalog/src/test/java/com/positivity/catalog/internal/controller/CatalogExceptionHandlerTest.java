package com.positivity.catalog.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogForbiddenOperationException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Unit tests for {@link CatalogExceptionHandler} — proves the single {@code buildResponse}
 * helper puts the correlation id in both the {@link ApiError} body and the {@code
 * X-Correlation-Id} response header for every {@code @ExceptionHandler} method (ADR-0017 §4,
 * issue #1729).
 */
class CatalogExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String CORRELATION_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc05";

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/v1/catalog/items");
    }

    private static MockHttpServletRequest requestWithCorrelationId(String value) {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", value);
        return request;
    }

    /** Any real method parameter works: the handler only reads the binding result. */
    private static MethodParameter methodParameter() throws NoSuchMethodException {
        return new MethodParameter(CatalogExceptionHandlerTest.class.getDeclaredMethod("sample", String.class), 0);
    }

    @SuppressWarnings("unused")
    private static void sample(String value) {
        // Signature-only holder for MethodParameter.
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the single `buildResponse`
    // helper puts the correlation id in both the body and the header for EVERY
    // @ExceptionHandler method, and guards against a future handler forgetting it.
    //
    // CatalogExceptionHandler was already fully compliant when this sweep landed: every
    // handler routes through `buildResponse`, which sets the header. No refactor was
    // needed here — this nested class only adds the regression guard.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(MockHttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link CatalogExceptionHandler}. Uses
         * a standalone handler instance so this factory method can stay static, as required by
         * {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() throws NoSuchMethodException {
            CatalogExceptionHandler sut = new CatalogExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

            BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
            bindingResult.addError(new org.springframework.validation.FieldError(
                    "request", "sku", null, false, null, null, "sku is required"));
            MethodArgumentNotValidException methodArgumentNotValidException =
                    new MethodArgumentNotValidException(methodParameter(), bindingResult);

            return Stream.of(
                    Named.of("handleNotFound", (HandlerInvocation)
                            request -> sut.handleNotFound(new CatalogNotFoundException("Item not found"), request)),
                    Named.of("handleForbidden", (HandlerInvocation) request -> sut.handleForbidden(
                            new CatalogForbiddenOperationException("Operation not allowed"), request)),
                    Named.of("handleMethodArgumentNotValid", (HandlerInvocation)
                            request -> sut.handleMethodArgumentNotValid(methodArgumentNotValidException, request)),
                    Named.of("handleMissingParameter", (HandlerInvocation) request -> sut.handleMissingParameter(
                            new MissingServletRequestParameterException("sku", "String"), request)),
                    Named.of("handleTypeMismatch", (HandlerInvocation) request -> {
                        MethodArgumentTypeMismatchException typeMismatch = new MethodArgumentTypeMismatchException(
                                "not-a-uuid", java.util.UUID.class, "itemId", null, null);
                        return sut.handleTypeMismatch(typeMismatch, request);
                    }),
                    Named.of("handleConstraintViolation", (HandlerInvocation) request ->
                            sut.handleConstraintViolation(new ConstraintViolationException(Set.of()), request)),
                    Named.of("handleBadRequest", (HandlerInvocation)
                            request -> sut.handleBadRequest(new CatalogValidationException("bad value"), request)),
                    Named.of("handleBusinessConflict", (HandlerInvocation) request ->
                            sut.handleBusinessConflict(new CatalogBusinessRuleException("rule violated"), request)),
                    Named.of("handleConflict", (HandlerInvocation) request -> sut.handleConflict(
                            new ObjectOptimisticLockingFailureException(Object.class, "id-123"), request)),
                    Named.of("handleServletException", (HandlerInvocation)
                            request -> sut.handleServletException(new ServletException("boom"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithCorrelationId(CORRELATION_ID));

            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(request());

            String header = response.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithCorrelationId("   "));

            String header = response.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on CatalogExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() throws NoSuchMethodException {
            long handlerMethodCount = Arrays.stream(CatalogExceptionHandler.class.getDeclaredMethods())
                    .filter((Method method) -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to CatalogExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in CatalogExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
