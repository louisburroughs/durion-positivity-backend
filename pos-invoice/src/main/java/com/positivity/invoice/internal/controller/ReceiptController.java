package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.ReceiptResponse;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.service.Receipt;
import com.positivity.invoice.service.ReceiptService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
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
    public ResponseEntity<ReceiptResponse> generateReceipt(
            @PathVariable @NonNull UUID invoiceId,
            @Valid @RequestBody @NonNull GenerateReceiptRequest request) {
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
    public ResponseEntity<ReceiptResponse> reprintReceipt(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID receiptId,
            @Valid @RequestBody @NonNull ReprintReceiptRequest request) {
        Receipt receipt = receiptService.reprintReceipt(receiptId, request.reason());
        return ResponseEntity.ok(toResponse(receipt));
    }

    @PostMapping("/{invoiceId}/receipts/{receiptId}/print")
    @EmitEvent(id = "INVOICE_RECEIPT_PRINT_DELIVERY", apiVersion = "1")
    public ResponseEntity<Void> recordPrintDelivery(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID receiptId,
            @RequestBody @Valid @NonNull PrintDeliveryRequest request) {
        receiptService.recordPrintDelivery(receiptId, request.status());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{invoiceId}/receipts/{receiptId}/email")
    @EmitEvent(id = "INVOICE_RECEIPT_EMAIL_DELIVERY", apiVersion = "1")
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

    private record GenerateReceiptRequest(
            @NotNull UUID paymentIntentId,
            @NotBlank String terminalId,
            @NotBlank String templateId,
            @NotBlank String templateVersion) {
    }

    private record PrintDeliveryRequest(@NotNull ReceiptDeliveryStatus status) {
    }

    private record EmailDeliveryRequest(@NotBlank String emailAddress, @NotNull ReceiptDeliveryStatus status) {
    }

    private record ReprintReceiptRequest(@NotBlank String reason) {
    }
}
