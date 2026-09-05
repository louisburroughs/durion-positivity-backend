package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderNotTransmittableException;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.exception.UomConversionUndefinedException;
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
 * ApiError mapping for {@link PurchaseOrderExceptionHandler}.
 *
 * <p>MockMvc-level coverage for the not-found/validation paths already lives in
 * {@code PurchaseOrderControllerErrorHandlingTest} and {@code PurchaseOrderErrorContractTest};
 * this class pins the handler's own status/code mapping and the X-Correlation-Id header contract
 * (ADR-0017 §4, #1729).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseOrderExceptionHandler")
class PurchaseOrderExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final UUID PO_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private HttpServletRequest request;

    private PurchaseOrderExceptionHandler sut;

    @BeforeEach
    void setUp() {
        sut = new PurchaseOrderExceptionHandler(clock);
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
    @DisplayName("maps a missing purchase order to 404 PURCHASE_ORDER_NOT_FOUND")
    void notFound() {
        assertEnvelope(
                sut.handleNotFound(new PurchaseOrderNotFoundException(PO_ID), request),
                HttpStatus.NOT_FOUND,
                "PURCHASE_ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("maps a not-transmittable order to 422 with the exception's own error code")
    void notTransmittable() {
        assertEnvelope(
                sut.handleNotTransmittable(PurchaseOrderNotTransmittableException.noSupplierRef(), request),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SUPPLIER_REF_MISSING");
    }

    @Test
    @DisplayName("maps an undefined UoM conversion to 422 UOM_CONVERSION_UNDEFINED")
    void uomConversionUndefined() {
        assertEnvelope(
                sut.handleUomConversionUndefined(UomConversionUndefinedException.unknownProduct(PO_ID, "EA"), request),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "UOM_CONVERSION_UNDEFINED");
    }

    @Test
    @DisplayName("maps a lifecycle refusal to 409 PURCHASE_ORDER_INVALID_STATE")
    void invalidState() {
        assertEnvelope(
                sut.handleInvalidState(new IllegalStateException("already approved"), request),
                HttpStatus.CONFLICT,
                "PURCHASE_ORDER_INVALID_STATE");
    }

    @Test
    @DisplayName("maps a malformed request to 400 PURCHASE_ORDER_BAD_REQUEST")
    void invalidRequest() {
        assertEnvelope(
                sut.handleInvalidRequest(new PurchaseOrderRequestValidationException("quantity is required"), request),
                HttpStatus.BAD_REQUEST,
                "PURCHASE_ORDER_BAD_REQUEST");
    }

    @Test
    @DisplayName("maps a permission failure to 403 PURCHASE_ORDER_FORBIDDEN")
    void accessDenied() {
        assertEnvelope(
                sut.handleAccessDenied(new AccessDeniedException("denied"), request),
                HttpStatus.FORBIDDEN,
                "PURCHASE_ORDER_FORBIDDEN");
    }

    @Test
    @DisplayName("mints a correlation id when the caller sent none")
    void mintsCorrelationIdWhenAbsent() {
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);

        ResponseEntity<ApiError> result = sut.handleNotFound(new PurchaseOrderNotFoundException(PO_ID), request);

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
            PurchaseOrderExceptionHandler handler = new PurchaseOrderExceptionHandler(fixedClock);

            return Stream.of(
                    Named.of("handleNotFound", (HandlerInvocation)
                            request -> handler.handleNotFound(new PurchaseOrderNotFoundException(PO_ID), request)),
                    Named.of("handleNotTransmittable", (HandlerInvocation) request -> handler.handleNotTransmittable(
                            PurchaseOrderNotTransmittableException.noSupplierRef(), request)),
                    Named.of("handleUomConversionUndefined", (HandlerInvocation)
                            request -> handler.handleUomConversionUndefined(
                                    UomConversionUndefinedException.unknownProduct(PO_ID, "EA"), request)),
                    Named.of("handleInvalidState", (HandlerInvocation) request ->
                            handler.handleInvalidState(new IllegalStateException("already approved"), request)),
                    Named.of("handleInvalidRequest", (HandlerInvocation) request -> handler.handleInvalidRequest(
                            new PurchaseOrderRequestValidationException("quantity is required"), request)),
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

        @Test
        @DisplayName(
                "every @ExceptionHandler method on PurchaseOrderExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(PurchaseOrderExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to PurchaseOrderExceptionHandler without a "
                            + "matching entry in XCorrelationIdHeader#handlerInvocations() in "
                            + "PurchaseOrderExceptionHandlerTest — add one so the X-Correlation-Id header contract "
                            + "stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}
