package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

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
public class PaymentClearedEvent {

    /**
     * Unique event ID (used as sourceEventId for idempotency).
     */
    @JsonProperty("eventId")
    private UUID eventId;

    /**
     * Organization ID.
     */
    @JsonProperty("organizationId")
    private UUID organizationId;

    /**
     * Payment ID from Payment domain.
     */
    @JsonProperty("paymentId")
    private UUID paymentId;

    /**
     * Customer ID who made the payment.
     */
    @JsonProperty("customerId")
    private UUID customerId;

    /**
     * Payment amount (full cleared amount available for application).
     */
    @JsonProperty("amount")
    private BigDecimal amount;

    /**
     * Currency code (ISO 4217 - e.g., "USD").
     */
    @JsonProperty("currencyCode")
    private String currencyCode;

    /**
     * Payment method (e.g., "CREDIT_CARD", "CHECK", "ACH", "WIRE").
     */
    @JsonProperty("paymentMethod")
    private String paymentMethod;

    /**
     * External reference (e.g., transaction ID from payment processor).
     */
    @JsonProperty("externalReference")
    private String externalReference;

    /**
     * Timestamp when payment cleared.
     */
    @JsonProperty("clearedAt")
    private Instant clearedAt;

    /**
     * Original event timestamp.
     */
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
