package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Vendor Bill entity.
 * Supports querying bills by status, date range, and payment tracking.
 */
@Repository
public interface VendorBillRepository extends JpaRepository<VendorBill, UUID> {

        /**
         * Find all bills for a vendor.
         */
        List<VendorBill> findByVendorId(UUID vendorId);

        /**
         * Find bills by status.
         */
        List<VendorBill> findByStatus(VendorBillStatus status);

        /**
         * Find bills for a vendor with a specific status.
         */
        List<VendorBill> findByVendorIdAndStatus(UUID vendorId, VendorBillStatus status);

        /**
         * Find bills received within a date range (based on billDate).
         */
        @Query("SELECT vb FROM VendorBill vb " +
                        "WHERE vb.billDate >= :startDate AND vb.billDate < :endDate " +
                        "ORDER BY vb.billDate DESC")
        List<VendorBill> findByBillDateRange(LocalDateTime startDate, LocalDateTime endDate);

        /**
         * Find a bill by vendor and bill number.
         */
        Optional<VendorBill> findByVendorIdAndBillNumber(UUID vendorId, String billNumber);

        /**
         * Find unpaid bills (status = APPROVED or PENDING_REVIEW) for a vendor.
         */
        @Query("SELECT vb FROM VendorBill vb " +
                        "WHERE vb.vendorId = :vendorId " +
                        "AND vb.status IN (com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED, com.positivity.accounting.internal.enums.VendorBillStatus.PENDING_RECEIPT_MATCH) "
                        +
                        "ORDER BY vb.dueDate ASC")
        List<VendorBill> findUnpaidBillsForVendor(UUID vendorId);

        /**
         * Get total amount owed to a vendor (APPROVED or PENDING_REVIEW status).
         */
        @Query("SELECT COALESCE(SUM(vb.totalAmount), 0) FROM VendorBill vb " +
                        "WHERE vb.vendorId = :vendorId " +
                        "AND vb.status IN (com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED, com.positivity.accounting.internal.enums.VendorBillStatus.PENDING_RECEIPT_MATCH)")
        BigDecimal getTotalOwedToVendor(UUID vendorId);

        /**
         * Find bills due within a date range (excluding PAID status).
         */
        @Query("SELECT vb FROM VendorBill vb " +
                        "WHERE vb.dueDate >= :startDate AND vb.dueDate <= :endDate " +
                        "AND vb.status != com.positivity.accounting.internal.enums.VendorBillStatus.PAID " +
                        "ORDER BY vb.dueDate ASC")
        List<VendorBill> findBillsDueInRange(LocalDateTime startDate, LocalDateTime endDate);

        /**
         * Find bill by origin event ID (for idempotency checks in event-driven
         * workflow).
         *
         * @param originEventId UUID of the originating event (e.g., GoodsReceivedEvent)
         * @return Optional containing the bill if found
         */
        Optional<VendorBill> findByOriginEventId(UUID originEventId);

        /**
         * Get the next bill sequence number from PostgreSQL sequence.
         * Guarantees unique, monotonically increasing bill numbers across service
         * restarts
         * and multi-instance deployments.
         *
         * Note: Requires 'bill_number_seq' sequence to exist in the database:
         * CREATE SEQUENCE IF NOT EXISTS bill_number_seq
         * START WITH 1
         * INCREMENT BY 1
         * NO CYCLE;
         *
         * @return Next sequence value for bill number generation
         */
        @Query(value = "SELECT nextval('bill_number_seq')", nativeQuery = true)
        long getNextBillSequence();
}
