package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.exception.OrderCancellationReviewRequiredException;
import com.positivity.order.internal.exception.OrderCancellationStateConflictException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * ApiError mapping for {@link OrderCancellationExceptionHandler}.
 *
 * <p>MockMvc-level coverage for the not-found/500-fallthrough behavior already lives in
 * {@link OrderCancellationControllerErrorHandlingTest}; this class pins the handler's own
 * status/code mapping and the X-Correlation-Id header contract (ADR-0017 §4, #1729).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderCancellationExceptionHandler")
class OrderCancellationExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private HttpServletRequest request;

    private OrderCancellationExceptionHandler sut;

    @BeforeEach
    void setUp() {
        sut = new OrderCancellationExceptionHandler(clock);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn("trace-1");
    }

    private static void assertEnvelope(ResponseEntity<ApiError> result, HttpStatus status, String code) {
        assertThat(result.getStatusCode()).isEqualTo(status);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo(code);
        assertThat(result.getBody().status()).isEqualTo(status.value());
        assertThat(result.getBody().timestamp()).isEqualTo(NOW.toString());
        assertThat(result.getBody().correlationId()).isEqualTo("trace-1");
        assertThat(result.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("maps a missing order to 404 ORDER_NOT_FOUND")
    void notFound() {
        assertEnvelope(
                sut.handleSalesOrderNotFound(new SalesOrderNotFoundException(ORDER_ID), request),
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("maps an invalid cancellation state to 409 ORDER_CANCELLATION_INVALID")
    void invalidState() {
        assertEnvelope(
                sut.handleInvalidCancellationState(
                        new OrderCancellationStateConflictException("already cancelled"), request),
                HttpStatus.CONFLICT,
                "ORDER_CANCELLATION_INVALID");
    }

    @Test
    @DisplayName("maps a permission failure to 403 ORDER_FORBIDDEN")
    void accessDenied() {
        assertEnvelope(
                sut.handleAccessDenied(new AccessDeniedException("denied"), request),
                HttpStatus.FORBIDDEN,
                "ORDER_FORBIDDEN");
    }

    @Test
    @DisplayName("mints a correlation id when the caller sent none")
    void mintsCorrelationIdWhenAbsent() {
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);

        ResponseEntity<ApiError> result =
                sut.handleSalesOrderNotFound(new SalesOrderNotFoundException(ORDER_ID), request);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().correlationId()).isNotBlank();
        assertThat(result.getHeaders().getFirst(CORRELATION_HEADER))
                .isEqualTo(result.getBody().correlationId());
    }

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
            OrderCancellationExceptionHandler handler = new OrderCancellationExceptionHandler(fixedClock);

            return Stream.of(
                    Named.of("handleSalesOrderNotFound", (HandlerInvocation) request ->
                            handler.handleSalesOrderNotFound(new SalesOrderNotFoundException(ORDER_ID), request)),
                    Named.of("handleInvalidCancellationState", (HandlerInvocation)
                            request -> handler.handleInvalidCancellationState(
                                    new OrderCancellationStateConflictException("already cancelled"), request)),
                    Named.of("handleCancellationReviewRequired", (HandlerInvocation)
                            request -> handler.handleCancellationReviewRequired(
                                    new OrderCancellationReviewRequiredException("manual review required"), request)),
                    Named.of("handleAccessDenied", (HandlerInvocation)
                            request -> handler.handleAccessDenied(new AccessDeniedException("denied"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getHeader(CORRELATION_HEADER)).thenReturn("trace-2");

            ResponseEntity<ApiError> response = invocation.invoke(req);

            assertThat(response.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo("trace-2");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst(CORRELATION_HEADER))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getHeader(CORRELATION_HEADER)).thenReturn(null);

            ResponseEntity<ApiError> response = invocation.invoke(req);

            String header = response.getHeaders().getFirst(CORRELATION_HEADER);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getHeader(CORRELATION_HEADER)).thenReturn("   ");

            ResponseEntity<ApiError> response = invocation.invoke(req);

            String header = response.getHeaders().getFirst(CORRELATION_HEADER);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName(
                "every @ExceptionHandler method on OrderCancellationExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(OrderCancellationExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to OrderCancellationExceptionHandler without a "
                            + "matching entry in XCorrelationIdHeader#handlerInvocations() in "
                            + "OrderCancellationExceptionHandlerTest — add one so the X-Correlation-Id header "
                            + "contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
