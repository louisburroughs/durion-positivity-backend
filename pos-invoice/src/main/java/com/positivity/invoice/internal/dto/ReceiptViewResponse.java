package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.ReceiptDeliveryMethod;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.internal.enums.ReceiptStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * Full read view of a single receipt, for the frontend receipt page (issue #2214).
 *
 * <p>Fields drawn from the {@code PaymentIntent} entity are limited to what it actually
 * stores: no PAN, CVV, card brand, last-4 or authorization code are captured anywhere in
 * pos-invoice (Story #9, AC8), so those are not present on this view.
 */
@Data
@Schema(description = "Full read view of a receipt, including its invoice, payment and delivery context")
public class ReceiptViewResponse {

    @NotNull
    @Schema(
            description = "Unique identifier of the receipt",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = REQUIRED)
    private UUID receiptId;

    @NotNull
    @Schema(
            description = "Human-readable receipt reference",
            example = "RCP-INV-12345-20260115T143022Z-001",
            requiredMode = REQUIRED)
    private String reference;

    @NotNull
    @Schema(description = "Current status of the receipt", example = "GENERATED", requiredMode = REQUIRED)
    private ReceiptStatus status;

    @NotNull
    @Schema(
            description = "Invoice the receipt documents",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    private UUID invoiceId;

    @Schema(description = "Human-readable invoice number", example = "INV-12345", requiredMode = NOT_REQUIRED)
    private String invoiceNumber;

    @NotNull
    @Schema(
            description = "Payment intent the receipt documents",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID paymentIntentId;

    @Schema(description = "Amount captured against the payment intent", example = "149.99", requiredMode = NOT_REQUIRED)
    private BigDecimal paidAmount;

    @Schema(
            description = "Payment gateway/processor that handled the transaction (e.g. \"stripe\"); "
                    + "pos-invoice does not store card brand, last 4 or authorization code",
            example = "stripe",
            requiredMode = NOT_REQUIRED)
    private String paymentMethod;

    @Schema(
            description = "Opaque gateway transaction reference used for capture, void and inquiry",
            example = "ch_3P0a1b2c3d4e5f",
            requiredMode = NOT_REQUIRED)
    private String gatewayReference;

    @NotNull
    @Schema(description = "Cashier who generated the receipt", example = "cashier-001", requiredMode = REQUIRED)
    private String cashierId;

    @NotNull
    @Schema(description = "Terminal that produced the receipt", example = "TERM-001", requiredMode = REQUIRED)
    private String terminalId;

    @NotNull
    @Schema(description = "Receipt template identifier", example = "RECEIPT_DEFAULT", requiredMode = REQUIRED)
    private String templateId;

    @NotNull
    @Schema(
            description = "Receipt template version, immutable once captured at generation",
            example = "1",
            requiredMode = REQUIRED)
    private String templateVersion;

    @Schema(description = "How the receipt was last delivered", example = "EMAIL", requiredMode = NOT_REQUIRED)
    private ReceiptDeliveryMethod deliveryMethod;

    @Schema(description = "Outcome of the last delivery attempt", example = "SUCCESS", requiredMode = NOT_REQUIRED)
    private ReceiptDeliveryStatus deliveryStatus;

    @Schema(
            description = "Recipient address of the last email delivery attempt",
            example = "customer@example.com",
            requiredMode = NOT_REQUIRED)
    private String deliveryEmailAddress;

    @NotNull
    @Schema(description = "Number of times the receipt has been reprinted", example = "0", requiredMode = REQUIRED)
    private int reprintCount;

    @Schema(
            description = "Reason given for the most recent reprint",
            example = "Customer requested a duplicate copy",
            requiredMode = NOT_REQUIRED)
    private String lastReprintReason;

    @Schema(
            description = "Actor who performed the most recent reprint",
            example = "cashier-002",
            requiredMode = NOT_REQUIRED)
    private String lastReprintedBy;

    @NotNull
    @Schema(description = "When the receipt was originally generated (printed-at)", requiredMode = REQUIRED)
    private Instant createdAt;
}
