package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.TreadDesignEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TreadDesignRepository extends JpaRepository<TreadDesignEntity, UUID> {

    Optional<TreadDesignEntity> findByVendorProfileIdAndVendorVariantId(UUID vendorProfileId, String vendorVariantId);

    /**
     * Designs matched to zero products, newest first (CAP-324 #1352). An ordinary outcome, visible
     * for review rather than surfaced as an error — see {@code SupplierCatalogEnrichmentListener}.
     */
    @Query("""
      SELECT td FROM TreadDesignEntity td
      WHERE NOT EXISTS (SELECT 1 FROM ProductEntity p WHERE p.treadDesignId = td.id)
      ORDER BY td.updatedAt DESC
      """)
    Page<TreadDesignEntity> findUnmatched(Pageable pageable);
}
