package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
         * Find bills by status with pagination.
         */
        Page<VendorBill> findByStatus(VendorBillStatus status, Pageable pageable);

        /**
         * Find bills by status with positive open amount and pagination.
         *
         * Open amount is computed as totalAmount minus the sum of all applied
         * allocations.
         */
        @Query(value = """
                        SELECT vb
                        FROM VendorBill vb
                        WHERE vb.status = :status
                                AND (
                                                vb.totalAmount - COALESCE(
                                                                (SELECT SUM(a.appliedAmount)
                                                                 FROM APPaymentAllocation a
                                                                 WHERE a.vendorBill.vendorBillId = vb.vendorBillId),
                                                                0
                                                )
                                ) > :openAmountThreshold
                        ORDER BY vb.dueDate ASC, vb.billDate ASC, vb.vendorBillId ASC
                        """, countQuery = """
                        SELECT COUNT(vb)
                        FROM VendorBill vb
                        WHERE vb.status = :status
                                AND (
                                                vb.totalAmount - COALESCE(
                                                                (SELECT SUM(a.appliedAmount)
                                                                 FROM APPaymentAllocation a
                                                                 WHERE a.vendorBill.vendorBillId = vb.vendorBillId),
                                                                0
                                                )
                                ) > :openAmountThreshold
                        """)
        Page<VendorBill> findByStatusAndOpenAmountGreaterThan(
                        @Param("status") VendorBillStatus status,
                        @Param("openAmountThreshold") BigDecimal openAmountThreshold,
                        Pageable pageable);

        /**
         * Find bills for a vendor with a specific status.
         */
        List<VendorBill> findByVendorIdAndStatus(UUID vendorId, VendorBillStatus status);

        /**
         * Find bills for a vendor with a specific status and pagination.
         */
        Page<VendorBill> findByVendorIdAndStatus(UUID vendorId, VendorBillStatus status, Pageable pageable);

        /**
         * Find bills for a vendor with a specific status and positive open amount,
         * with pagination.
         */
        @Query(value = """
                        SELECT vb
                        FROM VendorBill vb
                        WHERE vb.vendorId = :vendorId
                                AND vb.status = :status
                                AND (
                                                vb.totalAmount - COALESCE(
                                                                (SELECT SUM(a.appliedAmount)
                                                                 FROM APPaymentAllocation a
                                                                 WHERE a.vendorBill.vendorBillId = vb.vendorBillId),
                                                                0
                                                )
                                ) > :openAmountThreshold
                        ORDER BY vb.dueDate ASC, vb.billDate ASC, vb.vendorBillId ASC
                        """, countQuery = """
                        SELECT COUNT(vb)
                        FROM VendorBill vb
                        WHERE vb.vendorId = :vendorId
                                AND vb.status = :status
                                AND (
                                                vb.totalAmount - COALESCE(
                                                                (SELECT SUM(a.appliedAmount)
                                                                 FROM APPaymentAllocation a
                                                                 WHERE a.vendorBill.vendorBillId = vb.vendorBillId),
                                                                0
                                                )
                                ) > :openAmountThreshold
                        """)
        Page<VendorBill> findByVendorIdAndStatusAndOpenAmountGreaterThan(
                        @Param("vendorId") UUID vendorId,
                        @Param("status") VendorBillStatus status,
                        @Param("openAmountThreshold") BigDecimal openAmountThreshold,
                        Pageable pageable);

        /**
         * Find bills received within a date range (based on billDate).
         */
        @Query("SELECT vb FROM VendorBill vb " + "WHERE vb.billDate >= :startDate AND vb.billDate < :endDate "
                        + "ORDER BY vb.billDate DESC")
        List<VendorBill> findByBillDateRange(LocalDateTime startDate, LocalDateTime endDate);

        /**
         * Find a bill by vendor and bill number.
         */
        Optional<VendorBill> findByVendorIdAndBillNumber(UUID vendorId, String billNumber);

        /**
         * Find unpaid bills (status = APPROVED or PENDING_REVIEW) for a vendor.
         */
        @Query("SELECT vb FROM VendorBill vb " + "WHERE vb.vendorId = :vendorId "
                        + "AND vb.status IN (com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED, com.positivity.accounting.internal.enums.VendorBillStatus.PENDING_RECEIPT_MATCH) "
                        + "ORDER BY vb.dueDate ASC")
        List<VendorBill> findUnpaidBillsForVendor(UUID vendorId);

        /**
         * Get total amount owed to a vendor (APPROVED or PENDING_REVIEW status).
         */
        @Query("SELECT COALESCE(SUM(vb.totalAmount), 0) FROM VendorBill vb " + "WHERE vb.vendorId = :vendorId "
                        + "AND vb.status IN (com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED, com.positivity.accounting.internal.enums.VendorBillStatus.PENDING_RECEIPT_MATCH)")
        BigDecimal getTotalOwedToVendor(UUID vendorId);

        /**
         * Find bills due within a date range (excluding PAID status).
         */
        @Query("SELECT vb FROM VendorBill vb " + "WHERE vb.dueDate >= :startDate AND vb.dueDate <= :endDate "
                        + "AND vb.status != com.positivity.accounting.internal.enums.VendorBillStatus.PAID "
                        + "ORDER BY vb.dueDate ASC")
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
