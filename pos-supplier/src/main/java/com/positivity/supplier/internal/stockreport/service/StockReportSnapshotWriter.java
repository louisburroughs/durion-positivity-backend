package com.positivity.supplier.internal.stockreport.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierStockReportLine;
import com.positivity.domainevents.supplier.SupplierStockReportUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.domain.model.SupplierStockSnapshot;
import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import com.positivity.supplier.internal.entity.StockSnapshotLineEntity;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.repository.StockSnapshotLineRepository;
import com.positivity.supplier.internal.repository.StockSnapshotRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits one stock snapshot and the events describing it, in a single transaction
 * (ADR-0044 §4, CAP-322).
 *
 * <p>Separate from the fetch orchestration for the same reason the PRICAT writer is: a
 * {@code @Transactional} method called from a sibling method of the same bean is not proxied, so
 * "the snapshot and its events commit together" would silently not be true.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReportSnapshotWriter {

    /** Snapshot statuses; a failed or empty fetch is a recorded outcome, not an exception. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    public static final String STATUS_EMPTY = "EMPTY";

    public static final String STATUS_FAILED = "FAILED";

    /**
     * B2.1 states no market on the document and the vendor profile carries no country, so the
     * snapshot's scope is the buyer account alone. The column and the event field stay for the
     * sibling C1.0 report, whose request does take a market — recording null here is honest, where
     * inventing a country would give a consumer a scope the vendor never stated.
     */
    private static final String NO_MARKET_STATED = null;

    private final StockSnapshotRepository snapshotRepository;
    private final StockSnapshotLineRepository snapshotLineRepository;
    private final SupplierOutboxEventWriter outboxWriter;
    private final Clock clock;

    /**
     * Persists a decoded snapshot and publishes it as chunked {@code supplier.stockreport.updated}
     * events.
     *
     * <p>Chunked for the same reason PRICAT is: a country-level stock report is as wide as a
     * catalog, and one message would exceed broker limits for a real dealer. Each chunk carries its
     * sequence and the total, so a consumer can detect a gap without a second event type.
     */
    @NonNull
    @Transactional
    public StockSnapshotEntity commit(
            @NonNull SupplierStockSnapshot snapshot,
            @NonNull ResolvedBinding binding,
            @NonNull SupplierAccountEntity billing,
            @NonNull String correlationId,
            @NonNull Instant fetchedAt,
            int chunkSize) {

        List<List<SupplierStockSnapshot.Line>> chunks = partition(snapshot.lines(), chunkSize);
        Instant completedAt = Instant.now(clock);
        String status = snapshot.lines().isEmpty() ? STATUS_EMPTY : STATUS_COMPLETED;

        StockSnapshotEntity row = snapshotRepository.save(StockSnapshotEntity.builder()
                .vendorProfileId(binding.profile().getVendorProfileId())
                .supplierRef(binding.profile().getSupplierRef())
                .protocolVersion(binding.version().value())
                .status(status)
                .documentId(snapshot.documentId())
                .issuedOn(snapshot.issuedOn())
                .snapshotAsOf(snapshot.issuedAt())
                .buyerAccountNumber(billing.getAccountNumber())
                .countryCode(NO_MARKET_STATED)
                .fetchedAt(fetchedAt)
                .completedAt(completedAt)
                .linesReported(snapshot.lines().size())
                .linesRejected(snapshot.rejectedLines().size())
                .chunkCount(chunks.size())
                .chunkSize(chunkSize)
                .correlationId(correlationId)
                .build());

        for (SupplierStockSnapshot.Line line : snapshot.lines()) {
            snapshotLineRepository.save(StockSnapshotLineEntity.builder()
                    .snapshotId(row.getSnapshotId())
                    .vendorProfileId(row.getVendorProfileId())
                    .vendorLineId(line.lineId())
                    .articleEan(line.articleEan())
                    .supplierArticleCode(line.supplierArticleCode())
                    .buyersArticleId(line.buyersArticleId())
                    .description(truncate(line.description(), 512))
                    .availableQuantity(line.availableQuantity())
                    .build());
        }

        publish(row, chunks);

        log.info(
                "Stock snapshot {} for profile {}: {} lines reported, {} rejected, {} chunks, vendor asOf {}",
                row.getSnapshotId(),
                row.getVendorProfileId(),
                row.getLinesReported(),
                row.getLinesRejected(),
                chunks.size(),
                row.getSnapshotAsOf());
        return row;
    }

    /**
     * Records a failed fetch or decode. No lines, no events: the previous snapshot stays the last
     * thing the vendor actually said, and a consumer's hints keep their existing staleness rather
     * than being replaced by an empty answer.
     */
    @NonNull
    @Transactional
    public StockSnapshotEntity persistFailure(
            @NonNull ResolvedBinding binding,
            @NonNull SupplierAccountEntity billing,
            @NonNull String correlationId,
            @NonNull Instant fetchedAt,
            @Nullable String failureDetail) {
        StockSnapshotEntity row = snapshotRepository.save(StockSnapshotEntity.builder()
                .vendorProfileId(binding.profile().getVendorProfileId())
                .supplierRef(binding.profile().getSupplierRef())
                .protocolVersion(binding.version().value())
                .status(STATUS_FAILED)
                .buyerAccountNumber(billing.getAccountNumber())
                .countryCode(NO_MARKET_STATED)
                .fetchedAt(fetchedAt)
                .correlationId(correlationId)
                .failureDetail(truncate(failureDetail, 2000))
                .build());
        log.warn("Stock snapshot {} failed: {}", row.getSnapshotId(), failureDetail);
        return row;
    }

    private void publish(StockSnapshotEntity row, List<List<SupplierStockSnapshot.Line>> chunks) {
        String topic = DomainTopics.events("supplier");
        int sequence = 0;
        for (List<SupplierStockSnapshot.Line> chunk : chunks) {
            sequence++;
            SupplierStockReportUpdatedV1 payload = new SupplierStockReportUpdatedV1(
                    row.getSnapshotId(),
                    row.getVendorProfileId(),
                    row.getSupplierRef(),
                    sequence,
                    chunks.size(),
                    row.getDocumentId(),
                    row.getBuyerAccountNumber(),
                    row.getCountryCode(),
                    row.getSnapshotAsOf(),
                    row.getFetchedAt(),
                    row.getLinesReported(),
                    chunk.stream().map(StockReportSnapshotWriter::toEventLine).toList());
            outboxWriter.publish(
                    topic,
                    new DomainEventEnvelope<>(
                            UUIDv7Generator.generate(),
                            SupplierStockReportUpdatedV1.EVENT_TYPE,
                            SupplierStockReportUpdatedV1.SCHEMA_VERSION,
                            row.getSnapshotId(),
                            sequence,
                            Instant.now(clock),
                            "pos-supplier",
                            row.getCorrelationId(),
                            row.getCreatedBy(),
                            payload));
        }
    }

    private static SupplierStockReportLine toEventLine(SupplierStockSnapshot.Line line) {
        return new SupplierStockReportLine(
                line.articleEan(),
                line.supplierArticleCode(),
                line.buyersArticleId(),
                line.description(),
                line.availableQuantity());
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        if (items.isEmpty()) {
            return List.of();
        }
        int chunkSize = Math.max(1, size);
        List<List<T>> chunks = new ArrayList<>((items.size() + chunkSize - 1) / chunkSize);
        for (int start = 0; start < items.size(); start += chunkSize) {
            chunks.add(List.copyOf(items.subList(start, Math.min(items.size(), start + chunkSize))));
        }
        return chunks;
    }

    @Nullable
    private static String truncate(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Snapshot id accessor used by the orchestration layer's logging. */
    @NonNull
    public static UUID idOf(@NonNull StockSnapshotEntity snapshot) {
        return snapshot.getSnapshotId();
    }
}
