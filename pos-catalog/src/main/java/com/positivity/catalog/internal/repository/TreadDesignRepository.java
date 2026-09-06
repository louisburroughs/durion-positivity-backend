package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.TreadDesignEntity;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TreadDesignRepository extends JpaRepository<TreadDesignEntity, UUID> {

    Optional<TreadDesignEntity> findByVendorProfileIdAndVendorVariantId(UUID vendorProfileId, String vendorVariantId);

    /**
     * The review worklist (#1645): designs in any of {@code states}, optionally narrowed to one
     * vendor profile, most recently changed first.
     *
     * <p>This replaces #1352's "matched to zero products" query, and the difference is the point of
     * the story. Attachment count answered "did anything stick?"; {@code match_state} answers "does
     * this need a person?", which is a different question the moment a design can be plausible but
     * unattached (REVIEW), settled by hand (MATCHED/REJECTED) or postponed (DEFERRED). Ordering on
     * {@code matchStateAt} rather than {@code updatedAt} follows: a worklist ages on when its
     * decision last moved, not on when the vendor last republished the same words.
     */
    @Query("""
      SELECT td FROM TreadDesignEntity td
      WHERE td.matchState IN :states
        AND (:vendorProfileId IS NULL OR td.vendorProfileId = :vendorProfileId)
      ORDER BY td.matchStateAt DESC, td.id DESC
      """)
    Page<TreadDesignEntity> findForReview(
            @Param("states") Collection<TreadDesignMatchState> states,
            @Param("vendorProfileId") UUID vendorProfileId,
            Pageable pageable);
}
