package com.positivity.accounting.repository;

import com.positivity.accounting.entity.VendorBill;
import com.positivity.accounting.enums.VendorBillStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Vendor Bill entity.
 * Supports querying bills by status, date range, and payment tracking.
 */
@Repository
public interface VendorBillRepository extends JpaRepository<VendorBill, String> {

        /**
         * Find all bills for a vendor.
         */
        List<VendorBill> findByVendorId(String vendorId);

        /**
         * Find bills by status.
         */
        List<VendorBill> findByStatus(VendorBillStatus status);

        /**
         * Find bills received within a date range (based on billDate).
         */
        @Query("SELECT vb FROM VendorBill vb " +
                        "WHERE vb.billDate >= :startDate AND vb.billDate < :endDate " +
                        "ORDER BY vb.billDate DESC")
        List<VendorBill> findByBillDateRange(LocalDate startDate, LocalDate endDate);

        /**
         * Find a bill by vendor and bill number.
         */
        Optional<VendorBill> findByVendorIdAndBillNumber(String vendorId, String billNumber);

        /**
         * Find unpaid bills (status = APPROVED or PENDING_REVIEW) for a vendor.
         */
        @Query("SELECT vb FROM VendorBill vb " +
                        "WHERE vb.vendorId = :vendorId " +
                        "AND vb.status IN (com.positivity.accounting.enums.VendorBillStatus.APPROVED, com.positivity.accounting.enums.VendorBillStatus.PENDING_REVIEW) "
                        +
                        "ORDER BY vb.dueDate ASC")
        List<VendorBill> findUnpaidBillsForVendor(String vendorId);

        /**
         * Get total amount owed to a vendor (APPROVED or PENDING_REVIEW status).
         */
        @Query("SELECT COALESCE(SUM(vb.totalAmount), 0) FROM VendorBill vb " +
                        "WHERE vb.vendorId = :vendorId " +
                        "AND vb.status IN (com.positivity.accounting.enums.VendorBillStatus.APPROVED, com.positivity.accounting.enums.VendorBillStatus.PENDING_REVIEW)")
        BigDecimal getTotalOwedToVendor(String vendorId);

        /**
         * Find bills due within a date range (excluding PAID status).
         */
        @Query("SELECT vb FROM VendorBill vb " +
                        "WHERE vb.dueDate >= :startDate AND vb.dueDate <= :endDate " +
                        "AND vb.status != com.positivity.accounting.enums.VendorBillStatus.PAID " +
                        "ORDER BY vb.dueDate ASC")
        List<VendorBill> findBillsDueInRange(LocalDate startDate, LocalDate endDate);
}
