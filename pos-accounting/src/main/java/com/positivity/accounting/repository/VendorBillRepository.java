package com.positivity.accounting.repository;

import com.positivity.accounting.entity.VendorBill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    List<VendorBill> findByStatus(String status);

    /**
     * Find bills received within a date range.
     */
    @Query("SELECT vb FROM VendorBill vb " +
            "WHERE vb.receivedDate >= :startDate AND vb.receivedDate < :endDate " +
            "ORDER BY vb.receivedDate DESC")
    List<VendorBill> findByReceivedDateRange(LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Find a bill by vendor reference.
     */
    Optional<VendorBill> findByVendorIdAndVendorReferenceNumber(String vendorId, String vendorReferenceNumber);

    /**
     * Find unpaid bills (status = APPROVED or RECEIVED) for a vendor.
     */
    @Query("SELECT vb FROM VendorBill vb " +
            "WHERE vb.vendorId = :vendorId " +
            "AND vb.status IN ('APPROVED', 'RECEIVED') " +
            "ORDER BY vb.dueDate ASC")
    List<VendorBill> findUnpaidBillsForVendor(String vendorId);

    /**
     * Get total amount owed to a vendor.
     */
    @Query("SELECT COALESCE(SUM(vb.totalAmount), 0) FROM VendorBill vb " +
            "WHERE vb.vendorId = :vendorId AND vb.status IN ('APPROVED', 'RECEIVED')")
    BigDecimal getTotalOwedToVendor(String vendorId);

    /**
     * Find bills due within a date range.
     */
    @Query("SELECT vb FROM VendorBill vb " +
            "WHERE vb.dueDate >= :startDate AND vb.dueDate <= :endDate " +
            "AND vb.status != 'PAID' " +
            "ORDER BY vb.dueDate ASC")
    List<VendorBill> findBillsDueInRange(LocalDate startDate, LocalDate endDate);
}
