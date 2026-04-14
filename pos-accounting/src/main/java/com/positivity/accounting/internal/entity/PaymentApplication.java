package com.positivity.accounting.internal.entity;

import static jakarta.persistence.FetchType.LAZY;

import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "payment_application",
        indexes = {
            @Index(name = "idx_payment_application_payment", columnList = "payment_id"),
            @Index(name = "idx_payment_application_customer", columnList = "customer_id"),
            @Index(name = "idx_payment_application_timestamp", columnList = "application_timestamp")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_payment_application_idempotency",
                    columnNames = {"application_request_id", "invoice_id"})
        })
public class PaymentApplication {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "payment_application_id", nullable = false, columnDefinition = "UUID")
    private UUID paymentApplicationId;

    @PrePersist
    public void onPrePersist() {
        if (applicationTimestamp == null) {
            applicationTimestamp = Instant.now(Clock.systemUTC());
        }
    }

    @ToString.Exclude
    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, columnDefinition = "UUID")
    private ReceivablePayment payment;

    public UUID getPaymentId() {
        return payment != null ? payment.getPaymentId() : null;
    }

    @Column(name = "invoice_id", nullable = false, columnDefinition = "UUID")
    private UUID invoiceId;

    @Column(name = "customer_id", nullable = false, columnDefinition = "UUID")
    private UUID customerId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "applied_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal appliedAmount;

    /**
     * Invoice balance before this application was made.
     * Stored to support idempotent retries without external service calls.
     */
    @Column(name = "invoice_balance_before", precision = 19, scale = 4)
    private BigDecimal invoiceBalanceBefore;

    /**
     * Invoice balance after this application was made.
     * Stored to support idempotent retries without external service calls.
     */
    @Column(name = "invoice_balance_after", precision = 19, scale = 4)
    private BigDecimal invoiceBalanceAfter;

    /**
     * Invoice status at the time of application.
     * Stored to support idempotent retries without external service calls.
     */
    @Column(name = "invoice_status", length = 50)
    private InvoiceStatus invoiceStatus;

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
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @PreUpdate
    protected void preventUpdate() {
        throw new UnsupportedOperationException(
                "PaymentApplication records are immutable. Use PaymentApplicationReversal for corrections.");
    }

    public PaymentApplication(UUID paymentApplicationId) {
        this.paymentApplicationId = paymentApplicationId;
    }
}
