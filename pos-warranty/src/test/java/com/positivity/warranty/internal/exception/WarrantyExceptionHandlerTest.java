package com.positivity.warranty.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.shared.error.ApiError;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Concurrent-write conflicts on the versioned claim aggregate must map to a retryable 409
 * {@code CONFLICT} ApiError, never the 500 {@code INTERNAL_ERROR} catch-all
 * (docs/ERROR_ENVELOPE.md; mirrors pos-catalog).
 */
class WarrantyExceptionHandlerTest {

    private final WarrantyExceptionHandler handler =
            new WarrantyExceptionHandler(Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void objectOptimisticLockingFailureMapsTo409Conflict() {
        ResponseEntity<ApiError> response = handler.handleOptimisticLockConflict(
                new ObjectOptimisticLockingFailureException(WarrantyClaim.class, "id"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getBody().status()).isEqualTo(409);
    }

    @Test
    void jpaOptimisticLockExceptionMapsTo409Conflict() {
        ResponseEntity<ApiError> response =
                handler.handleOptimisticLockConflict(new OptimisticLockException("stale"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — WarrantyExceptionHandler was already
    // fully compliant (every handler routes through `build(...)` or sets the header directly
    // alongside a guided/field-error body); this nested class exists to guard that it stays
    // that way, proving every @ExceptionHandler method carries the correlation id in both the
    // ApiError body and the X-Correlation-Id response header.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        private static final String CORRELATION_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01";

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        private static HttpServletRequest requestWithHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", CORRELATION_ID);
            return request;
        }

        private static HttpServletRequest requestWithoutHeader() {
            return new MockHttpServletRequest();
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link WarrantyExceptionHandler}.
         * Uses a standalone handler instance so this factory method can stay static, as
         * required by {@code @MethodSource} outside a {@code PER_CLASS} test instance
         * lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            WarrantyExceptionHandler handler =
                    new WarrantyExceptionHandler(Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));

            return Stream.of(
                    Named.of("handleNotFound", (HandlerInvocation) request -> handler.handleNotFound(
                            new WarrantyNotFoundException("WARRANTY_PROVIDER_NOT_FOUND", "not found"), request)),
                    Named.of("handleIllegalClaimState", (HandlerInvocation) request -> handler.handleIllegalClaimState(
                            new IllegalClaimStateException(
                                    "CLAIM_STATE_INVALID", "illegal transition", "SUBMIT, APPROVE"),
                            request)),
                    Named.of("handleUnprocessable", (HandlerInvocation) request -> handler.handleUnprocessable(
                            new WarrantyUnprocessableException("WORKORDER_NOT_FOUND", "unresolvable workorder"),
                            request)),
                    Named.of(
                            "handleIntegrationFailure", (HandlerInvocation) request -> handler.handleIntegrationFailure(
                                    new WarrantyIntegrationException("invoice write failed"), request)),
                    Named.of("handleBodyValidation", (HandlerInvocation) request -> {
                        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
                        BindingResult bindingResult = mock(BindingResult.class);
                        when(ex.getBindingResult()).thenReturn(bindingResult);
                        when(bindingResult.getFieldErrors()).thenReturn(List.of());
                        return handler.handleBodyValidation(ex, request);
                    }),
                    Named.of("handleConstraintViolation", (HandlerInvocation)
                            request -> handler.handleConstraintViolation(
                                    new ConstraintViolationException(Set.<ConstraintViolation<?>>of()), request)),
                    Named.of("handleMissingParameter", (HandlerInvocation) request -> handler.handleMissingParameter(
                            new MissingServletRequestParameterException("id", "String"), request)),
                    Named.of("handleTypeMismatch", (HandlerInvocation) request -> handler.handleTypeMismatch(
                            new MethodArgumentTypeMismatchException(
                                    "bad-value", UUID.class, "id", null, new IllegalArgumentException("bad uuid")),
                            request)),
                    Named.of("handleUnreadableBody", (HandlerInvocation) request ->
                            handler.handleUnreadableBody(mock(HttpMessageNotReadableException.class), request)),
                    Named.of("handleValidation", (HandlerInvocation)
                            request -> handler.handleValidation(new WarrantyValidationException("bad field"), request)),
                    Named.of("handleAccessDenied", (HandlerInvocation)
                            request -> handler.handleAccessDenied(new AccessDeniedException("denied"), request)),
                    Named.of("handleOptimisticLockConflict", (HandlerInvocation)
                            request -> handler.handleOptimisticLockConflict(
                                    new ObjectOptimisticLockingFailureException(WarrantyClaim.class, "id-123"),
                                    request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader());

            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithoutHeader());

            String header = response.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on WarrantyExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(WarrantyExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to WarrantyExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in WarrantyExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
