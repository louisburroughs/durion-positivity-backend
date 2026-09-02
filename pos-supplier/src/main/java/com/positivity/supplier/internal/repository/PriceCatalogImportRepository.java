package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Import-manifest bookkeeping for PRICAT fetches (ADR-0053 §7). */
public interface PriceCatalogImportRepository extends JpaRepository<PriceCatalogImportEntity, UUID> {

    Optional<PriceCatalogImportEntity> findFirstByVendorProfileIdAndStatusOrderByFetchedAtDesc(
            UUID vendorProfileId, PriceCatalogImportStatus status);

    Page<PriceCatalogImportEntity> findByVendorProfileIdOrderByFetchedAtDesc(UUID vendorProfileId, Pageable pageable);

    List<PriceCatalogImportEntity> findByVendorProfileIdAndStatus(
            UUID vendorProfileId, PriceCatalogImportStatus status);

    boolean existsByVendorProfileIdAndStatus(UUID vendorProfileId, PriceCatalogImportStatus status);

    /**
     * The shared filter of the import-run search (#1637 decisions 4/6), factored out so the page
     * query and its count cannot drift apart. Every clause but the profile scope is optional: a
     * null parameter switches its predicate off rather than matching nothing.
     *
     * <p>The window binds against {@code fetchedAt} — when the vendor was called, the axis an
     * operator reads a feed's history by — and is half-open ({@code from} inclusive, {@code to}
     * exclusive) so adjacent windows tile without listing a boundary run twice. {@code bindingId}
     * narrows a profile's history to one feed; pre-V19 rows carry a null binding and therefore
     * never match a binding filter, which is the documented forward-only semantics.
     */
    String SEARCH_WHERE = " WHERE i.vendorProfileId = :vendorProfileId"
            + " AND (:bindingId IS NULL OR i.bindingId = :bindingId)"
            + " AND (:status IS NULL OR i.status = :status)"
            + " AND (:fetchedFrom IS NULL OR i.fetchedAt >= :fetchedFrom)"
            + " AND (:fetchedTo IS NULL OR i.fetchedAt < :fetchedTo)";

    /**
     * The filterable import-run listing, newest first by {@code fetchedAt} with the UUIDv7 manifest
     * id as the deterministic tie-break for runs recorded in the same instant.
     */
    @Query(
            value = "SELECT i FROM PriceCatalogImportEntity i" + SEARCH_WHERE
                    + " ORDER BY i.fetchedAt DESC, i.importManifestId DESC",
            countQuery = "SELECT COUNT(i) FROM PriceCatalogImportEntity i" + SEARCH_WHERE)
    @NonNull
    Page<PriceCatalogImportEntity> search(
            @Param("vendorProfileId") @NonNull UUID vendorProfileId,
            @Param("bindingId") @Nullable UUID bindingId,
            @Param("status") @Nullable PriceCatalogImportStatus status,
            @Param("fetchedFrom") @Nullable Instant fetchedFrom,
            @Param("fetchedTo") @Nullable Instant fetchedTo,
            @NonNull Pageable pageable);

    /**
     * The vendor's own latest catalog document date over completed imports (#1637 decision 3):
     * vendor document metadata, deliberately distinct from {@link #findLastFetchedAt platform
     * retrieval time}. Null when the profile has no completed import that stated a date.
     */
    @Query("SELECT MAX(i.sourceDocumentDate) FROM PriceCatalogImportEntity i"
            + " WHERE i.vendorProfileId = :vendorProfileId AND i.status = :status")
    @Nullable
    LocalDate findLatestSourceDocumentDate(
            @Param("vendorProfileId") @NonNull UUID vendorProfileId,
            @Param("status") @NonNull PriceCatalogImportStatus status);

    /**
     * When this platform last called the vendor, over every run including failed and empty ones —
     * a failed run is still an answer to "did anyone try recently". Null when never fetched.
     */
    @Query("SELECT MAX(i.fetchedAt) FROM PriceCatalogImportEntity i WHERE i.vendorProfileId = :vendorProfileId")
    @Nullable
    Instant findLastFetchedAt(@Param("vendorProfileId") @NonNull UUID vendorProfileId);

    /** When staging last committed for the profile; null when no run ever completed. */
    @Query("SELECT MAX(i.completedAt) FROM PriceCatalogImportEntity i WHERE i.vendorProfileId = :vendorProfileId")
    @Nullable
    Instant findLastCompletedAt(@Param("vendorProfileId") @NonNull UUID vendorProfileId);
}
