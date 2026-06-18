package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.ReceiptResponse;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.service.Receipt;
import com.positivity.invoice.service.ReceiptService;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/invoices")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Receipt", description = "Invoice receipt generation and reprint endpoints")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(@NonNull ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping("/{invoiceId}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @EmitEvent(id = "INVOICE_RECEIPT_GENERATE", apiVersion = "1")
    @Operation(
            summary = "Generate invoice receipt",
            description = "Generate a receipt for an invoice payment using the requested terminal and template")
    @ApiResponse(responseCode = "201", description = "Receipt generated")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    public ResponseEntity<ReceiptResponse> generateReceipt(
            @PathVariable @NonNull UUID invoiceId, @Valid @RequestBody @NonNull GenerateReceiptRequest request) {
        Receipt receipt = receiptService.generateReceipt(
                invoiceId,
                request.paymentIntentId(),
                request.terminalId(),
                request.templateId(),
                request.templateVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(receipt));
    }

    @PostMapping("/{invoiceId}/receipts/{receiptId}/reprint")
    @ResponseStatus(HttpStatus.OK)
    @EmitEvent(id = "INVOICE_RECEIPT_REPRINT", apiVersion = "1")
    @Operation(
            summary = "Reprint invoice receipt",
            description = "Create a reprint of an existing receipt and record the reason for the reprint")
    @ApiResponse(responseCode = "200", description = "Receipt reprinted")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    @ApiResponse(responseCode = "409", description = "Reprint limit exceeded")
    public ResponseEntity<ReceiptResponse> reprintReceipt(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID receiptId,
            @Valid @RequestBody @NonNull ReprintReceiptRequest request) {
        Receipt receipt = receiptService.reprintReceipt(receiptId, request.reason());
        return ResponseEntity.ok(toResponse(receipt));
    }

    @PostMapping("/{invoiceId}/receipts/{receiptId}/print")
    @EmitEvent(id = "INVOICE_RECEIPT_PRINT_DELIVERY", apiVersion = "1")
    @Operation(
            summary = "Record printed receipt delivery",
            description = "Record the delivery status for a printed receipt associated with an invoice")
    @ApiResponse(responseCode = "200", description = "Print delivery recorded")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    public ResponseEntity<Void> recordPrintDelivery(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID receiptId,
            @RequestBody @Valid @NonNull PrintDeliveryRequest request) {
        receiptService.recordPrintDelivery(receiptId, request.status());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{invoiceId}/receipts/{receiptId}/email")
    @EmitEvent(id = "INVOICE_RECEIPT_EMAIL_DELIVERY", apiVersion = "1")
    @Operation(
            summary = "Email invoice receipt",
            description = "Send an invoice receipt by email and record the delivery status for the attempt")
    @ApiResponse(responseCode = "200", description = "Email delivery recorded")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    public ResponseEntity<Void> sendEmailReceipt(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID receiptId,
            @RequestBody @Valid @NonNull EmailDeliveryRequest request) {
        receiptService.sendEmailReceipt(receiptId, request.emailAddress(), request.status());
        return ResponseEntity.ok().build();
    }

    private ReceiptResponse toResponse(Receipt receipt) {
        ReceiptResponse response = new ReceiptResponse();
        response.setReceiptId(receipt.getId());
        response.setReference(receipt.getReference());
        response.setStatus(receipt.getStatus());
        return response;
    }

    @Schema(description = "Request to generate a receipt for an invoice payment")
    private record GenerateReceiptRequest(
            @NotNull
                    @Schema(
                            description = "Payment intent the receipt is generated for",
                            example = "550e8400-e29b-41d4-a716-446655440000",
                            requiredMode = REQUIRED)
                    UUID paymentIntentId,
            @NotBlank
                    @Schema(description = "Identifier of the terminal producing the receipt", example = "TERM-001", requiredMode = REQUIRED)
                    String terminalId,
            @NotBlank
                    @Schema(description = "Receipt template identifier", example = "RECEIPT_DEFAULT", requiredMode = REQUIRED)
                    String templateId,
            @NotBlank
                    @Schema(description = "Receipt template version", example = "1", requiredMode = REQUIRED)
                    String templateVersion) {}

    @Schema(description = "Request to record the delivery status of a printed receipt")
    private record PrintDeliveryRequest(
            @NotNull
                    @Schema(description = "Print delivery outcome status", example = "SUCCESS", requiredMode = REQUIRED)
                    ReceiptDeliveryStatus status) {}

    @Schema(description = "Request to email a receipt and record the delivery status")
    private record EmailDeliveryRequest(
            @NotBlank
                    @Schema(
                            description = "Recipient email address for the receipt",
                            example = "customer@example.com",
                            requiredMode = REQUIRED)
                    String emailAddress,
            @NotNull
                    @Schema(description = "Email delivery outcome status", example = "SUCCESS", requiredMode = REQUIRED)
                    ReceiptDeliveryStatus status) {}

    @Schema(description = "Request to reprint an existing receipt")
    private record ReprintReceiptRequest(
            @NotBlank
                    @Schema(
                            description = "Reason the receipt is being reprinted",
                            example = "Customer requested a duplicate copy",
                            requiredMode = REQUIRED)
                    String reason) {}
}
