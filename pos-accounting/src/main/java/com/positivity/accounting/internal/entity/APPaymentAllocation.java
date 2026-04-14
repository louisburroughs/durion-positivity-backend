package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * AP Payment Allocation entity - tracks allocation of a single payment across
 * one or more vendor bills.
 *
 * <p>
 * Allocation rules (BR-4 from Issue #128):
 * <ul>
 * <li>Explicit allocations must be validated: amounts non-negative, sum ≤ gross
 * payment amount, bills must be payable/open</li>
 * <li>Automatic allocation (if not explicit): oldest due date first (nulls
 * last), then invoice date, then bill ID</li>
 * <li>Partial allocations are allowed</li>
 * <li>Unapplied remainder recorded in APPayment.unappliedAmount</li>
 * </ul>
 *
 * <p>
 * Immutable after creation: allocations are persisted atomically with gateway
 * success.
 *
 * @see APPayment
 * @see VendorBill
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "ap_payment_allocation",
        indexes = {
            @Index(name = "idx_ap_payment_allocation_payment", columnList = "payment_id"),
            @Index(name = "idx_ap_payment_allocation_bill", columnList = "vendor_bill_id")
        })
public class APPaymentAllocation {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "allocation_id", nullable = false, columnDefinition = "UUID")
    private UUID allocationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private APPayment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_bill_id", nullable = false)
    private VendorBill vendorBill;

    @Column(name = "applied_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal appliedAmount;

    @Column(name = "allocation_sequence")
    private Integer allocationSequence;

    // Audit field
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public APPaymentAllocation(UUID paymentId, UUID vendorBillId, BigDecimal appliedAmount) {
        setPaymentId(paymentId);
        setVendorBillId(vendorBillId);
        this.appliedAmount = appliedAmount;
    }

    @Transient
    public UUID getPaymentId() {
        return payment != null ? payment.getPaymentId() : null;
    }

    public void setPaymentId(UUID paymentId) {
        this.payment = paymentId == null ? null : new APPayment(paymentId);
    }

    @Transient
    public UUID getVendorBillId() {
        return vendorBill != null ? vendorBill.getVendorBillId() : null;
    }

    public void setVendorBillId(UUID vendorBillId) {
        this.vendorBill = vendorBillId == null ? null : new VendorBill(vendorBillId);
    }
}
