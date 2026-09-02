package com.positivity.supplier.internal.stockreport.service;

import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import com.positivity.supplier.internal.entity.StockSnapshotLineEntity;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.repository.StockSnapshotLineRepository;
import com.positivity.supplier.internal.repository.StockSnapshotRepository;
import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotLineView;
import com.positivity.supplier.internal.stockreport.service.model.StockSnapshotSummary;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshot reads over the stock-report tables (CAP-322; issue #1638 decision 5).
 *
 * <p>Thin projections only. There is deliberately no existence check against
 * {@code supplier_profile}: snapshots are received documents that outlive the configuration that
 * produced them (ADR-0050 §6/§7 pattern), so an unknown profile id and a profile that has never had
 * a stock report are the same observable answer — no snapshot, 404.
 */
@Service
@RequiredArgsConstructor
public class SupplierStockSnapshotServiceImpl implements SupplierStockSnapshotService {

    private final StockSnapshotRepository snapshotRepository;
    private final StockSnapshotLineRepository lineRepository;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public StockSnapshotSummary getLatestSnapshot(@NonNull UUID vendorProfileId) {
        return snapshotRepository
                .findByVendorProfileIdNewestSnapshotAsOfFirst(vendorProfileId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(SupplierStockSnapshotServiceImpl::toSummary)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.STOCK_SNAPSHOT_NOT_FOUND,
                        "No stock snapshot exists for vendor profile " + vendorProfileId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PagedResponse<StockSnapshotLineView> listSnapshotLines(
            @NonNull UUID vendorProfileId, @NonNull UUID snapshotId, @Nullable String search, int page, int size) {
        // One code for "no such snapshot" and "someone else's snapshot": confirming that an id
        // exists under another profile would leak another trading relationship's fetch history.
        snapshotRepository
                .findById(snapshotId)
                .filter(snapshot -> vendorProfileId.equals(snapshot.getVendorProfileId()))
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.STOCK_SNAPSHOT_NOT_FOUND,
                        "No stock snapshot " + snapshotId + " exists for vendor profile " + vendorProfileId));

        Page<StockSnapshotLineEntity> result =
                lineRepository.searchBySnapshotId(snapshotId, toLikePattern(search), PageRequest.of(page, size));
        return new PagedResponse<>(
                result.getContent().stream()
                        .map(SupplierStockSnapshotServiceImpl::toLineView)
                        .toList(),
                page,
                size,
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * A contains-match {@code LIKE} pattern, or null when there is nothing to search for.
     * Lowercased (the query compares lowercased columns) and with the {@code LIKE} metacharacters
     * escaped under escape character {@code !}: a pasted article code is a literal, not a wildcard.
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

    private static StockSnapshotSummary toSummary(StockSnapshotEntity snapshot) {
        return new StockSnapshotSummary(
                snapshot.getSnapshotId(),
                snapshot.getVendorProfileId(),
                snapshot.getSupplierRef(),
                snapshot.getBuyerAccountNumber(),
                snapshot.getCountryCode(),
                snapshot.getStatus(),
                snapshot.getDocumentId(),
                snapshot.getIssuedOn(),
                snapshot.getSnapshotAsOf(),
                snapshot.getFetchedAt(),
                snapshot.getCompletedAt(),
                snapshot.getLinesReported(),
                snapshot.getLinesRejected(),
                snapshot.getProtocolVersion());
    }

    private static StockSnapshotLineView toLineView(StockSnapshotLineEntity line) {
        return new StockSnapshotLineView(
                line.getLineId(),
                line.getVendorLineId(),
                line.getArticleEan(),
                line.getSupplierArticleCode(),
                line.getBuyersArticleId(),
                line.getDescription(),
                line.getAvailableQuantity());
    }
}
