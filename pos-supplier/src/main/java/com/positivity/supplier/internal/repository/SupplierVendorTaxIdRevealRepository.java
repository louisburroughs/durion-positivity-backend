package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierVendorTaxIdRevealEntity;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** The append-only reveal audit of vendor tax-registration numbers (#2621). Nothing here updates or deletes. */
public interface SupplierVendorTaxIdRevealRepository extends JpaRepository<SupplierVendorTaxIdRevealEntity, UUID> {

    /** A vendor's reveals, newest first; the tenant is the bound one (RLS). */
    @NonNull
    Page<SupplierVendorTaxIdRevealEntity> findByVendorIdOrderByRevealedAtDescRevealIdDesc(
            @NonNull UUID vendorId, @NonNull Pageable pageable);
}
