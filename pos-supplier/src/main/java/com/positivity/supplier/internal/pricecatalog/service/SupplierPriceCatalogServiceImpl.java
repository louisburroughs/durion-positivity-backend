package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.service.SupplierPriceCatalogService;
import com.positivity.supplier.service.model.PagedResponse;
import com.positivity.supplier.service.model.PriceCatalogImportSummary;
import com.positivity.supplier.service.model.UnmatchedPriceCatalogLineView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Import bookkeeping and the on-demand trigger for vendor price catalogs (ADR-0053 §7).
 *
 * <p>Reads are thin projections over the import and quarantine tables. The trigger delegates to
 * {@link PriceCatalogImporter}, which is the same path the scheduler takes.
 */
@Service
@RequiredArgsConstructor
public class SupplierPriceCatalogServiceImpl implements SupplierPriceCatalogService {

    private final PriceCatalogImportRepository importRepository;
    private final PriceCatalogUnmatchedLineRepository unmatchedLineRepository;
    private final SupplierProfileRepository profileRepository;
    private final PriceCatalogImporter importService;
    private final QuarantineReapplier reapplicationService;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Optional<PriceCatalogImportSummary> findLatestImport(@NonNull UUID vendorProfileId) {
        return importRepository
                .findFirstByVendorProfileIdAndStatusOrderByFetchedAtDesc(
                        vendorProfileId, PriceCatalogImportStatus.COMPLETED)
                .map(SupplierPriceCatalogServiceImpl::toSummary);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PagedResponse<PriceCatalogImportSummary> listImports(@NonNull UUID vendorProfileId, int page, int size) {
        Page<PriceCatalogImportEntity> result =
                importRepository.findByVendorProfileIdOrderByFetchedAtDesc(vendorProfileId, PageRequest.of(page, size));
        return toPage(result.map(SupplierPriceCatalogServiceImpl::toSummary), page, size);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PagedResponse<UnmatchedPriceCatalogLineView> listUnmatchedLines(
            @NonNull UUID vendorProfileId, int page, int size) {
        Page<PriceCatalogUnmatchedLineEntity> result =
                unmatchedLineRepository.findByVendorProfileIdAndResolvedAtIsNullOrderByCreatedAtDesc(
                        vendorProfileId, PageRequest.of(page, size));
        return toPage(result.map(SupplierPriceCatalogServiceImpl::toView), page, size);
    }

    @Override
    @NonNull
    public PriceCatalogImportSummary runImport(@NonNull UUID vendorProfileId) {
        SupplierProfileEntity profile = profileRepository
                .findById(vendorProfileId)
                .orElseThrow(() -> new SupplierConfigurationException(
                        SupplierConfigurationException.UNKNOWN_SUPPLIER,
                        "No vendor profile exists with id " + vendorProfileId));
        return toSummary(importService.runImport(new SupplierRef(profile.getSupplierRef())));
    }

    private static <T> PagedResponse<T> toPage(Page<T> source, int page, int size) {
        List<T> items = source.getContent();
        return new PagedResponse<>(items, page, size, source.getTotalElements(), source.getTotalPages());
    }

    @Override
    @NonNull
    public List<PriceCatalogImportSummary> reapplyQuarantine(@NonNull UUID vendorProfileId) {
        return reapplicationService.reapply(vendorProfileId).stream()
                .map(SupplierPriceCatalogServiceImpl::toSummary)
                .toList();
    }

    static PriceCatalogImportSummary toSummary(PriceCatalogImportEntity row) {
        return new PriceCatalogImportSummary(
                row.getImportManifestId(),
                row.getVendorProfileId(),
                row.getSupplierRef(),
                row.getStatus().name(),
                row.getFetchedAt(),
                row.getCompletedAt(),
                row.getLinesFetched(),
                row.getLinesMatched(),
                row.getLinesUnmatched(),
                row.getLinesDuplicate(),
                row.getChunkCount(),
                row.getSourceDocumentId(),
                row.getSourceDocumentDate(),
                row.getCountryCode(),
                row.getCurrency(),
                row.getFailureDetail());
    }

    static UnmatchedPriceCatalogLineView toView(PriceCatalogUnmatchedLineEntity row) {
        return new UnmatchedPriceCatalogLineView(
                row.getUnmatchedLineId(),
                row.getImportManifestId(),
                row.getVendorProfileId(),
                row.getPositionNumber(),
                row.getArticleEan(),
                row.getSupplierArticleCode(),
                row.getXReferenceCode(),
                row.getReason().name(),
                row.getReasonDetail(),
                row.getNetPrice(),
                row.getGrossPrice(),
                row.getEffectiveFrom(),
                row.getCurrency(),
                row.getFetchedAt(),
                row.getResolvedAt());
    }
}
