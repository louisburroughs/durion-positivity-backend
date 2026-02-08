package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * PaymentApplicationReversal - compensating transaction to reverse a payment
 * application.
 * 
 * Business Rules:
 * - Reversals are NEW records, not deletions
 * - Original PaymentApplication remains unchanged
 * - Reversals restore invoice balances and payment unapplied amounts
 * - Requires elevated permission (ACCOUNTING_ADMIN or AR_MANAGER)
 * - Requires non-empty reason for audit trail
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114 - Reversibility</a>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "payment_application_reversal", indexes = {
        @Index(name = "idx_reversal_original_application", columnList = "original_payment_application_id"),
        @Index(name = "idx_reversal_reversed_at", columnList = "reversed_at")
})
public class PaymentApplicationReversal {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "reversal_id", nullable = false, columnDefinition = "UUID")
    private UUID reversalId;

    @PrePersist
    public void onPrePersist() {
        if (reversalId == null) {
            reversalId = UUIDv7Generator.generate();
        }
        if (reversedAt == null) {
            reversedAt = Instant.now();
        }
    }

    @Column(name = "original_payment_application_id", nullable = false, columnDefinition = "UUID")
    private UUID originalPaymentApplicationId;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "reason", length = 1000, nullable = false)
    private String reason;

    @Column(name = "reversed_at", nullable = false, updatable = false)
    private Instant reversedAt;

    @Column(name = "reversed_by", length = 50, nullable = false, updatable = false)
    private String reversedBy;

    @Column(name = "trace_id", length = 100)
    private String traceId;
}
