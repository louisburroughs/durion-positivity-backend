package com.positivity.supplier.internal.mktcat.service;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.mktcat.service.model.MarketingEnrichmentView;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading what a marketing catalogue last sent (CAP-324 #1230).
 *
 * <h2>Staged, never presented as catalogue content</h2>
 *
 * Every row here is what a manufacturer published, not what any product says. A variant may match no
 * product at all — that is an ordinary outcome for a design-level record in a catalogue of articles —
 * and the view is deliberately shaped so nobody can mistake it for product data.
 */
@Service
@RequiredArgsConstructor
public class MktCatStagedReader {

    private final SupplierProfileResolver profileResolver;
    private final SupplierMktCatVariantRepository variantRepository;

    /**
     * The variants this catalogue last sent, most recently seen first.
     *
     * <p>Resolves the alias first, so an unknown {@code supplierRef} surfaces as the configuration
     * problem it is rather than as an empty catalogue — those two answers send an operator in
     * opposite directions.
     */
    @NonNull
    @Transactional(readOnly = true)
    public List<MarketingEnrichmentView> findStaged(@NonNull SupplierRef supplierRef, int limit) {
        var binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.MARKETING_CATALOG);
        return variantRepository
                .findByVendorProfileIdOrderByLastSeenAtDesc(binding.profile().getVendorProfileId(), Limit.of(limit))
                .stream()
                .map(MktCatStagedReader::toView)
                .toList();
    }

    @NonNull
    private static MarketingEnrichmentView toView(@NonNull SupplierMktCatVariantEntity row) {
        return new MarketingEnrichmentView(
                row.getVendorVariantId(),
                row.getSupplierRef(),
                row.getBrand(),
                row.getTreadDesign(),
                row.getProductName(),
                row.getSeasonality(),
                row.isHasUnresolvedImages(),
                row.getFirstSeenAt(),
                row.getLastSeenAt(),
                row.getLastPublishedAt());
    }
}
