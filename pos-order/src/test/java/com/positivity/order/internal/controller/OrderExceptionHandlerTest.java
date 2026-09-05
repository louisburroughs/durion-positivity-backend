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
import com.positivity.order.internal.exception.PriceOverrideRequestValidationException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.RegisterSessionNotFoundException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.exception.ReturnLineNotReturnableException;
import com.positivity.order.internal.exception.ReturnOrderNotFoundException;
import com.positivity.order.internal.exception.ReturnOrderStateConflictException;
import com.positivity.order.internal.exception.ReturnOrderUnprocessableException;
import com.positivity.order.internal.exception.ReturnRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderStateConflictException;
import com.positivity.order.internal.exception.SalesOrderUnprocessableException;
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
 * ApiError mapping for four of pos-order's six controller-scoped advices (OrderCancellationExceptionHandler
 * and PurchaseOrderExceptionHandler are exercised separately, in their own controller-slice tests).
 *
 * <p>
 * Each advice is deliberately scoped with {@code assignableTypes} so it does not
 * shadow its siblings. Until #1730 that scoping also let one exception type mean
 * different things per endpoint family: a bare {@link IllegalStateException} was a
 * 422 on the sales-order endpoints and a 409 on the returns endpoints, because the
 * type carried stateful collisions, domain-policy refusals, request-shape errors and
 * outright downstream failures all at once, and neither status was right for the mix.
 * It is no longer mapped by any advice in this module. Each meaning now has its own
 * type, and the status follows ADR-0017 §1/§2 rather than the endpoint family.
 * Clients branch on these codes, so this test pins the pairings.
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

    /**
     * The guarantee the rest of this class depends on (#1730): no advice in this module maps bare
     * {@link IllegalStateException} any more. While one did, the type meant four different things
     * at once — a stateful collision, a domain-policy refusal, a request-shape error, and an
     * outright downstream failure — so the four scoped advices had settled on two different wrong
     * statuses for it, which is the drift #1730 reported.
     *
     * <p>What still throws it is server-side only: a failed payment reversal, a failed workorder
     * cancellation, a purchase-order sequence overflow, an event-serialisation failure. Those
     * reach pos-web-common's platform advice as a correlated 500, which is what they always were.
     * Re-adding a handler for the bare type here would silently relabel them as client errors
     * again, so this test fails if one appears.
     */
    @Test
    @DisplayName("no advice in this module maps bare IllegalStateException (#1730)")
    void noAdviceMapsBareIllegalStateException() {
        List<Class<?>> advices = List.of(
                SalesOrderExceptionHandler.class,
                ReturnOrderExceptionHandler.class,
                OrderCancellationExceptionHandler.class,
                PurchaseOrderExceptionHandler.class,
                RegisterSessionExceptionHandler.class,
                PriceOverrideExceptionHandler.class,
                OrderStateExceptionHandler.class);

        List<String> offenders = advices.stream()
                .flatMap(advice -> java.util.Arrays.stream(advice.getDeclaredMethods())
                        .filter(method -> {
                            var annotation = method.getAnnotation(
                                    org.springframework.web.bind.annotation.ExceptionHandler.class);
                            return annotation != null
                                    && java.util.Arrays.asList(annotation.value())
                                            .contains(IllegalStateException.class);
                        })
                        .map(method -> advice.getSimpleName() + "#" + method.getName()))
                .toList();

        assertThat(offenders)
                .as("bare IllegalStateException must stay unmapped so server-side failures answer a correlated 500")
                .isEmpty();
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
        @DisplayName("maps a domain-policy refusal to 422 ORDER_UNPROCESSABLE")
        void unprocessableRequest() {
            // #1730: a structurally valid cart request a rule refuses on its merits — an empty
            // cart, an unresolvable price, a serial/lot count that does not match the quantity.
            // 422 is unchanged for this case; what changed is that it no longer also covers
            // request-shape errors (400) or stateful collisions (409).
            assertEnvelope(
                    salesOrder.handleUnprocessableRequest(
                            new SalesOrderUnprocessableException("Cannot quote an empty cart"), request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ORDER_UNPROCESSABLE");
        }

        @Test
        @DisplayName("maps a stateful collision to 409, the same status the module's other advices answer")
        void stateConflictIsAConflictHereToo() {
            // #1730: this is the drift the issue reported. A collision with another resource's
            // state answered 422 here and 409 in the return/cancellation/purchase advices; it is
            // 409 everywhere now, per ADR-0017 §2.
            assertEnvelope(
                    salesOrder.handleStateConflict(
                            new SalesOrderStateConflictException("Terminal T1 has a register session being closed"),
                            request),
                    HttpStatus.CONFLICT,
                    "ORDER_STATE_CONFLICT");
        }

        @Test
        @DisplayName("maps a malformed cart/checkout request to 400, not the former 422")
        void invalidRequestIsABadRequestNow() {
            // Issue #1694: request-shape validation (unsupported tenderType, missing locationId,
            // an unrecognised status/sourceType filter, ...) moved from 422 to 400 per ADR-0017 —
            // the old status was never deliberate, just whatever the blanket IllegalArgumentException
            // handler this replaced happened to answer.
            assertEnvelope(
                    salesOrder.handleInvalidRequest(
                            new SalesOrderRequestValidationException("Unsupported tenderType: CRYPTO"), request),
                    HttpStatus.BAD_REQUEST,
                    "ORDER_INVALID_ARGUMENT");
        }

        @Test
        @DisplayName("mints a correlation id when the caller sent none")
        void mintsCorrelationIdWhenAbsent() {
            when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);

            ResponseEntity<ApiError> result =
                    salesOrder.handleInvalidRequest(new SalesOrderRequestValidationException("bad qty"), request);

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
        @DisplayName("maps a rejected state transition to 409, the same status every advice now answers")
        void stateConflictIsAConflictHere() {
            assertEnvelope(
                    returns.handleConflict(
                            new ReturnOrderStateConflictException("Only a PENDING_APPROVAL return can be approved"),
                            request),
                    HttpStatus.CONFLICT,
                    "RETURN_INVALID_STATE");
        }

        @Test
        @DisplayName("maps a domain-policy refusal to 422, where it used to share the 409")
        void unprocessableIsNotAConflict() {
            // #1730: "no invoice to refund against" is not a collision with current state — the
            // request is refused on its own terms, so retrying it unchanged can never work.
            // ADR-0017 §2 makes that a 422; it answered 409 while it travelled as bare
            // IllegalStateException.
            assertEnvelope(
                    returns.handleUnprocessable(
                            new ReturnOrderUnprocessableException("No invoice on the original order to refund against"),
                            request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "RETURN_UNPROCESSABLE");
        }

        @Test
        @DisplayName("maps a malformed return request to 400, not the former 422")
        void invalidRequestIsABadRequestNow() {
            // Issue #1694: request-shape validation (no lines, a duplicate/unknown line, a
            // non-positive returnQty, an unrecognised refundMethod/condition) moved from 422 to
            // 400 per ADR-0017; the code (RETURN_INVALID_ARGUMENT) is unchanged.
            assertEnvelope(
                    returns.handleInvalidRequest(new ReturnRequestValidationException("qty must be positive"), request),
                    HttpStatus.BAD_REQUEST,
                    "RETURN_INVALID_ARGUMENT");
        }

        @Test
        @DisplayName("maps a not-returnable line to 422 RETURN_LINE_NOT_RETURNABLE, its own new code")
        void lineNotReturnable() {
            // Split out of the former blanket 422 catch-all: a well-formed request the domain
            // refuses on its merits is distinguishable now from a malformed one (400 above).
            assertEnvelope(
                    returns.handleLineNotReturnable(new ReturnLineNotReturnableException(ID), request),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "RETURN_LINE_NOT_RETURNABLE");
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
        @DisplayName("maps an invalid request to 400 (not the former 422) and a permission failure to 403")
        void invalidRequestAndAccessDenied() {
            // Issue #1694: a non-positive cash-movement amount or unknown movementType is
            // request-shape validation, moved from 422 to 400 per ADR-0017; the code
            // (REGISTER_SESSION_INVALID_ARGUMENT) is unchanged.
            assertEnvelope(
                    sessions.handleInvalidRequest(
                            new RegisterSessionRequestValidationException("negative float"), request),
                    HttpStatus.BAD_REQUEST,
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
        @DisplayName("maps an invalid request to 400 here, same status the blanket handler already used")
        void invalidRequestIsABadRequestHere() {
            assertEnvelope(
                    overrides.handleInvalidRequest(
                            new PriceOverrideRequestValidationException("orderId must be a UUID: not-a-uuid"), request),
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
