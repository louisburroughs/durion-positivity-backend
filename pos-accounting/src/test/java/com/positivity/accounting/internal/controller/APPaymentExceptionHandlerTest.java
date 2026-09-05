package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.exception.ExportJobNotFoundException;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.InvalidBillAllocationException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.exception.UnsupportedSortPropertyException;
import com.positivity.accounting.internal.exception.VendorBillMatchNotFoundException;
import com.positivity.accounting.internal.exception.VendorBillOperatorActionException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link APPaymentExceptionHandler}.
 *
 * <p>{@code APPaymentExceptionHandler} was already fully compliant with ADR-0017 §4 before this
 * test class was added: every handler routes through the private {@code build} helper, which
 * sets the correlation id in both the {@link ApiError} body and the {@code X-Correlation-Id}
 * response header. No production code changed for issue #1729; this class only adds the
 * header-contract guard so the class stays compliant.
 */
class APPaymentExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String CORRELATION_ID = "test-correlation-id-0001";

    private static HttpServletRequest requestWithHeader(String value) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_ID_HEADER)).thenReturn(value);
        return request;
    }

    private static HttpServletRequest requestWithoutHeader() {
        return requestWithHeader(null);
    }

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link APPaymentExceptionHandler}.
         * Uses a standalone handler instance so this factory method can stay static, as required
         * by {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            APPaymentExceptionHandler handler = new APPaymentExceptionHandler(TEST_CLOCK);

            return Stream.of(
                    Named.of("handleEventNotFound", (HandlerInvocation)
                            request -> handler.handleEventNotFound(new EventNotFoundException("not found"), request)),
                    Named.of("handleIdempotencyConflict", (HandlerInvocation)
                            request -> handler.handleIdempotencyConflict(
                                    new IdempotencyConflictException("paymentRef exists"), request)),
                    Named.of("handleExportJobNotFound", (HandlerInvocation) request ->
                            handler.handleExportJobNotFound(new ExportJobNotFoundException("not found"), request)),
                    Named.of("handleUnsupportedSortProperty", (HandlerInvocation)
                            request -> handler.handleUnsupportedSortProperty(
                                    new UnsupportedSortPropertyException("bad sort"), request)),
                    Named.of("handlePaymentGatewayException", (HandlerInvocation)
                            request -> handler.handlePaymentGatewayException(
                                    new PaymentGatewayException("gateway failure"), request)),
                    Named.of("handleInvalidBillAllocation", (HandlerInvocation)
                            request -> handler.handleInvalidBillAllocation(
                                    new InvalidBillAllocationException("allocation exceeds gross amount"), request)),
                    Named.of("handleVendorBillOperatorAction", (HandlerInvocation)
                            request -> handler.handleVendorBillOperatorAction(
                                    new VendorBillOperatorActionException("already resolved"), request)),
                    Named.of("handleVendorBillMatchNotFound", (HandlerInvocation)
                            request -> handler.handleVendorBillMatchNotFound(
                                    new VendorBillMatchNotFoundException("no matching bill"), request)),
                    Named.of("handleConstraintViolation", (HandlerInvocation) request -> {
                        ConstraintViolationException ex = mock(ConstraintViolationException.class);
                        when(ex.getConstraintViolations()).thenReturn(Set.<ConstraintViolation<?>>of());
                        return handler.handleConstraintViolation(ex, request);
                    }));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader(CORRELATION_ID));

            assertThat(response.getHeaders().getFirst(CORRELATION_ID_HEADER)).isEqualTo(CORRELATION_ID);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst(CORRELATION_ID_HEADER))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithoutHeader());

            String header = response.getHeaders().getFirst(CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader("   "));

            String header = response.getHeaders().getFirst(CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on APPaymentExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(APPaymentExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to APPaymentExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in APPaymentExceptionHandlerTest "
                            + "— add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
