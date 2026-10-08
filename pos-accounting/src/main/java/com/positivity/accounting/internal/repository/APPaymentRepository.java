package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
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

    /**
     * Find all payments for a vendor.
     */
    List<APPayment> findByVendorId(UUID vendorId);

    /**
     * Find payments by status.
     */
    List<APPayment> findByStatus(APPaymentStatus status);

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
