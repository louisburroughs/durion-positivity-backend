package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.SupplierPriceEntryDto;
import com.positivity.catalog.internal.dto.SupplierPriceImportStatusDto;
import com.positivity.catalog.internal.entity.SupplierPriceEntryEntity;
import com.positivity.catalog.internal.entity.SupplierPriceImportEntity;
import com.positivity.catalog.internal.repository.SupplierPriceEntryRepository;
import com.positivity.catalog.internal.repository.SupplierPriceImportRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the append-only supplier price entries (ADR-0053 §1–§2). */
@Service
@RequiredArgsConstructor
public class SupplierPriceEntryServiceImpl implements SupplierPriceEntryService {

    private final SupplierPriceEntryRepository priceEntryRepository;
    private final SupplierPriceImportRepository priceImportRepository;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Optional<SupplierPriceEntryDto> findLatest(
            @NonNull UUID productId,
            @Nullable UUID vendorProfileId,
            @NonNull String countryCode,
            @NonNull String currency,
            @Nullable LocalDate asOf) {
        LocalDate day = asOf == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : asOf;
        return priceEntryRepository
                .findApplicable(productId, vendorProfileId, countryCode, currency, day, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(SupplierPriceEntryServiceImpl::toDto);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Page<SupplierPriceEntryDto> findHistory(
            @NonNull UUID productId, @Nullable UUID vendorProfileId, @NonNull Pageable pageable) {
        return priceEntryRepository
                .findHistory(productId, vendorProfileId, pageable)
                .map(SupplierPriceEntryServiceImpl::toDto);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<SupplierPriceImportStatusDto> findIncompleteImports() {
        return priceImportRepository
                .findByStatusOrderByUpdatedAtDesc(SupplierPriceImportEntity.STATUS_INCOMPLETE)
                .stream()
                .map(SupplierPriceEntryServiceImpl::toStatusDto)
                .toList();
    }

    static SupplierPriceEntryDto toDto(SupplierPriceEntryEntity entity) {
        return new SupplierPriceEntryDto(
                entity.getId(),
                entity.getVendorProfileId(),
                entity.getSupplierRef(),
                entity.getProductId(),
                entity.getMatchMethod(),
                entity.getArticleEan(),
                entity.getSupplierArticleCode(),
                entity.getBuyerAccountNumber(),
                entity.getCountryCode(),
                entity.getCurrency(),
                entity.getSuggestedRetailPrice(),
                entity.getGrossPrice(),
                entity.getNetPrice(),
                entity.getTaxRate(),
                entity.getRecyclingFee(),
                entity.getEffectiveFrom(),
                entity.getSourceDocumentId(),
                entity.getSourceDocumentDate(),
                entity.getImportManifestId(),
                entity.getFetchedAt());
    }

    static SupplierPriceImportStatusDto toStatusDto(SupplierPriceImportEntity entity) {
        return new SupplierPriceImportStatusDto(
                entity.getImportManifestId(),
                entity.getVendorProfileId(),
                entity.getSupplierRef(),
                entity.getStatus(),
                entity.getChunksApplied(),
                entity.getExpectedChunkCount(),
                entity.getLinesApplied(),
                entity.getExpectedLineCount(),
                entity.getCompletedAt(),
                entity.getUpdatedAt());
    }
}
