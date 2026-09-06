package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.TreadDesignCandidateDto;
import com.positivity.catalog.internal.dto.TreadDesignDto;
import com.positivity.catalog.internal.dto.TreadDesignResolveRequest;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read and review access to MKCAT tread-design enrichment (CAP-324 #1352, review added by #1645).
 *
 * <p>The content is still vendor-supplied and still never becomes catalog-owned product data. What
 * #1645 adds is not a write path into that content but a decision surface over the matching of it:
 * a person may say which product a design describes, or that none does. That is a catalog judgement
 * about a vendor's claim, not an edit of the claim.
 */
public interface TreadDesignService {

    /**
     * The enrichment matched to a product, when one has been resolved.
     *
     * @param productId the catalog product
     * @return the design's enrichment, or empty when the product matches no design
     */
    @NonNull
    Optional<TreadDesignDto> findForProduct(@NonNull UUID productId);

    /**
     * The review worklist: designs in any of {@code states}, optionally for one vendor profile,
     * most recently changed first.
     *
     * <p>Replaces #1352's "matched to nothing" list. An unmatched design and a design whose best
     * candidate fell short of the auto threshold both need a person, and only the second one has
     * anything to show them — so the list is keyed on the decision state rather than on whether an
     * attachment happens to exist.
     *
     * @param states states to include; never empty (the controller defaults it)
     * @param vendorProfileId narrow to one vendor profile, or null for all
     * @param pageable paging
     * @return a page of designs with their candidates
     */
    @NonNull
    Page<TreadDesignDto> findForReview(
            @NonNull Collection<TreadDesignMatchState> states,
            @Nullable UUID vendorProfileId,
            @NonNull Pageable pageable);

    /**
     * Everything the matcher scored against one design, best first.
     *
     * @param treadDesignId the design
     * @return its candidates; empty when matching found nothing worth recording
     * @throws com.positivity.catalog.internal.exception.CatalogNotFoundException when no such design
     *     exists — distinct from an empty candidate list, which is a real answer
     */
    @NonNull
    List<TreadDesignCandidateDto> findCandidates(@NonNull UUID treadDesignId);

    /**
     * Records a reviewer's ruling and applies it.
     *
     * @param treadDesignId the design being resolved
     * @param request the ruling
     * @param resolvedBy who ruled, as the gateway identified them
     * @return the design as it now stands
     * @throws com.positivity.catalog.internal.exception.CatalogNotFoundException unknown design or
     *     product
     * @throws com.positivity.catalog.internal.exception.CatalogValidationException an action and
     *     payload that cannot go together
     * @throws com.positivity.catalog.internal.exception.CatalogBusinessRuleException attaching a
     *     product another design already holds by a reviewer's decision
     */
    @NonNull
    TreadDesignDto resolve(
            @NonNull UUID treadDesignId, @NonNull TreadDesignResolveRequest request, @NonNull String resolvedBy);
}
