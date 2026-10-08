package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.SupplierInvoiceHold;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** EDI invoice facts held until they can become bills (CAP:550 S24, #2517). */
public interface SupplierInvoiceHoldRepository extends JpaRepository<SupplierInvoiceHold, UUID> {

    /** A vendor's open holds of one reason, oldest first: what a release turns into bills, in arrival order. */
    @NonNull
    List<SupplierInvoiceHold> findByVendorIdAndReasonAndReleasedAtIsNullOrderByReceivedAtAscHoldIdAsc(
            @NonNull UUID vendorId, SupplierInvoiceHold.@NonNull Reason reason);

    /** Open holds of one reason: the {@code accounting.supplier_invoice.held} gauge. */
    long countByReasonAndReleasedAtIsNull(SupplierInvoiceHold.@NonNull Reason reason);

    /** Open holds received before {@code cutoff}: the daily WARN of holds older than 24 hours. */
    @NonNull
    List<SupplierInvoiceHold> findByReleasedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(@NonNull Instant cutoff);

    /** Open {@code VENDOR_NOT_IN_COPY} holds whose vendor is now copied: what the sweep retries releasing. */
    @Query("select distinct h.vendorId from SupplierInvoiceHold h, ExtSupplierVendor v"
            + " where v.vendorId = h.vendorId and h.releasedAt is null and h.reason = :reason")
    @NonNull
    List<UUID> findCopiedVendorIdsWithOpenHolds(@Param("reason") SupplierInvoiceHold.@NonNull Reason reason);
}
