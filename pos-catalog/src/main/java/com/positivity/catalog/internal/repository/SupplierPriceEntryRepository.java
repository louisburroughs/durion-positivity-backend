package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.SupplierPriceEntryEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only supplier price entries (ADR-0053 §1–§2). Nothing here updates or deletes a row. */
public interface SupplierPriceEntryRepository extends JpaRepository<SupplierPriceEntryEntity, UUID> {

    /**
     * The applicable entries for one product in one market as at a day, most applicable first
     * (ADR-0053 §2): greatest {@code effectiveFrom} not after the day, ties broken by
     * source-document date and then by fetch timestamp.
     *
     * <p>Returned as a page so the caller can take the first row for "latest" and the whole result
     * for history. The tie-break chain is what makes the first row deterministic when a vendor
     * restates a price on the same date — without it, two readers could disagree about today's cost.
     */
    @Query("""
      SELECT e FROM SupplierPriceEntryEntity e
      WHERE e.productId = :productId
        AND (:vendorProfileId IS NULL OR e.vendorProfileId = :vendorProfileId)
        AND e.countryCode = :countryCode
        AND e.currency = :currency
        AND e.effectiveFrom <= :asOf
      ORDER BY e.effectiveFrom DESC, e.sourceDocumentDate DESC NULLS LAST, e.fetchedAt DESC
      """)
    List<SupplierPriceEntryEntity> findApplicable(
            @Param("productId") UUID productId,
            @Param("vendorProfileId") UUID vendorProfileId,
            @Param("countryCode") String countryCode,
            @Param("currency") String currency,
            @Param("asOf") LocalDate asOf,
            Pageable pageable);

    /** Full cost history for a product, newest effective date first; superseded rows included. */
    @Query("""
      SELECT e FROM SupplierPriceEntryEntity e
      WHERE e.productId = :productId
        AND (:vendorProfileId IS NULL OR e.vendorProfileId = :vendorProfileId)
      ORDER BY e.effectiveFrom DESC, e.sourceDocumentDate DESC NULLS LAST, e.fetchedAt DESC
      """)
    Page<SupplierPriceEntryEntity> findHistory(
            @Param("productId") UUID productId, @Param("vendorProfileId") UUID vendorProfileId, Pageable pageable);

    long countByImportManifestId(UUID importManifestId);
}
