package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.APPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for AP Payment Allocation entities.
 * 
 * @see APPaymentAllocation
 */
@Repository
public interface APPaymentAllocationRepository extends JpaRepository<APPaymentAllocation, UUID> {

    /**
     * Find all allocations for a given payment.
     * 
     * @param paymentId UUID of the payment
     * @return List of allocations, ordered by allocation_sequence
     */
    List<APPaymentAllocation> findByPaymentIdOrderByAllocationSequenceAsc(UUID paymentId);

    /**
     * Find all allocations for a given vendor bill.
     * 
     * @param vendorBillId UUID of the vendor bill
     * @return List of allocations for the bill across all payments
     */
    List<APPaymentAllocation> findByVendorBillId(UUID vendorBillId);
}
