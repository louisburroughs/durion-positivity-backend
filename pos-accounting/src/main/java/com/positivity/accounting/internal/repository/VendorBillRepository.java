package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for Vendor Bill entity.
 * Supports querying bills by status, date range, and payment tracking.
 */
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
     * Find bills whose status is one of the given values. Used by the Aged
     * Payables report (story G2, issue #960) to load open-payable bills
     * (unpaid/unsettled statuses); the open balance per bill is then derived
     * in-service as {@code totalAmount} minus applied allocations.
     *
     * @param statuses statuses to include
     * @return matching bills (unordered; the report orders by vendor)
     */
    List<VendorBill> findByStatusIn(java.util.Collection<VendorBillStatus> statuses);

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
        ORDER BY CASE WHEN vb.dueDate IS NULL THEN 1 ELSE 0 END ASC,
                 vb.dueDate ASC,
                 vb.billDate ASC,
                 vb.vendorBillId ASC
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
        ORDER BY CASE WHEN vb.dueDate IS NULL THEN 1 ELSE 0 END ASC,
                 vb.dueDate ASC,
                 vb.billDate ASC,
                 vb.vendorBillId ASC
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
     * Find bills whose billDate falls in the inclusive range, regardless of status. Used by the
     * vendor-spend analytics endpoint (Wave 2 E8, issue #1596) for the bill-side
     * (billsIssuedInWindow / avgIssuedBillAmount) population, which is deliberately independent
     * of the payment-side (paidAmount) population — see {@code VendorSpendRow} Javadoc.
     *
     * @param startDate inclusive lower bound
     * @param endDate   inclusive upper bound
     * @return bills billed within the range (unordered)
     */
    List<VendorBill> findByBillDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Find bills whose dueDate falls in the inclusive range and whose status matches, with
     * pagination. Used by the vendor-bill due-date-window listing (Wave 2 E9, issue #1597).
     *
     * @param dueFrom  inclusive lower bound
     * @param dueTo    inclusive upper bound
     * @param status   required status
     * @param pageable pagination and sort (this endpoint enforces its own sort server-side)
     * @return page of matching bills
     */
    Page<VendorBill> findByDueDateBetweenAndStatus(
            LocalDateTime dueFrom, LocalDateTime dueTo, VendorBillStatus status, Pageable pageable);

    /**
     * Find bills whose dueDate falls in the inclusive range, any status, with pagination. Used
     * by the vendor-bill due-date-window listing (Wave 2 E9, issue #1597) when no status filter
     * is supplied.
     *
     * @param dueFrom  inclusive lower bound
     * @param dueTo    inclusive upper bound
     * @param pageable pagination and sort (this endpoint enforces its own sort server-side)
     * @return page of matching bills
     */
    Page<VendorBill> findByDueDateBetween(LocalDateTime dueFrom, LocalDateTime dueTo, Pageable pageable);

    /**
     * The live bill a new bill number would duplicate (#2501, BR-2; ADR-0070 Decision 4): same vendor,
     * same normalised number, bill date on the same calendar day, and not {@code VOIDED} or
     * {@code REJECTED}. The tenant is the bound one. The partial unique index
     * {@code uq_vendor_bill_duplicate_rule} allows at most one such row, so the result is an
     * {@link Optional}.
     *
     * @param vendorId      the vendor id as stored on the bill
     * @param billNumberKey {@link com.positivity.accounting.internal.entity.VendorBillNumbers#normalise}
     *                      of the bill number
     * @param dayStart      start of the bill date's calendar day, inclusive
     * @param nextDayStart  start of the following day, exclusive
     * @param excludeBillId a bill that is not its own duplicate (the bill being renamed), or null
     * @return the live original, if there is one
     */
    @Query("""
        SELECT vb
        FROM VendorBill vb
        WHERE vb.vendorId = :vendorId
          AND vb.billNumberKey = :billNumberKey
          AND vb.billDate >= :dayStart
          AND vb.billDate < :nextDayStart
          AND vb.status NOT IN (
                com.positivity.accounting.internal.enums.VendorBillStatus.VOIDED,
                com.positivity.accounting.internal.enums.VendorBillStatus.REJECTED)
          AND (:excludeBillId IS NULL OR vb.vendorBillId <> :excludeBillId)
        """)
    Optional<VendorBill> findLiveDuplicate(
            @Param("vendorId") UUID vendorId,
            @Param("billNumberKey") String billNumberKey,
            @Param("dayStart") LocalDateTime dayStart,
            @Param("nextDayStart") LocalDateTime nextDayStart,
            @Param("excludeBillId") @Nullable UUID excludeBillId);

    /**
     * Find unpaid bills (status = APPROVED or PENDING_REVIEW) for a vendor.
     */
    @Query("SELECT vb FROM VendorBill vb WHERE vb.vendorId = :vendorId AND vb.status IN"
            + " (com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED,"
            + " com.positivity.accounting.internal.enums.VendorBillStatus.PENDING_RECEIPT_MATCH) ORDER BY"
            + " vb.dueDate ASC")
    List<VendorBill> findUnpaidBillsForVendor(UUID vendorId);

    /**
     * Get total amount owed to a vendor (APPROVED or PENDING_REVIEW status).
     */
    @Query("SELECT COALESCE(SUM(vb.totalAmount), 0) FROM VendorBill vb WHERE vb.vendorId = :vendorId AND vb.status IN"
            + " (com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED,"
            + " com.positivity.accounting.internal.enums.VendorBillStatus.PENDING_RECEIPT_MATCH)")
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
     * The bill under a row lock for the length of the transaction (#2509; the {@code ReconciliationSupport.lock}
     * precedent): every transition takes it, so of two concurrent decisions one wins and the other, re-reading the
     * status, is refused.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT vb FROM VendorBill vb WHERE vb.vendorBillId = :billId")
    Optional<VendorBill> lockById(@Param("billId") UUID billId);

    /**
     * The bills of {@code billIds} under row locks taken in id order (#2509 review, A3): a payment allocating to
     * several bills locks them all before it reads their status, in the one order every such payment uses.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT vb FROM VendorBill vb WHERE vb.vendorBillId IN :billIds ORDER BY vb.vendorBillId")
    List<VendorBill> lockByVendorBillIdIn(@Param("billIds") Collection<UUID> billIds);

    /**
     * A vendor's bills in {@code status} under row locks taken in id order (#2509 review, A3). The status is
     * evaluated on the locked row, so a bill voided meanwhile is not returned.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT vb FROM VendorBill vb
        WHERE vb.vendorId = :vendorId AND vb.status = :status
        ORDER BY vb.vendorBillId
        """)
    List<VendorBill> lockByVendorIdAndStatus(
            @Param("vendorId") UUID vendorId, @Param("status") VendorBillStatus status);

    /**
     * A vendor's bills of one origin in any of {@code statuses}, oldest first (AW44: the goods-receipt bills still open
     * when its EDI bill is read).
     */
    List<VendorBill> findByVendorIdAndOriginEventTypeAndStatusInOrderByCreatedAtAscVendorBillIdAsc(
            UUID vendorId, String originEventType, Collection<VendorBillStatus> statuses);

    // ===== Stage reads (#2509; SPEC-accounting-workspace §5.2): no due-date window =====

    /** Bills in any of {@code statuses}, paged; the caller sets the order. */
    Page<VendorBill> findByStatusIn(Collection<VendorBillStatus> statuses, Pageable pageable);

    long countByStatusIn(Collection<VendorBillStatus> statuses);

    /** {@code PAY}: approved bills with an open amount above zero. */
    @Query("""
        SELECT COUNT(vb)
        FROM VendorBill vb
        WHERE vb.status = com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED
          AND vb.totalAmount - COALESCE(
                (SELECT SUM(a.appliedAmount) FROM APPaymentAllocation a
                 WHERE a.vendorBill.vendorBillId = vb.vendorBillId), 0) > 0
        """)
    long countApprovedWithOpenAmount();

    /**
     * {@code DONE}: approved bills paid in full whose last allocation's payment is dated in {@code [from, to)}. The
     * set is one month's paid bills; the service orders it newest paid first.
     */
    @Query("""
        SELECT vb
        FROM VendorBill vb
        WHERE vb.status = com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED
          AND vb.totalAmount - COALESCE(
                (SELECT SUM(a.appliedAmount) FROM APPaymentAllocation a
                 WHERE a.vendorBill.vendorBillId = vb.vendorBillId), 0) <= 0
          AND (SELECT MAX(p.payment.paymentDate) FROM APPaymentAllocation p
               WHERE p.vendorBill.vendorBillId = vb.vendorBillId) >= :from
          AND (SELECT MAX(p.payment.paymentDate) FROM APPaymentAllocation p
               WHERE p.vendorBill.vendorBillId = vb.vendorBillId) < :to
        """)
    List<VendorBill> findApprovedPaidInFullWithLastPaymentBetween(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
