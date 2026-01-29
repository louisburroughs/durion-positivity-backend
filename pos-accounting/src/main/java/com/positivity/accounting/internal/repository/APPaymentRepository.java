package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for AP Payment entities.
 * 
 * @see APPayment
 */
@Repository
public interface APPaymentRepository extends JpaRepository<APPayment, String> {

    /**
     * Find all payments for a vendor bill.
     */
    List<APPayment> findByVendorBillId(String vendorBillId);

    /**
     * Find all payments for a vendor.
     */
    List<APPayment> findByVendorId(String vendorId);

    /**
     * Find payments by status.
     */
    List<APPayment> findByStatus(APPaymentStatus status);

    /**
     * Find payments scheduled for a specific date.
     */
    List<APPayment> findByPaymentDate(LocalDate paymentDate);

    /**
     * Find payments scheduled between dates.
     */
    @Query("SELECT p FROM APPayment p WHERE p.paymentDate BETWEEN :startDate AND :endDate")
    List<APPayment> findPaymentsByDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Find pending approvals requiring high-threshold authorization.
     */
    @Query("SELECT p FROM APPayment p WHERE p.status = :status AND p.approvalLevel = :approvalLevel")
    List<APPayment> findPendingByApprovalLevel(
            @Param("status") APPaymentStatus status,
            @Param("approvalLevel") String approvalLevel);
}
