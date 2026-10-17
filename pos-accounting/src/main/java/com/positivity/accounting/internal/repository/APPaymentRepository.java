package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for AP Payment entities.
 *
 * @see APPayment
 */
public interface APPaymentRepository extends JpaRepository<APPayment, UUID> {

    /**
     * Find all payments for a vendor bill.
     */
    List<APPayment> findByVendorBill_VendorBillId(UUID vendorBillId);

    /** One vendor's unapplied AP payments in one currency (#2515): the aged-payables netting. */
    interface VendorUnappliedSum {
        UUID getVendorId();

        String getVendorName();

        BigDecimal getUnapplied();
    }

    /**
     * Unapplied AP payment totals per vendor: payments in {@code currency} whose status is not in {@code excluded} and
     * whose unapplied amount is above zero.
     */
    @Query("SELECT p.vendorId AS vendorId, MAX(p.vendorName) AS vendorName, SUM(p.unappliedAmount) AS unapplied"
            + " FROM APPayment p WHERE p.unappliedAmount > 0 AND p.currency = :currency"
            + " AND p.status NOT IN :excluded GROUP BY p.vendorId")
    @NonNull
    List<VendorUnappliedSum> sumUnappliedByVendor(
            @Param("currency") @NonNull String currency,
            @Param("excluded") @NonNull Collection<APPaymentStatus> excluded);

    /**
     * Find all payments for a vendor.
     */
    List<APPayment> findByVendorId(UUID vendorId);

    /**
     * Find payments by status.
     */
    List<APPayment> findByStatus(APPaymentStatus status);

    /** Payments naming a bank account in any of the given states (#2645: the ones not yet in the ledger). */
    long countByBankAccountIdAndStatusIn(@NonNull UUID bankAccountId, @NonNull Collection<APPaymentStatus> statuses);

    /**
     * The payment, row-locked to the end of the transaction (CAP:550 S42, #2603): its outbox posting and a
     * {@code gl-posting-retry} serialize here, so a payment is posted once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM APPayment p WHERE p.paymentId = :paymentId")
    @NonNull
    Optional<APPayment> lockById(@Param("paymentId") @NonNull UUID paymentId);

    /**
     * Find payment by payment reference (idempotency key).
     */
    Optional<APPayment> findByPaymentRef(String paymentRef);

    /** GATEWAY_PENDING orphans created before {@code before} (#2642: the settlement job's grace has passed). */
    @Query("SELECT p.paymentId FROM APPayment p WHERE p.status = com.positivity.accounting.internal.enums"
            + ".APPaymentStatus.GATEWAY_PENDING AND p.createdAt < :before ORDER BY p.createdAt ASC")
    @NonNull
    List<UUID> findOrphanIds(@Param("before") @NonNull Instant before, @NonNull Pageable pageable);

    /** Payments in flight at the gateway, least recently checked first (#2642: the settlement job). */
    @Query("SELECT p.paymentId FROM APPayment p WHERE p.settlementStatus = com.positivity.accounting.internal.enums"
            + ".APPaymentSettlementStatus.IN_FLIGHT AND p.status IN :statuses"
            + " ORDER BY p.settlementCheckedAt ASC NULLS FIRST, p.paymentDate ASC")
    @NonNull
    List<UUID> findInFlightIds(
            @Param("statuses") @NonNull Collection<APPaymentStatus> statuses, @NonNull Pageable pageable);

    /**
     * Returns held for a period remedy (#2642): RETURN_PENDING with the original posted and the mirror still owed; the
     * settlement sweep retries them each pass.
     */
    @Query("SELECT p.paymentId FROM APPayment p WHERE p.status = com.positivity.accounting.internal.enums"
            + ".APPaymentStatus.RETURN_PENDING AND p.glJournalEntry IS NOT NULL AND p.returnJournalEntryId IS NULL"
            + " ORDER BY p.returnedAt ASC")
    @NonNull
    List<UUID> findHeldReturnIds(@NonNull Pageable pageable);

    /** The in-flight read (#2642 ruling 4): oldest payment date first. */
    @Query("SELECT p FROM APPayment p WHERE p.settlementStatus = com.positivity.accounting.internal.enums"
            + ".APPaymentSettlementStatus.IN_FLIGHT ORDER BY p.paymentDate ASC, p.createdAt ASC")
    @NonNull
    List<APPayment> findInFlight();

    /** The payment whose own entry is {@code journalEntryId} (#2642: the reversal reaction). */
    Optional<APPayment> findByGlJournalEntry_JournalEntryId(UUID journalEntryId);

    /** The payment whose return's mirror entry is {@code journalEntryId} (#2642). */
    Optional<APPayment> findByReturnJournalEntryId(UUID journalEntryId);

    /**
     * Find payments in one of the given statuses whose payment date falls in the inclusive
     * range. Used by the vendor-spend analytics endpoint (Wave 2 E8, issue #1596) to sum settled
     * A/P cash by payment date, independent of vendor bill date.
     *
     * @param statuses  statuses to include (typically the "cash already moved" statuses)
     * @param startDate inclusive lower bound (the payment's business date, S42)
     * @param endDate   inclusive upper bound
     * @return payments in one of the given statuses within the range (unordered)
     */
    List<APPayment> findByStatusInAndPaymentDateBetween(
            Collection<APPaymentStatus> statuses, LocalDate startDate, LocalDate endDate);
}
