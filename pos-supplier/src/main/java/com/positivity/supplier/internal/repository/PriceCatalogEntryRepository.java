package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.PriceCatalogEntryEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Staged PRICAT lines: append-only received-document records (ADR-0053 §1/§2). */
public interface PriceCatalogEntryRepository extends JpaRepository<PriceCatalogEntryEntity, UUID> {

    Page<PriceCatalogEntryEntity> findByImportManifestIdOrderByPositionNumberAsc(
            UUID importManifestId, Pageable pageable);

    List<PriceCatalogEntryEntity> findByImportManifestIdOrderByPositionNumberAsc(UUID importManifestId);

    long countByImportManifestId(UUID importManifestId);

    /**
     * One published chunk of one import, in the order it was published (ADR-0044 §4 re-emission).
     *
     * <p>Read a chunk at a time rather than a whole import, so re-emitting a 50k-line catalogue
     * costs one chunk of memory rather than all of it. The entry id breaks position ties, because a
     * re-emit whose line order varied between runs would produce a payload the consumer's checksum
     * no longer matches.
     */
    List<PriceCatalogEntryEntity> findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(
            UUID importManifestId, int chunkSequence);

    /**
     * The applicable entry for one product in one market as at a given day (ADR-0053 §2):
     * greatest {@code effectiveFrom} not after the day, ties broken by source-document date and
     * then by fetch timestamp — the deterministic ordering the ADR fixes so two readers never
     * disagree about which vendor price is current.
     *
     * <p>Returned as a list so the caller can take the first element; the tie-break chain makes
     * the first row deterministic even when a vendor restates a price on the same date.
     */
    @Query("""
      SELECT e FROM PriceCatalogEntryEntity e
      WHERE e.vendorProfileId = :vendorProfileId
        AND e.matchedProductId = :productId
        AND e.countryCode = :countryCode
        AND e.currency = :currency
        AND e.effectiveFrom <= :asOf
      ORDER BY e.effectiveFrom DESC, e.sourceDocumentDate DESC NULLS LAST, e.fetchedAt DESC
      """)
    List<PriceCatalogEntryEntity> findApplicableEntries(
            @Param("vendorProfileId") UUID vendorProfileId,
            @Param("productId") UUID productId,
            @Param("countryCode") String countryCode,
            @Param("currency") String currency,
            @Param("asOf") LocalDate asOf,
            Pageable pageable);
}
