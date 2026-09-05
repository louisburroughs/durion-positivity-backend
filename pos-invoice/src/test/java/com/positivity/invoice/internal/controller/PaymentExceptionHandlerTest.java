package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.PaymentDeclinedException;
import com.positivity.invoice.internal.exception.PaymentIdempotencyConflictException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link PaymentExceptionHandler} (ADR-0017 §4, ADR-0056 §1, #1729).
 */
class PaymentExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    /**
     * Proves every {@code @ExceptionHandler} on {@link PaymentExceptionHandler} carries the
     * correlation id in both the {@code X-Correlation-Id} response header and the {@code ApiError}
     * body (ADR-0017 §4, ADR-0056 §1, #1729). This class was already fully compliant when this
     * guard was added — every handler builds its own {@code ResponseEntity} with the same {@code
     * X-Correlation-Id} header — so no production code changed; this only pins the behavior
     * against regression and guards a future handler forgetting the header.
     */
    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link PaymentExceptionHandler}. Uses
         * a standalone handler instance so this factory method can stay static, as required by
         * {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            PaymentExceptionHandler handler = new PaymentExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

            return Stream.of(
                    Named.of("handleInvalidPaymentState", (HandlerInvocation)
                            request -> handler.handleInvalidPaymentState(
                                    new InvalidPaymentStateException("payment intent already captured"), request)),
                    Named.of("handleIdempotencyConflict", (HandlerInvocation)
                            request -> handler.handleIdempotencyConflict(
                                    new PaymentIdempotencyConflictException("idempotency key reused"), request)),
                    Named.of("handleNotFound", (HandlerInvocation) request -> handler.handleNotFound(
                            new PaymentIntentNotFoundException("payment intent not found"), request)),
                    Named.of("handlePaymentDeclined", (HandlerInvocation) request ->
                            handler.handlePaymentDeclined(new PaymentDeclinedException("card declined"), request)),
                    Named.of("handleForbidden", (HandlerInvocation)
                            request -> handler.handleForbidden(new AccessDeniedException("denied"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CORRELATION_HEADER, "inbound-trace-id");

            ResponseEntity<ApiError> response = invocation.invoke(request);

            assertThat(response.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo("inbound-trace-id");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst(CORRELATION_HEADER))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(new MockHttpServletRequest());

            String header = response.getHeaders().getFirst(CORRELATION_HEADER);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on PaymentExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(PaymentExceptionHandler.class.getDeclaredMethods())
                    .filter((Method method) -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to PaymentExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in PaymentExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
