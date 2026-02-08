package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * PaymentApplication - immutable record linking a payment to an invoice.
 * 
 * Business Rules:
 * - ONE payment can be applied to MULTIPLE invoices
 * - ONE invoice can have MULTIPLE payments applied
 * - Applications are ATOMIC across all target invoices in a single request
 * - Applications are IDEMPOTENT via applicationRequestId
 * - Applications are IMMUTABLE - use PaymentApplicationReversal for corrections
 * - No hard deletes - reversals are compensating transactions
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114 - Decision Record</a>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "payment_application", indexes = {
        @Index(name = "idx_payment_application_payment", columnList = "payment_id"),
        @Index(name = "idx_payment_application_invoice", columnList = "invoice_id"),
        @Index(name = "idx_payment_application_customer", columnList = "customer_id"),
        @Index(name = "idx_payment_application_request_id", columnList = "application_request_id"),
        @Index(name = "idx_payment_application_timestamp", columnList = "application_timestamp")
})
public class PaymentApplication {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "payment_application_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentApplicationId;

    @PrePersist
    public void onPrePersist() {
        if (paymentApplicationId == null) {
            paymentApplicationId = UUIDv7Generator.generate();
        }
        if (applicationTimestamp == null) {
            applicationTimestamp = Instant.now();
        }
    }

    @Column(name = "payment_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentId;

    @Column(name = "invoice_id", nullable = false, columnDefinition = "UUID")
    private UUID invoiceId;

    @Column(name = "customer_id", nullable = false, columnDefinition = "UUID")
    private UUID customerId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "applied_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal appliedAmount;

    @Column(name = "application_timestamp", nullable = false, updatable = false)
    private Instant applicationTimestamp;

    /**
     * Idempotency key for the application request.
     * Retries with same key must not create duplicate applications.
     */
    @Column(name = "application_request_id", length = 100, nullable = false, updatable = false)
    private String applicationRequestId;

    @Column(name = "trace_id", length = 100, updatable = false)
    private String traceId;

    // Audit fields (immutable)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @PreUpdate
    protected void preventUpdate() {
        throw new UnsupportedOperationException(
                "PaymentApplication records are immutable. Use PaymentApplicationReversal for corrections.");
    }
}
