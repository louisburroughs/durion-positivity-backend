package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Event payload for PaymentCleared domain event.
 * Emitted by Payment domain when a payment clears (e.g., credit card
 * authorization, check clearing).
 * Consumed by Accounting domain to create ReceivablePayment record available
 * for application.
 *
 * @see com.positivity.accounting.internal.entity.ReceivablePayment
 * @see com.positivity.accounting.internal.service.PaymentApplicationServiceImpl#handlePaymentCleared
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Event payload emitted when a payment clears and becomes available for application")
public class PaymentClearedEvent {

    /**
     * Unique event ID (used as sourceEventId for idempotency).
     */
    @Schema(
            description = "Unique event identifier, used as the source event id for idempotency",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @JsonProperty("eventId")
    private UUID eventId;

    /**
     * Organization ID.
     */
    @Schema(
            description = "Identifier of the organization that owns the payment",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @JsonProperty("organizationId")
    private UUID organizationId;

    /**
     * Payment ID from Payment domain.
     */
    @Schema(
            description = "Identifier of the payment from the Payment domain",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @JsonProperty("paymentId")
    private UUID paymentId;

    /**
     * Customer ID who made the payment.
     */
    @Schema(
            description = "Identifier of the customer who made the payment",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = REQUIRED)
    @JsonProperty("customerId")
    private UUID customerId;

    /**
     * Payment amount (full cleared amount available for application).
     */
    @Schema(
            description = "Full cleared payment amount available for application",
            example = "1250.00",
            requiredMode = REQUIRED)
    @JsonProperty("amount")
    private BigDecimal amount;

    /**
     * Currency code (ISO 4217 - e.g., "USD").
     */
    @Schema(description = "Currency code (ISO 4217)", example = "USD", requiredMode = REQUIRED)
    @JsonProperty("currencyCode")
    private String currencyCode;

    /**
     * Payment method (e.g., "CREDIT_CARD", "CHECK", "ACH", "WIRE").
     */
    @Schema(
            description = "Payment method (e.g., CREDIT_CARD, CHECK, ACH, WIRE)",
            example = "CREDIT_CARD",
            requiredMode = REQUIRED)
    @JsonProperty("paymentMethod")
    private String paymentMethod;

    /**
     * External reference (e.g., transaction ID from payment processor).
     */
    @Schema(
            description = "External reference such as the payment processor transaction id",
            example = "txn_abc123xyz",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("externalReference")
    private String externalReference;

    /**
     * Timestamp when payment cleared.
     */
    @Schema(
            description = "Timestamp when the payment cleared (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("clearedAt")
    private Instant clearedAt;

    /**
     * Original event timestamp.
     */
    @Schema(
            description = "Original event timestamp (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("eventTimestamp")
    private Instant eventTimestamp;

    /**
     * Validate required fields for event processing.
     *
     * @throws IllegalArgumentException if required fields are missing
     */
    public void validate() {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("currencyCode is required");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new IllegalArgumentException("paymentMethod is required");
        }
    }
}
