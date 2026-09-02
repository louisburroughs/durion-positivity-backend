package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The unmatched-line quarantine and its re-application worklist (ADR-0053 §5). */
public interface PriceCatalogUnmatchedLineRepository extends JpaRepository<PriceCatalogUnmatchedLineEntity, UUID> {

    /**
     * The shared filter of the quarantine worklist search (#1637 decision 6), factored out so the
     * page query and its count cannot drift apart. Every clause but the profile scope and the
     * resolved toggle is optional: a null parameter switches its predicate off rather than
     * matching nothing.
     *
     * <p>{@code resolved} is a mandatory boolean, not a nullable switch: the worklist's default
     * has always been the open quarantine ({@code resolvedAt IS NULL}), and the service maps an
     * omitted request parameter to {@code false} so that default survives the new filters.
     *
     * <p>{@code searchPattern} is a pre-lowercased, pre-escaped {@code LIKE} pattern (escape
     * character {@code !}) built by the service, matched against the three identifiers the vendor
     * line carried — EAN, the vendor's own article code, and the cross-reference code — the
     * references an operator working the backlog pastes in.
     *
     * <p>The window binds against {@code fetchedAt}, half-open ({@code from} inclusive, {@code to}
     * exclusive) so adjacent windows tile without listing a boundary line twice.
     */
    String SEARCH_WHERE = " WHERE l.vendorProfileId = :vendorProfileId"
            + " AND ((:resolved = TRUE AND l.resolvedAt IS NOT NULL) OR (:resolved = FALSE AND l.resolvedAt IS NULL))"
            + " AND (:reason IS NULL OR l.reason = :reason)"
            + " AND (:searchPattern IS NULL OR LOWER(l.articleEan) LIKE :searchPattern ESCAPE '!'"
            + " OR LOWER(l.supplierArticleCode) LIKE :searchPattern ESCAPE '!'"
            + " OR LOWER(l.xReferenceCode) LIKE :searchPattern ESCAPE '!')"
            + " AND (:fetchedFrom IS NULL OR l.fetchedAt >= :fetchedFrom)"
            + " AND (:fetchedTo IS NULL OR l.fetchedAt < :fetchedTo)";

    /**
     * The filterable quarantine worklist, newest first by {@code createdAt} with the UUIDv7 line
     * id as the deterministic tie-break for lines staged in the same instant.
     */
    @Query(
            value = "SELECT l FROM PriceCatalogUnmatchedLineEntity l" + SEARCH_WHERE
                    + " ORDER BY l.createdAt DESC, l.unmatchedLineId DESC",
            countQuery = "SELECT COUNT(l) FROM PriceCatalogUnmatchedLineEntity l" + SEARCH_WHERE)
    @NonNull
    Page<PriceCatalogUnmatchedLineEntity> search(
            @Param("vendorProfileId") @NonNull UUID vendorProfileId,
            @Param("resolved") boolean resolved,
            @Param("reason") @Nullable UnmatchedLineReason reason,
            @Param("searchPattern") @Nullable String searchPattern,
            @Param("fetchedFrom") @Nullable Instant fetchedFrom,
            @Param("fetchedTo") @Nullable Instant fetchedTo,
            @NonNull Pageable pageable);

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
