package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.CreateDepositRequest;
import com.positivity.invoice.internal.dto.DepositCreditResponse;
import com.positivity.invoice.internal.enums.DepositSourceType;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.DepositCreditService;
import com.positivity.invoice.internal.service.model.CreateDepositCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deposit / down-payment credit endpoints (odoo-parity story E4, issue #1085,
 * spec R7.4). Registers
 * deposits taken at pos-order checkout, exposes the credit and its
 * application-audit trail, and
 * refunds a credit when its source is cancelled.
 */
@RestController
@RequestMapping("/v1/invoices/deposits")
@RequiredArgsConstructor
@Tag(name = "Deposit Credits", description = "Deposit / down-payment credits and their application to settlements")
// No class-level @PreAuthorize: every handler names its own permission (#1612). See the note on
// InvoiceController — a class-level guard is unioned into x-required-permissions, so the two reads
// here advertised invoice:manage alongside the view code they actually require.
public class DepositCreditController {

    private final DepositCreditService depositCreditService;

    @Operation(
            operationId = "createDepositCredit",
            summary = "Register a Deposit Credit",
            description = """
                    Creates an AVAILABLE deposit credit for a down-payment taken at pos-order checkout, holding the \
                    amount against its source document until a settlement invoice draws it down.
                    Use this tool when a deposit was tendered against an estimate, workorder, or order; do not use \
                    refundDepositCredit, which returns a credit's remaining balance when the source is cancelled.
                    Preconditions: none beyond a priced source document — the call is idempotent on orderId, so a \
                    replay for an order that already registered a credit returns the existing record unchanged.
                    Required inputs: orderId (UUID, idempotency anchor), sourceType (ESTIMATE, WORKORDER or ORDER), \
                    sourceId (UUID) and amount (positive); partyId is optional and currencyCode defaults to USD.
                    Emits an INVOICE_DEPOSIT_CREATE event; no invoice records are touched until settlement applies \
                    the credit.
                    Returns 201 with the credit (existing or new), and 422 when the amount is not positive or the \
                    sourceType is not a known value.
                    """,
            tags = {"Deposit Credits"})
    @PostMapping
    @PreAuthorize("hasAuthority('" + InvoicePermissions.MANAGE + "')")
    @EmitEvent(id = "INVOICE_DEPOSIT_CREATE", apiVersion = "1")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DepositCreditResponse> createDeposit(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Deposit taken at checkout to register as a credit against its source document.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Workorder down-payment", value = """
                                                                    {"orderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
                                                                     "sourceType":"WORKORDER",
                                                                     "sourceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a11",
                                                                     "partyId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a12",
                                                                     "amount":200.00,
                                                                     "currencyCode":"USD"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateDepositRequest request) {
        DepositCreditResponse response =
                DepositCreditResponse.from(depositCreditService.createDeposit(new CreateDepositCommand(
                        request.getOrderId(),
                        request.getSourceType(),
                        request.getSourceId(),
                        request.getPartyId(),
                        request.getAmount(),
                        request.getCurrencyCode())));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            operationId = "getDepositCredit",
            summary = "Get a Deposit Credit",
            description = """
                    Returns a single deposit credit with its status (AVAILABLE, PARTIALLY_APPLIED, FULLY_APPLIED or \
                    REFUNDED), original amount and remaining balance.
                    Use this tool when the depositCreditId is already known; use listDepositCreditsBySource instead \
                    to find the credits held against an estimate, workorder or order.
                    Preconditions: the deposit credit must exist.
                    Required inputs: depositCreditId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no deposit credit exists for the supplied id.
                    """,
            tags = {"Deposit Credits"})
    @GetMapping("/{depositCreditId}")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.VIEW + "')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DepositCreditResponse> getDeposit(@PathVariable UUID depositCreditId) {
        return ResponseEntity.ok(DepositCreditResponse.from(depositCreditService.getDeposit(depositCreditId)));
    }

    @Operation(
            operationId = "listDepositCreditsBySource",
            summary = "List Deposit Credits by Source",
            description = """
                    Lists every deposit credit held against one source document, identified by its type and id.
                    Use this tool when checking what down-payments a settlement can draw on; use getDepositCredit \
                    instead when a specific depositCreditId is already known.
                    Preconditions: none — an unknown or credit-free source returns an empty list rather than an \
                    error.
                    Required inputs: sourceType query parameter (ESTIMATE, WORKORDER or ORDER, case-insensitive) and \
                    sourceId (UUID) query parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 422 when sourceType is not one of the known values.
                    """,
            tags = {"Deposit Credits"})
    @GetMapping
    @PreAuthorize("hasAuthority('" + InvoicePermissions.VIEW + "')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<List<DepositCreditResponse>> listBySource(
            @RequestParam String sourceType, @RequestParam UUID sourceId) {
        DepositSourceType type = DepositSourceType.valueOf(sourceType.trim().toUpperCase(Locale.ROOT));
        return ResponseEntity.ok(depositCreditService.listBySource(type, sourceId).stream()
                .map(DepositCreditResponse::from)
                .toList());
    }

    @Operation(
            operationId = "refundDepositCredit",
            summary = "Refund a Deposit Credit Balance",
            description = """
                    Zeroes the remaining balance of a deposit credit and marks it REFUNDED, recording that the \
                    deposit is returned because its source document was cancelled after the deposit was taken.
                    Use this tool on source cancellation (spec R7.4); do not use createDepositCredit, which \
                    registers a new deposit, and note the cash disbursement itself is not performed here.
                    Preconditions: the deposit credit must exist; a credit already REFUNDED is left unchanged, so \
                    the call is idempotent.
                    Required inputs: depositCreditId (UUID) as a path parameter; the reason query parameter is \
                    optional and defaults to "source cancelled"; there is no request body.
                    Emits an INVOICE_DEPOSIT_REFUND event and sets the credit's status to REFUNDED with a zero \
                    remaining balance.
                    Returns 200 with the refunded credit, and 404 when no deposit credit exists for the supplied id.
                    """,
            tags = {"Deposit Credits"})
    @PostMapping("/{depositCreditId}/refund")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.MANAGE + "')")
    @EmitEvent(id = "INVOICE_DEPOSIT_REFUND", apiVersion = "1")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DepositCreditResponse> refundDeposit(
            @PathVariable UUID depositCreditId, @RequestParam(name = "reason", required = false) String reason) {
        depositCreditService.refundDeposit(depositCreditId, reason == null ? "source cancelled" : reason);
        return ResponseEntity.ok(DepositCreditResponse.from(depositCreditService.getDeposit(depositCreditId)));
    }
}
