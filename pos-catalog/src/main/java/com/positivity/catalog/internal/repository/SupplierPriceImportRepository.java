package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.SupplierPriceImportEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Consumer-side PRICAT import bookkeeping and completeness state (ADR-0053 §7). */
public interface SupplierPriceImportRepository extends JpaRepository<SupplierPriceImportEntity, UUID> {

    List<SupplierPriceImportEntity> findByVendorProfileIdOrderByUpdatedAtDesc(UUID vendorProfileId);

    List<SupplierPriceImportEntity> findByStatus(String status);
}
