package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBillLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for VendorBillLine entity.
 */
@Repository
public interface VendorBillLineRepository extends JpaRepository<VendorBillLine, UUID> {

    /**
     * Find all line items for a vendor bill.
     */
    List<VendorBillLine> findByVendorBillIdOrderByLineNumber(UUID vendorBillId);

    /**
     * Delete all line items for a vendor bill.
     */
    void deleteByVendorBillId(UUID vendorBillId);
}
