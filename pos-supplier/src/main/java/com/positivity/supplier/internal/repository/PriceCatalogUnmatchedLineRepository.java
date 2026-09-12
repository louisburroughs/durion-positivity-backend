package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** The unmatched-line quarantine and its re-application worklist (ADR-0053 §5). */
public interface PriceCatalogUnmatchedLineRepository
        extends JpaRepository<PriceCatalogUnmatchedLineEntity, UUID>,
                JpaSpecificationExecutor<PriceCatalogUnmatchedLineEntity> {

    /**
     * The worklist's documented order: newest first by {@code createdAt}, with the UUIDv7 line id
     * as the deterministic tie-break for lines staged in the same instant. Imposed by the search
     * rather than taken from the caller, because it is part of the endpoint's contract.
     */
    Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("unmatchedLineId"));

    /**
     * The filterable quarantine worklist (#1637 decision 6). Every clause but the profile scope and
     * the resolved toggle is optional: a null argument switches its predicate off rather than
     * matching nothing.
     *
     * <p>The filter is a {@link PriceCatalogQuarantineSearch} specification rather than a JPQL
     * string of {@code (:param IS NULL OR …)} clauses: see {@link PriceCatalogImportSearch} for why
     * the string form returned 500 from PostgreSQL for every call while passing on H2. Spring Data
     * derives the count query from the same specification, so page and count cannot drift apart.
     *
     * @param vendorProfileId the profile whose quarantine is being worked
     * @param resolved true for closed lines, false for the open worklist
     * @param reason only lines quarantined for this reason, or null for every reason
     * @param searchPattern a pre-lowercased, pre-escaped {@code LIKE} pattern (escape character
     *     {@code !}) matched against EAN, the vendor's article code and the cross-reference code,
     *     or null
     * @param fetchedFrom inclusive lower bound on {@code fetchedAt}, or null
     * @param fetchedTo exclusive upper bound on {@code fetchedAt}, or null
     * @param pageable the page to return; its sort is replaced by {@link #NEWEST_FIRST}
     * @return one page of matching quarantine lines, newest first
     */
    @NonNull
    default Page<PriceCatalogUnmatchedLineEntity> search(
            @NonNull UUID vendorProfileId,
            boolean resolved,
            @Nullable UnmatchedLineReason reason,
            @Nullable String searchPattern,
            @Nullable Instant fetchedFrom,
            @Nullable Instant fetchedTo,
            @NonNull Pageable pageable) {
        return findAll(
                PriceCatalogQuarantineSearch.matching(
                        vendorProfileId, resolved, reason, searchPattern, fetchedFrom, fetchedTo),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST));
    }

    long countByImportManifestId(UUID importManifestId);

    long countByVendorProfileIdAndResolvedAtIsNull(UUID vendorProfileId);

    /**
     * Open quarantine rows that are worth retrying against the catalog: everything except lines
     * that carried no identifier at all, which no catalog fix can rescue.
     */
    List<PriceCatalogUnmatchedLineEntity> findByVendorProfileIdAndResolvedAtIsNullAndReasonIn(
            UUID vendorProfileId, List<UnmatchedLineReason> reasons, Pageable pageable);

    /** How many open rows are retryable, so a caller can size a re-application before running it. */
    long countByVendorProfileIdAndResolvedAtIsNullAndReasonIn(UUID vendorProfileId, List<UnmatchedLineReason> reasons);
}
