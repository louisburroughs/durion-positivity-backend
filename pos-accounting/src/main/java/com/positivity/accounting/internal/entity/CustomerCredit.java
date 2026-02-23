package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * CustomerCredit - AR credit balance created from overpayments.
 * 
 * Business Rules:
 * - Created when payment amount exceeds invoice application total
 * - Represents remaining payment value as explicit AR credit
 * - Can be applied to future invoices (not implemented in CAP:051)
 * - Once created, payment.unappliedAmount should be 0 (credit is the
 * representation)
 * 
 * Example:
 * - Payment: $150
 * - Applied to Invoice A: $100
 * - Overpayment: $50 → CustomerCredit created for $50
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114 - Overpayment Policy</a>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "customer_credit", indexes = {
        @Index(name = "idx_customer_credit_customer", columnList = "customer_id"),
        @Index(name = "idx_customer_credit_payment", columnList = "source_payment_id"),
        @Index(name = "idx_customer_credit_created_at", columnList = "created_at")
})
public class CustomerCredit {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "credit_id", nullable = false, columnDefinition = "UUID")
    private UUID creditId;

    @PrePersist
    public void onPrePersist() {
        if (creditId == null) {
            creditId = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            }
    }

    @Column(name = "customer_id", nullable = false, columnDefinition = "UUID")
    private UUID customerId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "source_payment_id", nullable = false, columnDefinition = "UUID")
    private UUID sourcePaymentId;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    // Audit fields (immutable)
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy;
}
