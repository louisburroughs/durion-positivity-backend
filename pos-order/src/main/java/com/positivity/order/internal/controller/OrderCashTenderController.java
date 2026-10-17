package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.CashTenderRequest;
import com.positivity.order.internal.dto.CashTenderResponse;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.order.internal.service.CashTenderService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Register cash tender with rounding to the currency's cash increment (ADR-0067 A8, PC-7 (b); #2662). */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Sales Orders", description = "Sales order cart management")
public class OrderCashTenderController {

    private final CashTenderService cashTenderService;

    @PostMapping("/{orderId}/payments/cash")
    @PreAuthorize("hasAuthority('" + OrderPermissions.ORDER_CHECKOUT + "')")
    @EmitEvent(id = "ORDER_CASH_TENDER_RECORD", apiVersion = "1")
    @Operation(operationId = "recordCashTender", summary = "Record Cash Tender on Order", description = """
                    Records a cash tender taken at the register against a checked-out order: pos-order computes \
                    the amount due, rounds the cash due HALF_UP to the currency's cash increment (CAD 0.05; USD and \
                    JPY do not round), checks the tender covers it and records the tender on the order's invoice \
                    through pos-invoice, which publishes the settled fact that completes the order.
                    Use this tool when the customer pays cash at the counter; do not use checkout, which freezes \
                    the cart and declares the tender without recording it, and do not round the amount yourself, \
                    because the register owns the increment and the rounding.
                    Preconditions: the order is PENDING_PAYMENT with an invoice; it belongs to an OPEN register \
                    session; currencyCode is the drawer's currency; the caller holds order:order:checkout for the \
                    order's location.
                    Required inputs: the Idempotency-Key header (a replay with the same payload returns the same \
                    tender, another payload is 409), cashTendered (what the customer handed over, at the currency's \
                    exponent) and currencyCode; cashApplied is optional and names a partial cash amount for a split \
                    tender, which rounds nothing and must be a whole multiple of the increment.
                    Only a cash tender that closes the balance rounds; the rounding is signed (positive when the \
                    customer paid more than due) and is posted by pos-accounting, never to the customer's balance.
                    Emits an ORDER_CASH_TENDER_RECORD event.
                    Returns 201 with amountDue, cashIncrement, cashDue, cashRoundingAmount, cashTendered, changeDue \
                    and the recorded payment intent; 400 when the header is missing or cashApplied exceeds the \
                    balance (ORDER_INVALID_ARGUMENT) or the body fails validation (VALIDATION_ERROR); 403 when the \
                    authority is missing or, above 500.00, the clerk lacks invoice:payment:limit_override; 404 when the order does not exist; 409 when the order is not \
                    PENDING_PAYMENT (ORDER_INVALID_STATE_TRANSITION), the session is not OPEN \
                    (REGISTER_SESSION_CONFLICT), the key was reused with another payload \
                    (ORDER_IDEMPOTENCY_CONFLICT) or pos-invoice's balance disagrees (ORDER_CONFLICT); 422 with \
                    CASH_TENDER_INSUFFICIENT, CASH_AMOUNT_NOT_IN_INCREMENT, CASH_TENDER_SESSION_REQUIRED, \
                    CURRENCY_NOT_SUPPORTED or AMOUNT_PRECISION_EXCEEDS_CURRENCY; 503 when pos-invoice is unreachable.
                    """)
    @ApiResponse(responseCode = "201", description = "Cash tender recorded on the invoice")
    @ApiResponse(
            responseCode = "400",
            description = "ORDER_INVALID_ARGUMENT: missing Idempotency-Key or cashApplied above the balance;"
                    + " VALIDATION_ERROR with fieldErrors when the body fails validation (negative or missing"
                    + " cashTendered, missing currencyCode)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "ORDER_FORBIDDEN when the caller lacks order:order:checkout",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "ORDER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "ORDER_INVALID_STATE_TRANSITION when the order is not PENDING_PAYMENT;"
                    + " REGISTER_SESSION_CONFLICT when the order's session is not OPEN; ORDER_IDEMPOTENCY_CONFLICT"
                    + " when the key was used with another payload; ORDER_CONFLICT when pos-invoice's balance"
                    + " disagrees with the register's remainder",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "CASH_TENDER_INSUFFICIENT when cashTendered is below the rounded cash due;"
                    + " CASH_AMOUNT_NOT_IN_INCREMENT when a partial cashApplied is not a multiple of the increment;"
                    + " CASH_TENDER_SESSION_REQUIRED when the order has no register session; CURRENCY_NOT_SUPPORTED"
                    + " when currencyCode is not the drawer's; AMOUNT_PRECISION_EXCEEDS_CURRENCY when an amount has"
                    + " more decimals than the currency",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "ORDER_INVOICING_UNAVAILABLE: pos-invoice unreachable; nothing recorded, retry the same key",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CashTenderResponse> recordCashTender(
            @PathVariable @NonNull UUID orderId,
            @Parameter(description = "Required idempotency key; a replay with the same payload returns the same tender")
                    @RequestHeader(name = "Idempotency-Key")
                    @NonNull
                    String idempotencyKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The cash handed over, an optional partial amount applied and the drawer's"
                                    + " currency.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = {
                                                @ExampleObject(name = "Full settlement, CAD", value = """
                                                                        {"cashTendered":20.00,"currencyCode":"CAD"}
                                                                        """),
                                                @ExampleObject(
                                                        name = "Split tender: 10.00 cash, the rest by card",
                                                        value = """
                                                                        {"cashTendered":10.00,"cashApplied":10.00,"currencyCode":"CAD"}
                                                                        """)
                                            }))
                    @Valid
                    @RequestBody
                    @NonNull
                    CashTenderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cashTenderService.recordCashTender(orderId, idempotencyKey, request));
    }
}
