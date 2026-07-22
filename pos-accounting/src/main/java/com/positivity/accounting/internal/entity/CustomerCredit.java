package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * CustomerCredit - AR credit balance created from overpayments.
 *
 * Business Rules:
 * - Created when payment amount exceeds invoice application total
 * - Represents remaining payment value as explicit AR credit
 * - Once created, payment.unappliedAmount should be 0 (credit is the
 * representation)
 * - Can be drawn down against a future invoice or refunded (issue #992); each
 * draw-down is recorded as a {@link CustomerCreditTransaction} and relieves the
 * Customer Credit Liability (2300) recognized at issuance
 *
 * Example:
 * - Payment: $150
 * - Applied to Invoice A: $100
 * - Overpayment: $50 → CustomerCredit created for $50
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114 - Overpayment Policy</a>
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/992">Issue
 *      #992 - Credit lifecycle liability relief</a>
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
        name = "customer_credit",
        indexes = {
            @Index(name = "idx_customer_credit_customer", columnList = "customer_id"),
            @Index(name = "idx_customer_credit_payment", columnList = "source_payment_id"),
            @Index(name = "idx_customer_credit_created_at", columnList = "created_at"),
            @Index(name = "idx_customer_credit_status", columnList = "status")
        })
public class CustomerCredit {

    /** Currency scale used for the derived open-amount arithmetic. */
    private static final int CURRENCY_SCALE = 2;

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "credit_id", nullable = false, columnDefinition = "UUID")
    private UUID creditId;

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

    /**
     * Consumption state derived from {@link #appliedAmount} / {@link #refundedAmount}
     * (issue #992). Maintained by the service on every draw-down; never set directly.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomerCreditStatus status = CustomerCreditStatus.AVAILABLE;

    /** Cumulative amount applied against invoices (issue #992). */
    @Column(name = "applied_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal appliedAmount = BigDecimal.ZERO;

    /** Cumulative amount refunded to the customer (issue #992). */
    @Column(name = "refunded_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    /**
     * Optimistic lock (issue #992): two concurrent apply/refund requests must not both
     * pass the remaining-amount check and over-draw the 2300 liability.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Audit fields (immutable)
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * The credit still owed to the customer: {@code amount − applied − refunded}. This is
     * the credit's contribution to the Customer Credit Liability (2300) control account, so
     * {@code Σ openAmount} over all credits is exactly what #975 AC-7 reconciles against.
     *
     * <p><b>Rounds DOWN, deliberately.</b> The underlying columns are {@code numeric(19,4)} and
     * the request DTOs accept four decimals, so this value can carry sub-cent precision. Rounding
     * HALF_UP would <em>invent</em> open credit: a credit of {@code 100.0050} would report
     * {@code 100.01} open, the over-draw gate would admit an application of {@code 100.01}, and
     * the flush would then violate {@code chk_customer_credit_consumed_within_amount} — a 500
     * with no {@code ApiError} envelope instead of a clean 409. Rounding DOWN can only ever
     * understate what may be drawn, which is the safe direction for a liability.
     *
     * @return remaining open amount, at currency scale, never rounded upward
     */
    @Transient
    public BigDecimal getOpenAmount() {
        BigDecimal issued = amount == null ? BigDecimal.ZERO : amount;
        return issued.subtract(nullSafe(appliedAmount))
                .subtract(nullSafe(refundedAmount))
                .setScale(CURRENCY_SCALE, RoundingMode.DOWN);
    }

    /**
     * Unrounded remaining amount, used to derive the lifecycle status so it agrees with the
     * {@code sumOpenAmount()} reconciliation query (which is likewise unrounded). Using the
     * cent-rounded {@link #getOpenAmount()} here would mark a credit with a {@code 0.0049}
     * residual as CONSUMED while the reconciliation still counted that residual against the
     * 2300 control account.
     *
     * @return {@code amount − applied − refunded} at full column precision
     */
    @Transient
    public BigDecimal getExactOpenAmount() {
        BigDecimal issued = amount == null ? BigDecimal.ZERO : amount;
        return issued.subtract(nullSafe(appliedAmount)).subtract(nullSafe(refundedAmount));
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
