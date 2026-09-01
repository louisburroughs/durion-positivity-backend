package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * Find payments scheduled for a specific date.
     */
    List<APPayment> findByPaymentDate(LocalDateTime paymentDate);

    /**
     * Find payments scheduled between dates.
     */
    @Query("SELECT p FROM APPayment p WHERE p.paymentDate BETWEEN :startDate AND :endDate")
    List<APPayment> findPaymentsByDateRange(
            @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

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
     * @param startDate inclusive lower bound
     * @param endDate   inclusive upper bound
     * @return payments in one of the given statuses within the range (unordered)
     */
    List<APPayment> findByStatusInAndPaymentDateBetween(
            Collection<APPaymentStatus> statuses, LocalDateTime startDate, LocalDateTime endDate);
}
