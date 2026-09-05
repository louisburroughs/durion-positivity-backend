package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.exception.CartIdempotencyConflictException;
import com.positivity.order.internal.exception.InvalidOrderStateTransitionException;
import com.positivity.order.internal.exception.OrderNotEditableException;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * ApiError mapping for {@link OrderStateExceptionHandler} — every handler answers 409 Conflict per
 * ADR-0017, and every response carries the correlation id in both the body and the
 * X-Correlation-Id header (ADR-0017 §4, #1729).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderStateExceptionHandler")
class OrderStateExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private HttpServletRequest request;

    private OrderStateExceptionHandler sut;

    @BeforeEach
    void setUp() {
        sut = new OrderStateExceptionHandler(clock);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn("trace-1");
    }

    private static void assertEnvelope(ResponseEntity<ApiError> result, String code) {
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo(code);
        assertThat(result.getBody().status()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(result.getBody().timestamp()).isEqualTo(NOW.toString());
        assertThat(result.getBody().correlationId()).isEqualTo("trace-1");
        assertThat(result.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("maps a non-editable order to 409 ORDER_NOT_EDITABLE")
    void notEditable() {
        assertEnvelope(
                sut.handleNotEditable(new OrderNotEditableException(ORDER_ID, SalesOrderStatus.COMPLETED), request),
                "ORDER_NOT_EDITABLE");
    }

    @Test
    @DisplayName("maps a disallowed transition to 409 ORDER_INVALID_STATE_TRANSITION")
    void invalidTransition() {
        assertEnvelope(
                sut.handleInvalidTransition(
                        new InvalidOrderStateTransitionException(
                                ORDER_ID, SalesOrderStatus.DRAFT, SalesOrderStatus.COMPLETED),
                        request),
                "ORDER_INVALID_STATE_TRANSITION");
    }

    @Test
    @DisplayName("maps a replayed idempotency key with different content to 409 ORDER_IDEMPOTENCY_CONFLICT")
    void idempotencyConflict() {
        assertEnvelope(
                sut.handleIdempotencyConflict(new CartIdempotencyConflictException("payload mismatch"), request),
                "ORDER_IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("maps a concurrent modification to 409 ORDER_CONFLICT")
    void optimisticLock() {
        assertEnvelope(
                sut.handleOptimisticLock(new ObjectOptimisticLockingFailureException(Object.class, "id-123"), request),
                "ORDER_CONFLICT");
    }

    @Test
    @DisplayName("mints a correlation id when the caller sent none")
    void mintsCorrelationIdWhenAbsent() {
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);

        ResponseEntity<ApiError> result =
                sut.handleNotEditable(new OrderNotEditableException(ORDER_ID, SalesOrderStatus.COMPLETED), request);

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
            OrderStateExceptionHandler handler = new OrderStateExceptionHandler(fixedClock);

            return Stream.of(
                    Named.of("handleNotEditable", (HandlerInvocation) request -> handler.handleNotEditable(
                            new OrderNotEditableException(ORDER_ID, SalesOrderStatus.COMPLETED), request)),
                    Named.of("handleInvalidTransition", (HandlerInvocation) request -> handler.handleInvalidTransition(
                            new InvalidOrderStateTransitionException(
                                    ORDER_ID, SalesOrderStatus.DRAFT, SalesOrderStatus.COMPLETED),
                            request)),
                    Named.of("handleIdempotencyConflict", (HandlerInvocation)
                            request -> handler.handleIdempotencyConflict(
                                    new CartIdempotencyConflictException("payload mismatch"), request)),
                    Named.of("handleOptimisticLock", (HandlerInvocation) request -> handler.handleOptimisticLock(
                            new ObjectOptimisticLockingFailureException(Object.class, "id-123"), request)));
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
        @DisplayName("every @ExceptionHandler method on OrderStateExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(OrderStateExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to OrderStateExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in OrderStateExceptionHandlerTest "
                            + "— add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
