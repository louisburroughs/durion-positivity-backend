package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBillLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for VendorBillLine entity.
 */
public interface VendorBillLineRepository extends JpaRepository<VendorBillLine, UUID> {

    /**
     * Find all line items for a vendor bill.
     */
    List<VendorBillLine> findByVendorBill_VendorBillIdOrderByLineNumber(UUID vendorBillId);

    /**
     * Delete all line items for a vendor bill.
     */
    void deleteByVendorBill_VendorBillId(UUID vendorBillId);
}
