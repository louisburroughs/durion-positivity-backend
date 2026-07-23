package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.AdjustmentRequest;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.dto.RevertRequest;
import com.positivity.invoice.service.InvoiceFinalizationService;
import com.positivity.invoice.service.InvoiceService;
import com.positivity.invoice.service.OrderInvoiceService;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/invoices")
@Tag(name = "Invoice", description = "Invoice generation and lifecycle management")
@PreAuthorize("hasAuthority('invoice:manage')")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoiceFinalizationService invoiceFinalizationService;
    private final OrderInvoiceService orderInvoiceService;

    public InvoiceController(
            @NonNull InvoiceService invoiceService,
            @NonNull InvoiceFinalizationService invoiceFinalizationService,
            @NonNull OrderInvoiceService orderInvoiceService) {
        this.invoiceService = invoiceService;
        this.invoiceFinalizationService = invoiceFinalizationService;
        this.orderInvoiceService = orderInvoiceService;
    }

    @PostMapping
    @EmitEvent(id = "INVOICE_CREATE", apiVersion = "1")
    @Operation(summary = "Create invoice", description = "Create invoice draft from completed workorder data")
    @ApiResponse(responseCode = "201", description = "Invoice created")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:manage"})
    public ResponseEntity<InvoiceGenerationResponse> createInvoice(
            @Valid @RequestBody @NonNull InvoiceCreationRequest request) {
        InvoiceGenerationResponse response = invoiceService.createInvoice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/from-order")
    @EmitEvent(id = "INVOICE_CREATE_FROM_ORDER", apiVersion = "1")
    @Operation(
            summary = "Create invoice from a sales order",
            description = "Create the invoice fronting a sales order at checkout (counter sale). Idempotent on "
                    + "orderId — a replay returns the existing invoice with 200. When workorderId is present and a "
                    + "workorder invoice already exists, that invoice is returned for tender instead of creating a "
                    + "duplicate.")
    @ApiResponse(responseCode = "201", description = "Invoice created")
    @ApiResponse(responseCode = "200", description = "Existing invoice returned (replay or workorder dedupe)")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:manage"})
    public ResponseEntity<OrderInvoiceResponse> createInvoiceFromOrder(
            @Valid @RequestBody @NonNull OrderInvoiceCreationRequest request) {
        OrderInvoiceResponse response = orderInvoiceService.createInvoiceForOrder(request);
        HttpStatus status = response.isExisting() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{invoiceId}")
    @EmitEvent(id = "INVOICE_GET", apiVersion = "1")
    @Operation(summary = "Get invoice", description = "Get invoice details")
    @ApiResponse(responseCode = "200", description = "Invoice found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:manage"})
    public ResponseEntity<InvoiceDetailsResponse> getInvoice(@PathVariable @NonNull UUID invoiceId) {
        return ResponseEntity.ok(invoiceService.getInvoice(invoiceId));
    }

    @PostMapping("/{invoiceId}/adjustments")
    @EmitEvent(id = "INVOICE_ADJUSTMENT_APPLY", apiVersion = "1")
    @Operation(
            summary = "Apply invoice adjustment",
            description = "Apply discount, fee, correction, or warranty credit to a draft invoice")
    @ApiResponse(responseCode = "200", description = "Adjustment applied")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:manage"})
    public ResponseEntity<InvoiceDetailsResponse> applyAdjustment(
            @PathVariable @NonNull UUID invoiceId, @Valid @RequestBody @NonNull AdjustmentRequest request) {
        return ResponseEntity.ok(invoiceService.applyAdjustment(invoiceId, request));
    }

    @PostMapping("/{invoiceId}/finalize")
    @EmitEvent(id = "INVOICE_FINALIZED", apiVersion = "1")
    @Operation(
            summary = "Finalize invoice",
            description =
                    "Transition invoice from DRAFT to FINALIZED; enforces permission matrix and emits InvoiceFinalized event for async GL posting (Story #13)")
    @ApiResponse(responseCode = "200", description = "Invoice finalized")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:finalize"})
    @PreAuthorize("hasAuthority('invoice:finalize')")
    public ResponseEntity<InvoiceDetailsResponse> finalizeInvoice(
            @PathVariable @NonNull UUID invoiceId, @Valid @RequestBody @NonNull FinalizationRequest request) {
        return ResponseEntity.ok(invoiceFinalizationService.completeInvoice(invoiceId, request));
    }

    @PostMapping("/{invoiceId}/revert")
    @EmitEvent(id = "INVOICE_DRAFT_REVERT", apiVersion = "1")
    @Operation(
            summary = "Revert finalized invoice",
            description =
                    "Revert a FINALIZED invoice back to DRAFT within 24h of finalization and before GL posting (Story #13, AC6)")
    @ApiResponse(responseCode = "200", description = "Invoice reverted to DRAFT")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:finalize"})
    @PreAuthorize("hasAuthority('invoice:finalize')")
    public ResponseEntity<InvoiceDetailsResponse> revertInvoice(
            @PathVariable @NonNull UUID invoiceId, @Valid @RequestBody @NonNull RevertRequest request) {
        return ResponseEntity.ok(
                invoiceFinalizationService.revert(invoiceId, request.getManagerApprovalCode(), request.getReason()));
    }
}
