package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierScheduleLeaseEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogBindingFreshness;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogFreshnessView;
import com.positivity.supplier.internal.pricecatalog.service.model.PriceCatalogImportSummary;
import com.positivity.supplier.internal.pricecatalog.service.model.UnmatchedPriceCatalogLineView;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierScheduleLeaseRepository;
import com.positivity.supplier.internal.service.model.PagedResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Import bookkeeping and the on-demand trigger for vendor price catalogs (ADR-0053 §7).
 *
 * <p>Reads are thin projections over the import and quarantine tables. The trigger delegates to
 * {@link PriceCatalogImporter}, which is the same path the scheduler takes.
 *
 * <p>The staleness threshold is backend configuration (#1637 decision 3):
 * {@code pos.supplier.pricat.staleness-threshold}, an ISO-8601 duration defaulting to seven days.
 * It is a policy about how old a catalog fetch may be before an operator should worry, returned in
 * the freshness view so every client applies the same rule — and it has nothing to do with any
 * request-cache TTL.
 */
@Service
public class SupplierPriceCatalogServiceImpl implements SupplierPriceCatalogService {

    private final PriceCatalogImportRepository importRepository;
    private final PriceCatalogUnmatchedLineRepository unmatchedLineRepository;
    private final SupplierProfileRepository profileRepository;
    private final SupplierEndpointBindingRepository bindingRepository;
    private final SupplierScheduleLeaseRepository scheduleLeaseRepository;
    private final PriceCatalogImporter importService;
    private final QuarantineReapplier reapplicationService;
    private final Clock clock;
    private final Duration stalenessThreshold;

    public SupplierPriceCatalogServiceImpl(
            PriceCatalogImportRepository importRepository,
            PriceCatalogUnmatchedLineRepository unmatchedLineRepository,
            SupplierProfileRepository profileRepository,
            SupplierEndpointBindingRepository bindingRepository,
            SupplierScheduleLeaseRepository scheduleLeaseRepository,
            PriceCatalogImporter importService,
            QuarantineReapplier reapplicationService,
            Clock clock,
            @Value("${pos.supplier.pricat.staleness-threshold:P7D}") Duration stalenessThreshold) {
        this.importRepository = importRepository;
        this.unmatchedLineRepository = unmatchedLineRepository;
        this.profileRepository = profileRepository;
        this.bindingRepository = bindingRepository;
        this.scheduleLeaseRepository = scheduleLeaseRepository;
        this.importService = importService;
        this.reapplicationService = reapplicationService;
        this.clock = clock;
        this.stalenessThreshold = stalenessThreshold;
    }

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
    public PagedResponse<PriceCatalogImportSummary> listImports(
            @NonNull UUID vendorProfileId,
            @Nullable UUID bindingId,
            @Nullable PriceCatalogImportStatus status,
            @Nullable Instant fetchedFrom,
            @Nullable Instant fetchedTo,
            int page,
            int size) {
        Page<PriceCatalogImportEntity> result = importRepository.search(
                vendorProfileId, bindingId, status, fetchedFrom, fetchedTo, PageRequest.of(page, size));
        return toPage(result.map(SupplierPriceCatalogServiceImpl::toSummary), page, size);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PagedResponse<UnmatchedPriceCatalogLineView> listUnmatchedLines(
            @NonNull UUID vendorProfileId,
            @Nullable UnmatchedLineReason reason,
            @Nullable String search,
            @Nullable Instant fetchedFrom,
            @Nullable Instant fetchedTo,
            @Nullable Boolean resolved,
            int page,
            int size) {
        Page<PriceCatalogUnmatchedLineEntity> result = unmatchedLineRepository.search(
                vendorProfileId,
                Boolean.TRUE.equals(resolved),
                reason,
                toLikePattern(search),
                fetchedFrom,
                fetchedTo,
                PageRequest.of(page, size));
        return toPage(result.map(SupplierPriceCatalogServiceImpl::toView), page, size);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PriceCatalogFreshnessView getFreshness(@NonNull UUID vendorProfileId) {
        SupplierProfileEntity profile = requireProfile(vendorProfileId);

        LocalDate latestEffectiveDate = importRepository.findLatestSourceDocumentDate(
                profile.getVendorProfileId(), PriceCatalogImportStatus.COMPLETED);
        Instant lastFetchedAt = importRepository.findLastFetchedAt(profile.getVendorProfileId());
        Instant lastCompletedAt = importRepository.findLastCompletedAt(profile.getVendorProfileId());
        long unresolvedUnmatchedCount =
                unmatchedLineRepository.countByVendorProfileIdAndResolvedAtIsNull(profile.getVendorProfileId());

        // Never fetched is the stalest a feed can be; otherwise strictly older than the threshold.
        boolean stale = lastFetchedAt == null
                || lastFetchedAt.isBefore(Instant.now(clock).minus(stalenessThreshold));

        List<PriceCatalogBindingFreshness> bindings = bindingRepository
                .findByVendorProfileIdAndCapability(profile.getVendorProfileId(), SupplierCapability.PRICE_CATALOG)
                .map(this::toBindingFreshness)
                .map(List::of)
                .orElse(List.of());

        return new PriceCatalogFreshnessView(
                profile.getVendorProfileId(),
                latestEffectiveDate,
                lastFetchedAt,
                lastCompletedAt,
                unresolvedUnmatchedCount,
                stalenessThreshold.toString(),
                stale,
                bindings);
    }

    @NonNull
    private PriceCatalogBindingFreshness toBindingFreshness(@NonNull SupplierEndpointBindingEntity binding) {
        Optional<SupplierScheduleLeaseEntity> lease = scheduleLeaseRepository.findById(binding.getId());
        return new PriceCatalogBindingFreshness(
                binding.getId(),
                binding.getScheduleCron(),
                binding.isEnabled(),
                lease.map(SupplierScheduleLeaseEntity::getCheckpointAt).orElse(null),
                lease.map(SupplierScheduleLeaseEntity::getLastRunOutcome).orElse(null),
                lease.map(SupplierScheduleLeaseEntity::getLastRunStartedAt).orElse(null));
    }

    @Override
    @NonNull
    public PriceCatalogImportSummary runImport(@NonNull UUID vendorProfileId) {
        SupplierProfileEntity profile = requireProfile(vendorProfileId);
        return toSummary(importService.runImport(new SupplierRef(profile.getSupplierRef())));
    }

    @NonNull
    private SupplierProfileEntity requireProfile(@NonNull UUID vendorProfileId) {
        return profileRepository
                .findById(vendorProfileId)
                .orElseThrow(() -> new SupplierConfigurationException(
                        SupplierConfigurationException.UNKNOWN_SUPPLIER,
                        "No vendor profile exists with id " + vendorProfileId));
    }

    private static <T> PagedResponse<T> toPage(Page<T> source, int page, int size) {
        List<T> items = source.getContent();
        return new PagedResponse<>(items, page, size, source.getTotalElements(), source.getTotalPages());
    }

    /**
     * A contains-match {@code LIKE} pattern for the free-text identifier search, or null when there
     * is nothing to search for.
     *
     * <p>Lowercased here (the query compares against lowercased columns) and with the {@code LIKE}
     * metacharacters escaped under escape character {@code !}: an operator pasting a vendor article
     * code containing {@code _} is quoting a literal reference, not writing a wildcard. Same
     * contract as the transmission ledger search.
     */
    @Nullable
    private static String toLikePattern(@Nullable String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.trim()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
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
                row.getBindingId(),
                row.getSupplierRef(),
                row.getStatus().name(),
                row.getFetchedAt(),
                row.getCompletedAt(),
                row.getWindowFrom(),
                row.getWindowTo(),
                row.getCheckpointState(),
                row.getCheckpointAt(),
                row.getLinesFetched(),
                row.getLinesMatched(),
                row.getLinesUnmatched(),
                row.getLinesDuplicate(),
                row.getChunkCount(),
                row.getSourceDocumentId(),
                row.getSourceDocumentDate(),
                row.getCountryCode(),
                row.getCurrency(),
                row.getFailureDetail(),
                row.getErrorCode() == null ? null : row.getErrorCode().name());
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
