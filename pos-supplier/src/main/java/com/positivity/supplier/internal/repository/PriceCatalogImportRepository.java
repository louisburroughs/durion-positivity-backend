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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Import-manifest bookkeeping for PRICAT fetches (ADR-0053 §7). */
public interface PriceCatalogImportRepository
        extends JpaRepository<PriceCatalogImportEntity, UUID>, JpaSpecificationExecutor<PriceCatalogImportEntity> {

    Optional<PriceCatalogImportEntity> findFirstByVendorProfileIdAndStatusOrderByFetchedAtDesc(
            UUID vendorProfileId, PriceCatalogImportStatus status);

    Page<PriceCatalogImportEntity> findByVendorProfileIdOrderByFetchedAtDesc(UUID vendorProfileId, Pageable pageable);

    List<PriceCatalogImportEntity> findByVendorProfileIdAndStatus(
            UUID vendorProfileId, PriceCatalogImportStatus status);

    boolean existsByVendorProfileIdAndStatus(UUID vendorProfileId, PriceCatalogImportStatus status);

    /**
     * The listing's documented order: newest first by {@code fetchedAt}, with the UUIDv7 manifest
     * id as the deterministic tie-break for runs recorded in the same instant. Imposed by the
     * search rather than taken from the caller, because it is part of the endpoint's contract.
     */
    Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("fetchedAt"), Sort.Order.desc("importManifestId"));

    /**
     * The filterable import-run listing (#1637 decisions 4/6). Every clause but the profile scope
     * is optional: a null argument switches its predicate off rather than matching nothing.
     *
     * <p>The filter is a {@link PriceCatalogImportSearch} specification rather than a JPQL string
     * of {@code (:param IS NULL OR …)} clauses: see that class for why the string form returned 500
     * from PostgreSQL for every call while passing on H2. Spring Data derives the count query from
     * the same specification, so page and count cannot drift apart.
     *
     * @param vendorProfileId the profile whose history is being read
     * @param bindingId only runs of this feed, or null for every feed
     * @param status only runs in this state, or null for every state
     * @param fetchedFrom inclusive lower bound on {@code fetchedAt}, or null
     * @param fetchedTo exclusive upper bound on {@code fetchedAt}, or null
     * @param pageable the page to return, or {@code Pageable.unpaged()} for all matches; its sort is
     *     replaced by {@link #NEWEST_FIRST} either way
     * @return one page of matching import runs, newest first
     */
    @NonNull
    default Page<PriceCatalogImportEntity> search(
            @NonNull UUID vendorProfileId,
            @Nullable UUID bindingId,
            @Nullable PriceCatalogImportStatus status,
            @Nullable Instant fetchedFrom,
            @Nullable Instant fetchedTo,
            @NonNull Pageable pageable) {
        return findAll(
                PriceCatalogImportSearch.matching(vendorProfileId, bindingId, status, fetchedFrom, fetchedTo),
                SearchPaging.sortedBy(pageable, NEWEST_FIRST));
    }

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
