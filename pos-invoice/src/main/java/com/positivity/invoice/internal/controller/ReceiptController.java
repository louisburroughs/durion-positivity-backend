package com.positivity.invoice.internal.controller;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.ReceiptResponse;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.internal.service.Receipt;
import com.positivity.invoice.internal.service.ReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @Operation(operationId = "generateReceipt", summary = "Generate Receipt for Invoice Payment", description = """
                    Generates a receipt record for an invoice payment, assigning a unique reference built from the \
                    invoice number, a UTC timestamp and a per-invoice sequence, with the cashier taken from the \
                    security context.
                    Use this tool once per payment after tender; do not use reprintReceipt, which duplicates a \
                    receipt that already exists.
                    Preconditions: the invoice and payment intent must exist, the intent must belong to the \
                    invoice, and the caller needs the GENERATE_RECEIPT authority.
                    Required inputs: paymentIntentId (UUID), terminalId, templateId and templateVersion.
                    Emits an INVOICE_RECEIPT_GENERATE event and stores the receipt in GENERATED status with a zero \
                    reprint count; the receipt also becomes a downloadable artifact of the invoice.
                    Returns 201 with the receipt reference, 404 when the invoice or payment intent does not exist \
                    or the intent belongs to a different invoice, and 403 when the GENERATE_RECEIPT authority is \
                    missing.
                    """)
    @ApiResponse(responseCode = "201", description = "Receipt generated")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    public ResponseEntity<ReceiptResponse> generateReceipt(
            @PathVariable @NonNull UUID invoiceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Payment, terminal and template identifying what the receipt documents.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Counter receipt", value = """
                                                                    {"paymentIntentId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
                                                                     "terminalId":"TERM-001",
                                                                     "templateId":"RECEIPT_DEFAULT",
                                                                     "templateVersion":"1"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    GenerateReceiptRequest request) {
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
    @Operation(operationId = "reprintReceipt", summary = "Reprint an Existing Receipt", description = """
                    Records a reprint of an existing receipt, incrementing its reprint count and capturing the \
                    reason and the reprinting actor for audit.
                    Use this tool when a customer needs a duplicate copy; do not use generateReceipt, which creates \
                    a new receipt for a payment that has none yet.
                    Preconditions: the receipt must exist, and its reprint count must be below 5 unless the caller \
                    holds the SUPERVISOR_OVERRIDE authority.
                    Required inputs: receiptId (UUID) as a path parameter and a non-blank reason in the body.
                    Emits an INVOICE_RECEIPT_REPRINT event and updates the receipt's reprint count, last reprint \
                    reason and last reprinted-by.
                    Returns 200 with the receipt, 404 when the receipt does not exist, and 409 when the reprint \
                    limit of 5 is exceeded without a supervisor override.
                    """)
    @ApiResponse(responseCode = "200", description = "Receipt reprinted")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    @ApiResponse(responseCode = "409", description = "Reprint limit exceeded")
    public ResponseEntity<ReceiptResponse> reprintReceipt(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID receiptId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Business reason the duplicate copy is being produced.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Duplicate copy", value = """
                                                                    {"reason":"Customer requested a duplicate copy"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ReprintReceiptRequest request) {
        Receipt receipt = receiptService.reprintReceipt(receiptId, request.reason());
        return ResponseEntity.ok(toResponse(receipt));
    }

    @PostMapping("/{invoiceId}/receipts/{receiptId}/print")
    @EmitEvent(id = "INVOICE_RECEIPT_PRINT_DELIVERY", apiVersion = "1")
    @Operation(
            operationId = "recordReceiptPrintDelivery",
            summary = "Record Printed Receipt Delivery Status",
            description = """
                    Records the outcome of printing a receipt at the terminal, stamping the receipt's delivery \
                    method as PRINT with the reported status; the physical printing itself happens client-side, not \
                    here.
                    Use this tool after the terminal reports its print result; do not use recordReceiptEmailDelivery, \
                    which records an email delivery attempt with its recipient address.
                    Preconditions: the receipt must already exist via generateReceipt.
                    Required inputs: receiptId (UUID) as a path parameter and status (SUCCESS or FAILED) in the \
                    body.
                    Emits an INVOICE_RECEIPT_PRINT_DELIVERY event and overwrites the receipt's delivery method and \
                    status.
                    Returns 200 with an empty body on success, and 404 when the receipt does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Print delivery recorded")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    public ResponseEntity<Void> recordPrintDelivery(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID receiptId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Print outcome reported by the terminal.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Successful print", value = """
                                                                    {"status":"SUCCESS"}
                                                                    """)))
                    @RequestBody
                    @Valid
                    @NonNull
                    PrintDeliveryRequest request) {
        receiptService.recordPrintDelivery(receiptId, request.status());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{invoiceId}/receipts/{receiptId}/email")
    @EmitEvent(id = "INVOICE_RECEIPT_EMAIL_DELIVERY", apiVersion = "1")
    @Operation(
            operationId = "recordReceiptEmailDelivery",
            summary = "Record Emailed Receipt Delivery Status",
            description = """
                    Records the outcome of emailing a receipt to a customer, stamping the receipt's delivery method \
                    as EMAIL with the recipient address and the reported status; this endpoint records the attempt \
                    rather than dispatching the email itself.
                    Use this tool after an email delivery attempt completes; do not use recordReceiptPrintDelivery, \
                    which records a terminal print outcome.
                    Preconditions: the receipt must already exist via generateReceipt.
                    Required inputs: receiptId (UUID) as a path parameter plus emailAddress and status (SUCCESS or \
                    FAILED) in the body.
                    Emits an INVOICE_RECEIPT_EMAIL_DELIVERY event and overwrites the receipt's delivery method, \
                    address and status.
                    Returns 200 with an empty body on success, and 404 when the receipt does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Email delivery recorded")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    public ResponseEntity<Void> sendEmailReceipt(
            @PathVariable @NonNull UUID invoiceId,
            @PathVariable @NonNull UUID receiptId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Recipient address and outcome of the email delivery attempt.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Successful email", value = """
                                                                    {"emailAddress":"customer@example.com",
                                                                     "status":"SUCCESS"}
                                                                    """)))
                    @RequestBody
                    @Valid
                    @NonNull
                    EmailDeliveryRequest request) {
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
            @Schema(
                    description = "Identifier of the terminal producing the receipt",
                    example = "TERM-001",
                    requiredMode = REQUIRED)
            String terminalId,

            @NotBlank
            @Schema(description = "Receipt template identifier", example = "RECEIPT_DEFAULT", requiredMode = REQUIRED)
            String templateId,

            @NotBlank @Schema(description = "Receipt template version", example = "1", requiredMode = REQUIRED)
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
