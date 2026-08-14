package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Import-manifest bookkeeping for PRICAT fetches (ADR-0053 §7). */
public interface PriceCatalogImportRepository extends JpaRepository<PriceCatalogImportEntity, UUID> {

    Optional<PriceCatalogImportEntity> findFirstByVendorProfileIdAndStatusOrderByFetchedAtDesc(
            UUID vendorProfileId, PriceCatalogImportStatus status);

    Page<PriceCatalogImportEntity> findByVendorProfileIdOrderByFetchedAtDesc(UUID vendorProfileId, Pageable pageable);

    List<PriceCatalogImportEntity> findByVendorProfileIdAndStatus(
            UUID vendorProfileId, PriceCatalogImportStatus status);

    boolean existsByVendorProfileIdAndStatus(UUID vendorProfileId, PriceCatalogImportStatus status);
}
