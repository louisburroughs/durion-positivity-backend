package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import com.positivity.accounting.internal.dto.InvoiceStatusResponse;
import com.positivity.accounting.internal.dto.RegenerateInvoiceFromWorkorderRequest;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.InvoiceRegenerationServiceImpl;
import com.positivity.accounting.service.BillingRulesService;
import com.positivity.accounting.service.InvoicePaymentStatusService;
import com.positivity.accounting.service.InvoiceRegenerationService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for invoice payment operations.
 * Provides endpoints for applying payments and querying invoice status.
 */
@RestController
@Tag(name = "Invoice Payments", description = "Apply payments and query invoice status.")
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"accounting:ap:view"})
@Validated
public class InvoicePaymentController {

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentController.class);

    private final InvoicePaymentStatusService paymentStatusService;
    private final BillingRulesService billingRulesService;
    private final InvoiceRegenerationService invoiceRegenerationService;

    public InvoicePaymentController(
            InvoicePaymentStatusService paymentStatusService,
            BillingRulesService billingRulesService,
            InvoiceRegenerationService invoiceRegenerationService) {
        this.paymentStatusService = paymentStatusService;
        this.billingRulesService = billingRulesService;
        this.invoiceRegenerationService = invoiceRegenerationService;
    }

    /**
     * Get current payment status of an invoice.
     *
     * @param invoiceId Invoice identifier
     * @return Current invoice status
     */
    @GetMapping("/v1/accounting/invoice/{invoiceId}/status")
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "getInvoiceStatus",
            summary = "Get Invoice Payment Status",
            description = """
                    Returns the current payment status of an invoice as tracked by the accounting module's \
                    invoice replica.
                    Use this tool to check whether an invoice is open, partially paid or paid before applying \
                    payments or credits; do not use applyPayment, which changes the status.
                    Preconditions: the invoice must be known to the accounting module.
                    Required inputs: invoiceId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the invoice is not found, and 400 when the identifier is rejected by \
                    the status service.
                    """,
            tags = {"Invoice Payments"})
    @ApiResponse(
            responseCode = "200",
            description = "Invoice status returned",
            content = @Content(schema = @Schema(implementation = InvoiceStatusResponse.class)))
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @ApiResponse(responseCode = "500", description = "Error retrieving invoice status")
    public ResponseEntity<InvoiceStatusResponse> getInvoiceStatus(
            @Parameter(description = "Invoice identifier") @PathVariable UUID invoiceId) {

        try {
            InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);
            return ResponseEntity.ok(response);

        } catch (EntityNotFoundException _) {
            log.error("Invoice not found: {}", invoiceId);
            return ResponseEntity.notFound().build();

        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for invoice status {}: {}", invoiceId, e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (Exception e) {
            log.error("Error retrieving status for invoice {}: {}", invoiceId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/v1/accounting/invoice/invoices")
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "regenerateInvoiceFromWorkorder",
            summary = "Regenerate Invoice From Workorder",
            description = """
                    Requests regeneration of an invoice from a completed workorder, either synchronously or \
                    via the asynchronous command path.
                    Use this tool when an invoice must be rebuilt from its source workorder; do not use \
                    getInvoiceStatus, which only reads the current payment status.
                    Preconditions: the workorder must exist and be in COMPLETED state.
                    Required inputs: workorderId (UUID); idempotencyKey is optional and de-duplicates \
                    repeated regeneration commands.
                    Emits an ACCOUNTING_INVOICE_REGENERATE event; on the async path the call returns \
                    202 with status PENDING and the invoice arrives later via invoice.events.v1.
                    Returns 202 when the command is accepted asynchronously, 404 when the workorder is not \
                    found, 409 when it is not COMPLETED, and 503 when the workorder service is unavailable.
                    """,
            tags = {"Invoice Payments"})
    @ApiResponse(responseCode = "200", description = "Invoice regenerated")
    @ApiResponse(
            responseCode = "202",
            description = "Regeneration command accepted (ADR-0044 async path); invoice arrives via invoice.events.v1")
    @ApiResponse(responseCode = "404", description = "Workorder not found")
    @ApiResponse(responseCode = "409", description = "Workorder is not in COMPLETED state")
    @ApiResponse(responseCode = "503", description = "Workorder service unavailable")
    @EmitEvent(id = "ACCOUNTING_INVOICE_REGENERATE", apiVersion = "1")
    public ResponseEntity<InvoiceGenerationResponse> regenerateInvoiceFromWorkorder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Workorder to rebuild the invoice from, with an optional idempotency key.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Regenerate invoice", value = """
                                                                    {"workorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "idempotencyKey":"regen-2026-08-13-001"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RegenerateInvoiceFromWorkorderRequest request) {

        InvoiceGenerationResponse response = invoiceRegenerationService.regenerateInvoiceFromWorkorder(
                request.getWorkorderId(), request.getIdempotencyKey());
        if (InvoiceRegenerationServiceImpl.STATUS_PENDING.equals(response.getStatus())) {
            return ResponseEntity.accepted().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/v1/accounting/invoice/rules/{customerId}")
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "getAccountingBillingRules",
            summary = "Get Customer Billing Rules",
            description = """
                    Returns the billing rule references configured for a customer, fetched from the customer \
                    service.
                    Use this tool to inspect how a customer is billed before generating or regenerating \
                    invoices; do not use regenerateInvoiceFromWorkorder, which performs the regeneration \
                    itself.
                    Preconditions: the customer must exist in the customer service.
                    Required inputs: customerId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection over a \
                    cross-service call.
                    Returns 404 when the customer is not found, and 503 when the customer service is \
                    unavailable.
                    """,
            tags = {"Invoice Payments"})
    @ApiResponse(responseCode = "200", description = "Billing rules returned")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    @ApiResponse(responseCode = "503", description = "Customer service unavailable")
    public ResponseEntity<BillingRuleRefResponse> getAccountingBillingRules(
            @Parameter(description = "Customer identifier") @PathVariable UUID customerId) {
        log.info("Fetching billing rules for customer {}", customerId);
        BillingRuleRefResponse rules = billingRulesService.getBillingRules(customerId);
        return ResponseEntity.ok(rules);
    }
}
