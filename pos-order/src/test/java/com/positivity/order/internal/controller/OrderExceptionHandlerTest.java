package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.exception.InvalidCustomerException;
import com.positivity.order.internal.exception.InvalidPriceOverrideException;
import com.positivity.order.internal.exception.InvalidSkuException;
import com.positivity.order.internal.exception.InvoicingUnavailableException;
import com.positivity.order.internal.exception.OrderVoidBlockedException;
import com.positivity.order.internal.exception.OverCapReturnException;
import com.positivity.order.internal.exception.PriceOverrideIdempotencyConflictException;
import com.positivity.order.internal.exception.PriceOverrideNotFoundException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.ReturnOrderNotFoundException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.SessionCloseBlockedException;
import com.positivity.order.internal.exception.TaxUnavailableException;
import com.positivity.order.internal.exception.WarrantyReturnRoutingException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * ApiError mapping for the four controller-scoped advices in pos-order.
 *
 * <p>
 * Each advice is deliberately scoped with {@code assignableTypes} so it does not
 * shadow its siblings, and that scoping is the reason the same exception type
 * maps to different codes and statuses per endpoint family — an
 * {@link IllegalStateException} is a 422 on the sales-order endpoints and a 409
 * on the returns endpoints. Clients branch on those codes, so this test pins the
 * pairings rather than trusting them to stay aligned by convention.
 *
 * <p>
 * The other property worth pinning is that the correlation id appears on both
 * the body and the {@code X-Correlation-Id} response header, and that an inbound
 * id is preserved so a trace started upstream survives the error. A blank
 * inbound header counts as absent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("pos-order exception advices — ApiError mapping")
class OrderExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private HttpServletRequest request;

    private SalesOrderExceptionHandler salesOrder;
    private ReturnOrderExceptionHandler returns;
    private RegisterSessionExceptionHandler sessions;
    private PriceOverrideExceptionHandler overrides;

    @BeforeEach
    void setUp() {
        salesOrder = new SalesOrderExceptionHandler(clock);
        returns = new ReturnOrderExceptionHandler(clock);
        sessions = new RegisterSessionExceptionHandler(clock);
        overrides = new PriceOverrideExceptionHandler(clock);
        when(request.getHeader(CORRELATION_HEADER)).thenReturn("trace-1");
    }

    private static void assertEnvelope(ResponseEntity<ApiError> result, HttpStatus status, String code) {
        assertThat(result.getStatusCode()).isEqualTo(status);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo(code);
        assertThat(result.getBody().status()).isEqualTo(status.value());
        assertThat(result.getBody().timestamp()).isEqualTo(NOW.toString());
        assertThat(result.getBody().correlationId()).isEqualTo("trace-1");
        // The header must carry the same id, since that is what a caller reports back.
        assertThat(result.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo("trace-1");
    }

    @Nested
    @DisplayName("SalesOrderExceptionHandler")
    class SalesOrders {

        @Test
        @DisplayName("maps a missing order to 404 ORDER_NOT_FOUND")
        void notFound() {
            assertEnvelope(
                    salesOrder.handleSalesOrderNotFound(new SalesOrderNotFoundException(ID), request),
                    HttpStatus.NOT_FOUND,
                    "ORDER_NOT_FOUND");
        }

        @Test
        @DisplayName("maps an unreachable tax service to 503, not a client error")
        void taxUnavailable() {
            // 503 tells the caller to retry; a 4xx would suggest the request itself was wrong.
            assertEnvelope(
                    salesOrder.handleTaxUnavailable(new TaxUnavailableException("tax down"), request),
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORDER_TAX_UNAVAILABLE");
        }

        @Test
        @DisplayName("maps an unreachable invoicing service to 503")
        void invoicingUnavailable() {
            assertEnvelope(
                    salesOrder.handleInvoicingUnavailable(new InvoicingUnavailableException("invoicing down"), request),
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORDER_INVOICING_UNAVAILABLE");
        }

        @Test
        @DisplayName("maps a blocked void to 409 ORDER_VOID_BLOCKED")
        void voidBlocked() {
            assertEnvelope(
                    salesOrder.handleVoidBlocked(new OrderVoidBlockedException(ID), request),
                    HttpStatus.CONFLICT,
                    "ORDER_VOID_BLOCKED");
        }

        @Test
        @DisplayName("maps an invalid customer to 422 and an invalid SKU to 400")
        void customerAndSku() {
            assertEnvelope(
                    salesOrder.handleInvalidCustomer(new InvalidCustomerException("unknown party"), request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ORDER_INVALID_CUSTOMER");
            // A bad SKU is a malformed request; an unknown customer is a well-formed request the
            // domain refuses. The two statuses reflect that difference.
            assertEnvelope(
                    salesOrder.handleInvalidSku(new InvalidSkuException("BAD", "no such sku"), request),
                    HttpStatus.BAD_REQUEST,
                    "ORDER_INVALID_SKU");
        }

        @Test
        @DisplayName("maps a rejected state transition to 422 UNPROCESSABLE_REQUEST")
        void illegalState() {
            assertEnvelope(
                    salesOrder.handleUnprocessableRequest(new IllegalStateException("already closed"), request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "UNPROCESSABLE_REQUEST");
        }

        @Test
        @DisplayName("mints a correlation id when the caller sent none")
        void mintsCorrelationIdWhenAbsent() {
            when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);

            ResponseEntity<ApiError> result =
                    salesOrder.handleIllegalArgument(new IllegalArgumentException("bad qty"), request);

            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().correlationId()).isNotBlank();
            assertThat(result.getHeaders().getFirst(CORRELATION_HEADER))
                    .isEqualTo(result.getBody().correlationId());
        }

        @Test
        @DisplayName("treats a blank inbound correlation id as absent rather than echoing it")
        void blankCorrelationIdIsReplaced() {
            when(request.getHeader(CORRELATION_HEADER)).thenReturn("   ");

            ResponseEntity<ApiError> result =
                    salesOrder.handleAccessDenied(new AccessDeniedException("denied"), request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("ORDER_FORBIDDEN");
            assertThat(result.getBody().correlationId()).isNotBlank().isNotEqualTo("   ");
        }
    }

    @Nested
    @DisplayName("ReturnOrderExceptionHandler")
    class Returns {

        @Test
        @DisplayName("maps either missing document to 404 RETURN_NOT_FOUND")
        void notFound() {
            assertEnvelope(
                    returns.handleNotFound(new ReturnOrderNotFoundException(ID), request),
                    HttpStatus.NOT_FOUND,
                    "RETURN_NOT_FOUND");
            // The original order missing is equally a "return not found" to this endpoint family.
            assertEnvelope(
                    returns.handleNotFound(new SalesOrderNotFoundException(ID), request),
                    HttpStatus.NOT_FOUND,
                    "RETURN_NOT_FOUND");
        }

        @Test
        @DisplayName("reports each over-cap line with its returnable quantity (spec R5.2)")
        void overCapCarriesPerLineCaps() {
            UUID secondLine = UUID.randomUUID();

            ResponseEntity<ApiError> result = returns.handleOverCap(
                    new OverCapReturnException(List.of(
                            new OverCapReturnException.LineCap(ID, 5, 2),
                            new OverCapReturnException.LineCap(secondLine, 3, 0))),
                    request);

            assertEnvelope(result, HttpStatus.UNPROCESSABLE_CONTENT, "RETURN_OVER_CAP");
            // Without the per-line caps the client cannot tell the operator what to change.
            assertThat(result.getBody().fieldErrors())
                    .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                    .containsExactly(
                            tuple(ID.toString(), "requested 5 but returnableQty is 2"),
                            tuple(secondLine.toString(), "requested 3 but returnableQty is 0"));
        }

        @Test
        @DisplayName("maps a warranty-routed line to 422 RETURN_WARRANTY_ROUTING")
        void warrantyRouting() {
            assertEnvelope(
                    returns.handleWarrantyRouting(new WarrantyReturnRoutingException(ID), request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "RETURN_WARRANTY_ROUTING");
        }

        @Test
        @DisplayName("maps a rejected state transition to 409, unlike the sales-order advice's 422")
        void illegalStateIsAConflictHere() {
            assertEnvelope(
                    returns.handleConflict(new IllegalStateException("already returned"), request),
                    HttpStatus.CONFLICT,
                    "RETURN_INVALID_STATE");
        }

        @Test
        @DisplayName("maps an invalid argument to 422 RETURN_INVALID_ARGUMENT")
        void invalidArgument() {
            assertEnvelope(
                    returns.handleInvalidArgument(new IllegalArgumentException("qty must be positive"), request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "RETURN_INVALID_ARGUMENT");
        }
    }

    @Nested
    @DisplayName("RegisterSessionExceptionHandler")
    class Sessions {

        @Test
        @DisplayName("maps a missing session to 404 and an open-session conflict to 409")
        void notFoundAndConflict() {
            assertEnvelope(
                    sessions.handleNotFound(new RegisterSessionNotFoundException(ID), request),
                    HttpStatus.NOT_FOUND,
                    "REGISTER_SESSION_NOT_FOUND");
            assertEnvelope(
                    sessions.handleConflict(new RegisterSessionConflictException("terminal already open"), request),
                    HttpStatus.CONFLICT,
                    "REGISTER_SESSION_CONFLICT");
        }

        @Test
        @DisplayName("maps a blocked close to 409 SESSION_CLOSE_BLOCKED")
        void closeBlocked() {
            assertEnvelope(
                    sessions.handleCloseBlocked(new SessionCloseBlockedException(ID), request),
                    HttpStatus.CONFLICT,
                    "SESSION_CLOSE_BLOCKED");
        }

        @Test
        @DisplayName("maps an invalid argument to 422 and a permission failure to 403")
        void invalidArgumentAndAccessDenied() {
            assertEnvelope(
                    sessions.handleIllegalArgument(new IllegalArgumentException("negative float"), request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "REGISTER_SESSION_INVALID_ARGUMENT");
            assertEnvelope(
                    sessions.handleAccessDenied(new AccessDeniedException("denied"), request),
                    HttpStatus.FORBIDDEN,
                    "ORDER_FORBIDDEN");
        }
    }

    @Nested
    @DisplayName("PriceOverrideExceptionHandler")
    class Overrides {

        @Test
        @DisplayName("maps a missing override to 404 and an invalid one to 422")
        void notFoundAndInvalid() {
            assertEnvelope(
                    overrides.handleOverrideNotFound(new PriceOverrideNotFoundException(ID), request),
                    HttpStatus.NOT_FOUND,
                    "ORDER_PRICE_OVERRIDE_NOT_FOUND");
            assertEnvelope(
                    overrides.handleInvalidOverride(new InvalidPriceOverrideException("below floor"), request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ORDER_PRICE_OVERRIDE_INVALID");
        }

        @Test
        @DisplayName("maps a reused idempotency key with different content to 409")
        void idempotencyConflict() {
            assertEnvelope(
                    overrides.handleIdempotencyConflict(
                            new PriceOverrideIdempotencyConflictException("key reused"), request),
                    HttpStatus.CONFLICT,
                    "ORDER_PRICE_OVERRIDE_IDEMPOTENCY_CONFLICT");
        }

        @Test
        @DisplayName("maps an invalid argument to 400 here, unlike the 422 the other advices use")
        void illegalArgumentIsABadRequestHere() {
            assertEnvelope(
                    overrides.handleIllegalArgument(new IllegalArgumentException("bad price"), request),
                    HttpStatus.BAD_REQUEST,
                    "ORDER_PRICE_OVERRIDE_BAD_REQUEST");
        }

        @Test
        @DisplayName("reports every rejected field, defaulting a null message rather than emitting null")
        void bodyValidationListsFieldErrors() throws NoSuchMethodException {
            BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
            binding.addError(new FieldError("request", "overridePrice", "must be positive"));
            binding.addError(new FieldError("request", "reasonCode", null));
            MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                    new org.springframework.core.MethodParameter(
                            Overrides.class.getDeclaredMethod("bodyValidationListsFieldErrors"), -1),
                    binding);

            ResponseEntity<ApiError> result = overrides.handleValidation(ex, request);

            assertEnvelope(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            assertThat(result.getBody().fieldErrors())
                    .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                    .containsExactly(tuple("overridePrice", "must be positive"), tuple("reasonCode", "invalid"));
        }

        @Test
        @DisplayName("maps a permission failure to 403 ORDER_FORBIDDEN")
        void accessDenied() {
            assertEnvelope(
                    overrides.handleAccessDenied(new AccessDeniedException("denied"), request),
                    HttpStatus.FORBIDDEN,
                    "ORDER_FORBIDDEN");
        }
    }
}
