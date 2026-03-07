package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.PaymentFlow;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a payment intent for a card transaction against an invoice.
 *
 * <p>
 * Stores the lifecycle state, amounts, and gateway metadata for each
 * payment attempt. Only tokenized payment references are stored — no PAN
 * or CVV (AC8, Story #9).
 *
 * Story #9.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@Table(name = "payment_intents")
public class PaymentIntent {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false)
    private UUID invoiceId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentIntentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_flow", nullable = false, length = 20)
    private PaymentFlow paymentFlow;

    /**
     * Tokenised card reference provided by the payment form.
     * No PAN, no CVV. (AC8 — no PAN storage)
     */
    @Column(name = "payment_token", nullable = false, length = 255)
    private String paymentToken;

    @Column(name = "authorized_amount", precision = 15, scale = 2)
    private BigDecimal authorizedAmount;

    @Column(name = "captured_amount", precision = 15, scale = 2)
    private BigDecimal capturedAmount;

    @Column(name = "voided_remainder_amount", precision = 15, scale = 2)
    private BigDecimal voidedRemainderAmount;

    /** Identifies which gateway processed this payment (e.g., "stripe"). */
    @Column(name = "gateway_provider", length = 50)
    private String gatewayProvider;

    /** Raw JSON response from the gateway — retained for audit (AC9). */
    @Column(name = "gateway_response", columnDefinition = "TEXT")
    private String gatewayResponse;

    /** Opaque gateway transaction reference used for capture, void, and inquiry. */
    @Column(name = "gateway_reference", length = 100)
    private String gatewayReference;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
