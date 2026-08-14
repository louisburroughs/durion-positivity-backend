package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogLine;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.adapter.ediwheelb.PricatDocument;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.entity.PriceCatalogEntryEntity;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.PriceCatalogUnmatchedLineEntity;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogImporter.MatchedLine;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogImporter.PreparedImport;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogImporter.QuarantinedLine;
import com.positivity.supplier.internal.repository.PriceCatalogEntryRepository;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.repository.PriceCatalogUnmatchedLineRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits one PRICAT import: staged lines, quarantined lines, the manifest row, and the events
 * that describe them — all in a single transaction (ADR-0053 §7, ADR-0044 §4).
 *
 * <p>Separate from {@link PriceCatalogImporter} because the transaction has to be real: a
 * {@code @Transactional} method called from a sibling method of the same bean is not proxied, so
 * "staging and its events commit together" would silently be untrue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCatalogStagingWriter {

    private final PriceCatalogImportRepository importRepository;
    private final PriceCatalogEntryRepository entryRepository;
    private final PriceCatalogUnmatchedLineRepository unmatchedLineRepository;
    private final SupplierOutboxEventWriter outboxWriter;
    private final Clock clock;

    /**
     * Persists a completed (or empty) import and queues its chunk plus completion events.
     *
     * @throws IllegalStateException when the line counters do not partition the fetch — a line went
     *                               missing between decode and staging, and an under-reported
     *                               import would look complete to every consumer
     */
    @NonNull
    @Transactional
    public PriceCatalogImportEntity commit(
            @NonNull PreparedImport prepared,
            @NonNull PricatDocument document,
            @NonNull ResolvedBinding binding,
            @NonNull SupplierAccountEntity billing,
            @NonNull UUID importManifestId,
            @NonNull String correlationId,
            @NonNull Instant fetchedAt,
            int chunkSize) {

        int linesMatched = prepared.matched().size();
        int linesUnmatched = prepared.quarantined().size() - prepared.duplicates();
        int linesFetched = document.linesFetched();
        int accounted = linesMatched + linesUnmatched + prepared.duplicates();
        if (accounted != linesFetched) {
            throw new IllegalStateException("PRICAT line accounting mismatch for import " + importManifestId
                    + ": fetched=" + linesFetched + " matched=" + linesMatched + " unmatched=" + linesUnmatched
                    + " duplicate=" + prepared.duplicates());
        }

        List<List<MatchedLine>> chunks = partition(prepared.matched(), chunkSize);
        Instant completedAt = Instant.now(clock);
        PriceCatalogImportStatus status =
                linesFetched == 0 ? PriceCatalogImportStatus.EMPTY : PriceCatalogImportStatus.COMPLETED;

        PriceCatalogImportEntity importRow = importRepository.save(PriceCatalogImportEntity.builder()
                .importManifestId(importManifestId)
                .vendorProfileId(binding.profile().getVendorProfileId())
                .supplierRef(binding.profile().getSupplierRef())
                .protocolFamily(binding.family())
                .protocolVersion(binding.version().value())
                .status(status)
                .sourceDocumentId(document.documentId())
                .sourceDocumentDate(document.documentDate())
                .buyerAccountNumber(billing.getAccountNumber())
                .buyerAgencyCode(billing.getAgencyCode())
                .countryCode(document.countryCode())
                .currency(document.currency())
                .fetchedAt(fetchedAt)
                .completedAt(completedAt)
                .linesFetched(linesFetched)
                .linesMatched(linesMatched)
                .linesUnmatched(linesUnmatched)
                .linesDuplicate(prepared.duplicates())
                .chunkCount(chunks.size())
                .chunkSize(chunkSize)
                .contentChecksum(checksum(prepared.matched()))
                .correlationId(correlationId)
                .build());

        for (MatchedLine line : prepared.matched()) {
            entryRepository.save(toEntryEntity(line, importRow, billing, fetchedAt));
        }
        for (QuarantinedLine line : prepared.quarantined()) {
            unmatchedLineRepository.save(toUnmatchedEntity(line, importRow, billing, fetchedAt));
        }

        publishEvents(importRow, chunks, completedAt);
        log.info(
                "PRICAT import {} for profile {} staged: fetched={} matched={} unmatched={} duplicate={} chunks={}",
                importManifestId,
                importRow.getVendorProfileId(),
                linesFetched,
                linesMatched,
                linesUnmatched,
                prepared.duplicates(),
                chunks.size());
        return importRow;
    }

    /**
     * Records a failed fetch or decode. No entries, no quarantine rows, and no events: the previous
     * import stays the latest word on this vendor's prices, because a vendor outage is not a
     * statement that the catalog is empty.
     */
    @NonNull
    @Transactional
    public PriceCatalogImportEntity persistFailure(
            @NonNull UUID importManifestId,
            @NonNull ResolvedBinding binding,
            @NonNull SupplierAccountEntity billing,
            @NonNull Instant fetchedAt,
            @NonNull String correlationId,
            @Nullable String failureDetail) {
        PriceCatalogImportEntity row = importRepository.save(PriceCatalogImportEntity.builder()
                .importManifestId(importManifestId)
                .vendorProfileId(binding.profile().getVendorProfileId())
                .supplierRef(binding.profile().getSupplierRef())
                .protocolFamily(binding.family())
                .protocolVersion(binding.version().value())
                .status(PriceCatalogImportStatus.FAILED)
                .buyerAccountNumber(billing.getAccountNumber())
                .buyerAgencyCode(billing.getAgencyCode())
                .fetchedAt(fetchedAt)
                .correlationId(correlationId)
                .failureDetail(truncate(failureDetail, 2000))
                .build());
        log.warn("PRICAT import {} failed: {}", importManifestId, failureDetail);
        return row;
    }

    private void publishEvents(
            PriceCatalogImportEntity importRow, List<List<MatchedLine>> chunks, Instant completedAt) {
        String topic = DomainTopics.events("supplier");
        int sequence = 0;
        for (List<MatchedLine> chunk : chunks) {
            sequence++;
            SupplierPriceCatalogUpdatedV1 payload = new SupplierPriceCatalogUpdatedV1(
                    importRow.getImportManifestId(),
                    importRow.getVendorProfileId(),
                    importRow.getSupplierRef(),
                    sequence,
                    chunks.size(),
                    importRow.getSourceDocumentId(),
                    importRow.getSourceDocumentDate(),
                    importRow.getBuyerAccountNumber(),
                    importRow.getCountryCode(),
                    importRow.getCurrency(),
                    importRow.getProtocolVersion(),
                    importRow.getFetchedAt(),
                    chunk.stream().map(PriceCatalogStagingWriter::toEventLine).toList());
            outboxWriter.publish(
                    topic, envelope(importRow, SupplierPriceCatalogUpdatedV1.EVENT_TYPE, sequence, payload));
        }

        SupplierPriceCatalogImportCompletedV1 completion = new SupplierPriceCatalogImportCompletedV1(
                importRow.getImportManifestId(),
                importRow.getVendorProfileId(),
                importRow.getSupplierRef(),
                importRow.getStatus().name(),
                importRow.getLinesFetched(),
                importRow.getLinesMatched(),
                importRow.getLinesUnmatched(),
                importRow.getLinesDuplicate(),
                importRow.getChunkCount(),
                importRow.getChunkSize(),
                importRow.getContentChecksum(),
                importRow.getSourceDocumentId(),
                importRow.getSourceDocumentDate(),
                importRow.getBuyerAccountNumber(),
                importRow.getCountryCode(),
                importRow.getCurrency(),
                importRow.getFetchedAt(),
                completedAt);
        // Sequenced after every chunk, and the outbox drains in id order, so a consumer can never
        // see "complete" before the lines it is meant to complete.
        outboxWriter.publish(
                topic,
                envelope(importRow, SupplierPriceCatalogImportCompletedV1.EVENT_TYPE, chunks.size() + 1L, completion));
    }

    private <T> DomainEventEnvelope<T> envelope(
            PriceCatalogImportEntity importRow, String eventType, long aggregateVersion, T payload) {
        return new DomainEventEnvelope<>(
                UUIDv7Generator.generate(),
                eventType,
                1,
                importRow.getImportManifestId(),
                aggregateVersion,
                Instant.now(clock),
                "pos-supplier",
                importRow.getCorrelationId(),
                importRow.getCreatedBy(),
                payload);
    }

    private static SupplierPriceCatalogLine toEventLine(MatchedLine line) {
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

    private static PriceCatalogEntryEntity toEntryEntity(
            MatchedLine line, PriceCatalogImportEntity importRow, SupplierAccountEntity billing, Instant fetchedAt) {
        SupplierPriceCatalogEntry entry = line.entry();
        return PriceCatalogEntryEntity.builder()
                .importManifestId(importRow.getImportManifestId())
                .vendorProfileId(importRow.getVendorProfileId())
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
                .buyerAccountNumber(billing.getAccountNumber())
                .countryCode(entry.countryCode())
                .currency(entry.currency())
                .sourceDocumentId(entry.sourceDocumentId())
                .sourceDocumentDate(entry.sourceDocumentDate())
                .fetchedAt(fetchedAt)
                .build();
    }

    private static PriceCatalogUnmatchedLineEntity toUnmatchedEntity(
            QuarantinedLine line,
            PriceCatalogImportEntity importRow,
            SupplierAccountEntity billing,
            Instant fetchedAt) {
        SupplierPriceCatalogEntry entry = line.entry();
        return PriceCatalogUnmatchedLineEntity.builder()
                .importManifestId(importRow.getImportManifestId())
                .vendorProfileId(importRow.getVendorProfileId())
                .positionNumber(line.positionNumber())
                .articleEan(line.articleEan())
                .supplierArticleCode(line.supplierArticleCode())
                .xReferenceCode(line.xReferenceCode())
                .reason(line.reason())
                .reasonDetail(truncate(line.detail(), 1000))
                .suggestedRetailPrice(entry == null ? null : entry.suggestedRetailPrice())
                .grossPrice(entry == null ? null : entry.grossPrice())
                .netPrice(entry == null ? null : entry.netPrice())
                .taxRate(entry == null ? null : entry.taxRate())
                .recyclingFee(entry == null ? null : entry.recyclingFee())
                .effectiveFrom(entry == null ? null : entry.effectiveFrom())
                .buyerAccountNumber(billing.getAccountNumber())
                .countryCode(entry == null ? importRow.getCountryCode() : entry.countryCode())
                .currency(entry == null ? importRow.getCurrency() : entry.currency())
                .sourceDocumentId(entry == null ? importRow.getSourceDocumentId() : entry.sourceDocumentId())
                .sourceDocumentDate(entry == null ? importRow.getSourceDocumentDate() : entry.sourceDocumentDate())
                .fetchedAt(fetchedAt)
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

    /**
     * SHA-256 over each published line's product, identity, values and effective date, so a
     * consumer can prove it applied the same set the producer staged.
     */
    @Nullable
    private static String checksum(List<MatchedLine> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (MatchedLine line : lines) {
                SupplierPriceCatalogEntry entry = line.entry();
                digest.update((line.productId() + "|" + entry.articleEan() + "|" + entry.netPrice() + "|"
                                + entry.grossPrice() + "|" + entry.effectiveFrom() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for PRICAT import checksums", e);
        }
    }

    @Nullable
    private static String truncate(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
