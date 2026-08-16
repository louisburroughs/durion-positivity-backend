package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierInvoiceEntity;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoiceEntity, UUID> {

    /**
     * Lookup by the vendor's own identity for the document.
     *
     * <p>This is what makes an overlapping fetch window safe. Windows are allowed to overlap on
     * re-run so a failed fetch can simply be repeated (ADR-0052 §5), which means the same invoice
     * arrives again by design rather than by accident.
     */
    Optional<SupplierInvoiceEntity> findByVendorProfileIdAndVendorInvoiceNumberAndInvoiceDate(
            UUID vendorProfileId, String vendorInvoiceNumber, LocalDate invoiceDate);
}
