package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.StockSnapshotLineEntity;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Lines of a vendor stock snapshot (CAP-322). */
public interface StockSnapshotLineRepository extends JpaRepository<StockSnapshotLineEntity, UUID> {

    List<StockSnapshotLineEntity> findBySnapshotIdOrderByCreatedAtAsc(UUID snapshotId);

    long countBySnapshotId(UUID snapshotId);

    /**
     * The shared filter of the snapshot-line browse, factored out so the page query and its count
     * cannot drift apart. {@code searchPattern} is a pre-lowercased, pre-escaped {@code LIKE}
     * pattern (escape character {@code !}), or null to switch the text filter off; it matches the
     * three columns a human recognises an article by — EAN, the vendor's code, and the description.
     */
    String LINE_SEARCH_WHERE = " where l.snapshotId = :snapshotId"
            + " and (:searchPattern is null or lower(l.articleEan) like :searchPattern escape '!'"
            + " or lower(l.supplierArticleCode) like :searchPattern escape '!'"
            + " or lower(l.description) like :searchPattern escape '!')";

    /**
     * One page of a snapshot's lines (issue #1638 decision 5).
     *
     * <p>Ordered by the UUIDv7 line id, which is insertion order — the order the vendor's document
     * stated them — and, unlike {@code createdAt}, unique, so paging is deterministic even for a
     * batch persisted in one instant.
     */
    @Query(
            value = "select l from StockSnapshotLineEntity l" + LINE_SEARCH_WHERE + " order by l.lineId asc",
            countQuery = "select count(l) from StockSnapshotLineEntity l" + LINE_SEARCH_WHERE)
    @NonNull
    Page<StockSnapshotLineEntity> searchBySnapshotId(
            @Param("snapshotId") @NonNull UUID snapshotId,
            @Param("searchPattern") @Nullable String searchPattern,
            @NonNull Pageable pageable);
}
