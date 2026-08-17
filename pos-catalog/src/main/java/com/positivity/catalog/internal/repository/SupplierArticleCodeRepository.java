package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.SupplierArticleCodeEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierArticleCodeRepository extends JpaRepository<SupplierArticleCodeEntity, UUID> {

    Optional<SupplierArticleCodeEntity> findByVendorProfileIdAndProductId(UUID vendorProfileId, UUID productId);

    /**
     * A page of current-state rows for fact replay (CAP-320 #1347, following #1309's pattern),
     * ordered by id so a cursor can resume where the previous page stopped.
     *
     * <p>Cursor rather than offset paging, for the same reason as {@code ProductRepository}'s
     * equivalent: offsets shift under concurrent writes, and a row upserted mid-replay would
     * silently displace another out of the window.
     */
    @Query("""
      SELECT e FROM SupplierArticleCodeEntity e
      WHERE (:afterId IS NULL OR e.id > :afterId)
        AND (:updatedSince IS NULL OR e.updatedAt >= :updatedSince)
      ORDER BY e.id ASC
      """)
    List<SupplierArticleCodeEntity> findForReplay(
            @Param("afterId") UUID afterId, @Param("updatedSince") Instant updatedSince, Pageable pageable);
}
