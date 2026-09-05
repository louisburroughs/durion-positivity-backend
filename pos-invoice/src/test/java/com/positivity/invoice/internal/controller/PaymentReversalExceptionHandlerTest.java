package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.internal.exception.InsufficientRefundableAmountException;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.PaymentGatewayException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.exception.PaymentWindowExpiredException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link PaymentReversalExceptionHandler} (ADR-0017 §4, ADR-0056 §1, #1729).
 */
class PaymentReversalExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    /**
     * Proves every {@code @ExceptionHandler} on {@link PaymentReversalExceptionHandler} carries
     * the correlation id in both the {@code X-Correlation-Id} response header and the {@code
     * ApiError} body (ADR-0017 §4, ADR-0056 §1, #1729). This class was already fully compliant
     * when this guard was added — every handler builds its own {@code ResponseEntity} with the
     * same {@code X-Correlation-Id} header — so no production code changed; this only pins the
     * behavior against regression and guards a future handler forgetting the header.
     */
    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link PaymentReversalExceptionHandler}.
         * Uses a standalone handler instance so this factory method can stay static, as required
         * by {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            PaymentReversalExceptionHandler handler =
                    new PaymentReversalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

            return Stream.of(
                    Named.of("handleNotFound", (HandlerInvocation) request -> handler.handleNotFound(
                            new PaymentIntentNotFoundException("payment intent not found"), request)),
                    Named.of("handleInvoiceNotFound", (HandlerInvocation) request ->
                            handler.handleInvoiceNotFound(new InvoiceNotFoundException(UUID.randomUUID()), request)),
                    Named.of("handleInvalidPaymentState", (HandlerInvocation)
                            request -> handler.handleInvalidPaymentState(
                                    new InvalidPaymentStateException("payment already reversed"), request)),
                    Named.of("handlePaymentWindowExpired", (HandlerInvocation)
                            request -> handler.handlePaymentWindowExpired(
                                    new PaymentWindowExpiredException("refund window has closed"), request)),
                    Named.of("handleInsufficientRefundableAmount", (HandlerInvocation)
                            request -> handler.handleInsufficientRefundableAmount(
                                    new InsufficientRefundableAmountException("refundable amount exceeded"), request)),
                    Named.of("handlePaymentGatewayException", (HandlerInvocation)
                            request -> handler.handlePaymentGatewayException(
                                    new PaymentGatewayException("gateway timeout"), request)));
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

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CORRELATION_HEADER, "   ");

            ResponseEntity<ApiError> response = invocation.invoke(request);

            String header = response.getHeaders().getFirst(CORRELATION_HEADER);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName(
                "every @ExceptionHandler method on PaymentReversalExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(PaymentReversalExceptionHandler.class.getDeclaredMethods())
                    .filter((Method method) -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to PaymentReversalExceptionHandler without a "
                            + "matching entry in XCorrelationIdHeader#handlerInvocations() in "
                            + "PaymentReversalExceptionHandlerTest — add one so the X-Correlation-Id header "
                            + "contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
