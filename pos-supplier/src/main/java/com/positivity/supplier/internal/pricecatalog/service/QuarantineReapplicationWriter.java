package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogLine;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.entity.PriceCatalogEntryEntity;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.pricecatalog.service.QuarantineReapplier.ResolvedLine;
import com.positivity.supplier.internal.repository.PriceCatalogEntryRepository;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a re-application: staged entries, closed quarantine rows, and the events describing them,
 * in one transaction (ADR-0053 §5/§7, ADR-0044 §4).
 *
 * <p>Separate from {@link QuarantineReapplier} for the same reason the import writer is
 * separate: a {@code @Transactional} method called from a sibling method of the same bean is not
 * proxied, so "the rows and their events commit together" would silently not hold.
 *
 * <p>Closing a quarantine row and publishing its price are the same fact. If the close committed
 * without the event, an operator's worklist would go quiet while consumers never received the
 * price — the worst of the two failures, because nothing would look wrong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuarantineReapplicationWriter {

    private final PriceCatalogImportRepository importRepository;
    private final PriceCatalogEntryRepository entryRepository;
    private final PriceCatalogUnmatchedLineRepository unmatchedLineRepository;
    private final SupplierOutboxEventWriter outboxWriter;
    private final Clock clock;

    /**
     * Stages the resolved lines under a fresh manifest, closes their quarantine rows, and publishes
     * the chunked events plus a completion event.
     *
     * @param profile   the vendor profile being re-applied
     * @param resolved  lines that now match
     * @param appliedAt the instant of this re-application
     * @param chunkSize event chunk size
     * @return the re-application manifest
     */
    @NonNull
    @Transactional
    public PriceCatalogImportEntity commit(
            @NonNull SupplierProfileEntity profile,
            @NonNull List<ResolvedLine> resolved,
            @NonNull Instant appliedAt,
            int chunkSize) {

        PriceCatalogUnmatchedLineEntity first = resolved.getFirst().quarantineRow();
        // The original manifest, which the quarantine row's FK guarantees exists. Its norm is copied
        // rather than invented: these lines came from that vendor document, and a consumer reading
        // protocolVersion on the event is asking which norm produced the prices, not which code
        // path re-applied them.
        PriceCatalogImportEntity origin = importRepository
                .findById(first.getImportManifestId())
                .orElseThrow(() -> new IllegalStateException("Quarantined line " + first.getUnmatchedLineId()
                        + " references import " + first.getImportManifestId() + ", which does not exist"));
        String correlationId = SupplierCorrelationContext.currentOrGenerate();
        List<List<ResolvedLine>> chunks = partition(resolved, chunkSize);

        PriceCatalogImportEntity manifest = importRepository.save(PriceCatalogImportEntity.builder()
                .vendorProfileId(profile.getVendorProfileId())
                .supplierRef(profile.getSupplierRef())
                .protocolFamily(origin.getProtocolFamily())
                .protocolVersion(origin.getProtocolVersion())
                .status(PriceCatalogImportStatus.COMPLETED)
                .sourceDocumentId(first.getSourceDocumentId())
                .sourceDocumentDate(first.getSourceDocumentDate())
                .buyerAccountNumber(first.getBuyerAccountNumber())
                .countryCode(first.getCountryCode())
                .currency(first.getCurrency())
                .fetchedAt(first.getFetchedAt())
                .completedAt(appliedAt)
                .linesFetched(resolved.size())
                .linesMatched(resolved.size())
                .linesUnmatched(0)
                .linesDuplicate(0)
                .chunkCount(chunks.size())
                .chunkSize(chunkSize)
                .correlationId(correlationId)
                .reappliedFromImportId(first.getImportManifestId())
                .build());

        for (ResolvedLine line : resolved) {
            entryRepository.save(toEntryEntity(line, manifest));
            PriceCatalogUnmatchedLineEntity row = line.quarantineRow();
            row.setResolvedAt(appliedAt);
            row.setResolvedProductId(line.productId());
            unmatchedLineRepository.save(row);
        }

        publish(manifest, chunks, appliedAt);
        return manifest;
    }

    private void publish(PriceCatalogImportEntity manifest, List<List<ResolvedLine>> chunks, Instant completedAt) {
        String topic = DomainTopics.events("supplier");
        int sequence = 0;
        for (List<ResolvedLine> chunk : chunks) {
            sequence++;
            SupplierPriceCatalogUpdatedV1 payload = new SupplierPriceCatalogUpdatedV1(
                    manifest.getImportManifestId(),
                    manifest.getVendorProfileId(),
                    manifest.getSupplierRef(),
                    sequence,
                    chunks.size(),
                    manifest.getSourceDocumentId(),
                    manifest.getSourceDocumentDate(),
                    manifest.getBuyerAccountNumber(),
                    manifest.getCountryCode(),
                    manifest.getCurrency(),
                    manifest.getProtocolVersion(),
                    manifest.getFetchedAt(),
                    chunk.stream()
                            .map(QuarantineReapplicationWriter::toEventLine)
                            .toList());
            outboxWriter.publish(
                    topic, envelope(manifest, SupplierPriceCatalogUpdatedV1.EVENT_TYPE, sequence, payload));
        }

        // A completion event with its own chunkCount, so a consumer can confirm the re-application
        // whole exactly as it confirms an import. Without it the consumer would hold chunks of a
        // manifest that never declares a total and could never be marked complete.
        SupplierPriceCatalogImportCompletedV1 completion = new SupplierPriceCatalogImportCompletedV1(
                manifest.getImportManifestId(),
                manifest.getVendorProfileId(),
                manifest.getSupplierRef(),
                manifest.getStatus().name(),
                manifest.getLinesFetched(),
                manifest.getLinesMatched(),
                manifest.getLinesUnmatched(),
                manifest.getLinesDuplicate(),
                manifest.getChunkCount(),
                manifest.getChunkSize(),
                null,
                manifest.getSourceDocumentId(),
                manifest.getSourceDocumentDate(),
                manifest.getBuyerAccountNumber(),
                manifest.getCountryCode(),
                manifest.getCurrency(),
                manifest.getFetchedAt(),
                completedAt);
        outboxWriter.publish(
                topic,
                envelope(manifest, SupplierPriceCatalogImportCompletedV1.EVENT_TYPE, chunks.size() + 1L, completion));
    }

    private <T> DomainEventEnvelope<T> envelope(
            PriceCatalogImportEntity manifest, String eventType, long aggregateVersion, T payload) {
        return new DomainEventEnvelope<>(
                UUIDv7Generator.generate(),
                eventType,
                1,
                manifest.getImportManifestId(),
                aggregateVersion,
                Instant.now(clock),
                "pos-supplier",
                manifest.getCorrelationId(),
                manifest.getCreatedBy(),
                payload);
    }

    private static SupplierPriceCatalogLine toEventLine(ResolvedLine line) {
        SupplierPriceCatalogEntry entry = line.entry();
        return new SupplierPriceCatalogLine(
                line.productId(),
                line.method().name(),
                entry.articleEan(),
                entry.supplierArticleCode(),
                entry.xReferenceCode(),
                entry.suggestedRetailPrice(),
                entry.grossPrice(),
                entry.netPrice(),
                entry.taxRate(),
                entry.recyclingFee(),
                entry.effectiveFrom(),
                entry.positionNumber());
    }

    private static PriceCatalogEntryEntity toEntryEntity(ResolvedLine line, PriceCatalogImportEntity manifest) {
        SupplierPriceCatalogEntry entry = line.entry();
        return PriceCatalogEntryEntity.builder()
                .importManifestId(manifest.getImportManifestId())
                .vendorProfileId(manifest.getVendorProfileId())
                .positionNumber(entry.positionNumber())
                .articleEan(entry.articleEan())
                .supplierArticleCode(entry.supplierArticleCode())
                .xReferenceCode(entry.xReferenceCode())
                .matchedProductId(line.productId())
                .matchMethod(line.method())
                .suggestedRetailPrice(entry.suggestedRetailPrice())
                .grossPrice(entry.grossPrice())
                .netPrice(entry.netPrice())
                .taxRate(entry.taxRate())
                .recyclingFee(entry.recyclingFee())
                .effectiveFrom(entry.effectiveFrom())
                .buyerAccountNumber(manifest.getBuyerAccountNumber())
                .countryCode(entry.countryCode())
                .currency(entry.currency())
                .sourceDocumentId(entry.sourceDocumentId())
                .sourceDocumentDate(entry.sourceDocumentDate())
                // The vendor's own fetch time, not now: the prices are as fresh as the document
                // they came from, and pretending otherwise would let a re-application outrank a
                // newer fetch in the latest-selection tie-break.
                .fetchedAt(manifest.getFetchedAt())
                .build();
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
}
