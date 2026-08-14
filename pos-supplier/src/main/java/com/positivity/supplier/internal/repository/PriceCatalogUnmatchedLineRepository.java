package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** The unmatched-line quarantine and its re-application worklist (ADR-0053 §5). */
public interface PriceCatalogUnmatchedLineRepository extends JpaRepository<PriceCatalogUnmatchedLineEntity, UUID> {

    Page<PriceCatalogUnmatchedLineEntity> findByVendorProfileIdAndResolvedAtIsNullOrderByCreatedAtDesc(
            UUID vendorProfileId, Pageable pageable);

    long countByImportManifestId(UUID importManifestId);

    long countByVendorProfileIdAndResolvedAtIsNull(UUID vendorProfileId);

    /**
     * Open quarantine rows that are worth retrying against the catalog: everything except lines
     * that carried no identifier at all, which no catalog fix can rescue.
     */
    List<PriceCatalogUnmatchedLineEntity> findByVendorProfileIdAndResolvedAtIsNullAndReasonIn(
            UUID vendorProfileId, List<UnmatchedLineReason> reasons, Pageable pageable);
}
