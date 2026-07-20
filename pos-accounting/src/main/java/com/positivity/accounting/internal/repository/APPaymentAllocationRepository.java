package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.APPaymentAllocation;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for AP Payment Allocation entities.
 *
 * @see APPaymentAllocation
 */
public interface APPaymentAllocationRepository extends JpaRepository<APPaymentAllocation, UUID> {

    /**
     * Find all allocations for a given payment.
     *
     * @param paymentId UUID of the payment
     * @return List of allocations, ordered by allocation_sequence
     */
    List<APPaymentAllocation> findByPayment_PaymentIdOrderByAllocationSequenceAsc(UUID paymentId);

    /**
     * Find all allocations for a given vendor bill.
     *
     * @param vendorBillId UUID of the vendor bill
     * @return List of allocations for the bill across all payments
     */
    List<APPaymentAllocation> findByVendorBill_VendorBillId(UUID vendorBillId);

    /**
     * Calculate the sum of all allocations for a given vendor bill.
     *
     * This is more efficient than loading all allocations and summing in memory,
     * as it computes the aggregate in the database.
     *
     * @param vendorBillId UUID of the vendor bill
     * @return Sum of all allocations, or 0 if no allocations exist
     */
    @Query(
            "SELECT COALESCE(SUM(a.appliedAmount), 0) FROM APPaymentAllocation a WHERE a.vendorBill.vendorBillId = :vendorBillId")
    BigDecimal sumAllocatedAmountByVendorBillId(@Param("vendorBillId") UUID vendorBillId);

    /**
     * Batched allocation totals for many vendor bills in one query, so the Aged
     * Payables report avoids a per-bill N+1 (PR #977 finding 9). Bills with no
     * allocations are simply absent from the result (treat as zero).
     *
     * @param vendorBillIds the vendor bill ids to total
     * @return one row per bill that has at least one allocation
     */
    @Query("""
            SELECT a.vendorBill.vendorBillId AS vendorBillId, COALESCE(SUM(a.appliedAmount), 0) AS allocated
            FROM APPaymentAllocation a
            WHERE a.vendorBill.vendorBillId IN :vendorBillIds
            GROUP BY a.vendorBill.vendorBillId
            """)
    List<VendorBillAllocationSum> sumAllocatedAmountByVendorBillIdIn(
            @Param("vendorBillIds") Collection<UUID> vendorBillIds);

    /** Projection for {@link #sumAllocatedAmountByVendorBillIdIn(Collection)}. */
    interface VendorBillAllocationSum {
        UUID getVendorBillId();

        BigDecimal getAllocated();
    }
}
