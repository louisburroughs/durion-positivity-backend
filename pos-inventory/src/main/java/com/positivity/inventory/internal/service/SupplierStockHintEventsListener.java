package com.positivity.inventory.internal.service;

import com.positivity.domainevents.supplier.SupplierStockReportLine;
import com.positivity.domainevents.supplier.SupplierStockReportUpdatedV1;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.entity.SupplierStockHint;
import com.positivity.inventory.internal.entity.SupplierStockSnapshotChunk;
import com.positivity.inventory.internal.entity.SupplierStockSnapshotReceipt;
import com.positivity.inventory.internal.enums.SupplierHintAsOfSource;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.internal.enums.SupplierHintResolutionStatus;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.inventory.internal.repository.SupplierStockHintRepository;
import com.positivity.inventory.internal.repository.SupplierStockSnapshotChunkRepository;
import com.positivity.inventory.internal.repository.SupplierStockSnapshotReceiptRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code supplier.stockreport.updated} from {@code supplier.events.v1} into supplier
 * availability hints (CAP-322, #1312; producer #1228).
 *
 * <p>A stock report is what a <strong>vendor</strong> says about its own warehouse. It is not owned
 * stock: nothing written here enters valuation (ADR-0048) or on-hand ATP, which is guaranteed by
 * the hints living in tables no valuation or ATP query joins rather than by anyone remembering to
 * exclude them.
 *
 * <p>Consumer contract per ADR-0044 §4 and the module's other listeners: manual envelope parse,
 * {@code processed_events} idempotency (owner {@code supplier}) inside the apply transaction,
 * transient DB errors rethrown for container retry/DLQ, everything else logged and swallowed.
 * Unsupported event types still record their eventIds — the PRICAT feed shares this topic, and its
 * chunks must not walk the dispatch path again on every redelivery.
 *
 * <p>Three things this feed needs that a replica listener does not:
 *
 * <ul>
 *   <li><strong>An applied-chunk log.</strong> {@code processed_events} dedupes a redelivered
 *       event; it cannot dedupe the same chunk re-emitted under a fresh eventId. The log does, and
 *       it is also how a gap becomes visible — the feed carries no terminal event, so counting
 *       arrivals against the {@code chunkCount} every chunk states is the only completeness signal
 *       there is.
 *   <li><strong>A stale guard on {@code fetchedAt}, not on the vendor's figure.</strong>
 *       {@code snapshotAsOf} is nullable, so it cannot order two snapshots in general.
 *       {@code fetchedAt} always exists. Freshness is still judged on the vendor's figure where the
 *       vendor stated one; that is a read-time question, and this is a write-time one.
 *   <li><strong>Absence is not zero.</strong> A snapshot that omits an article the vendor reported
 *       before does not touch that row: the previous hint stands with its own {@code asOf}, and its
 *       age is what tells a caller how much to trust it. Zeroing on omission would turn a vendor's
 *       silence into an out-of-stock, which is the single thing this design exists to prevent.
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class SupplierStockHintEventsListener {

    /** Producing domain, per the repo-wide processed_events convention. */
    static final String OWNER = "supplier";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final SupplierStockHintRepository hintRepository;
    private final SupplierStockSnapshotReceiptRepository receiptRepository;
    private final SupplierStockSnapshotChunkRepository chunkRepository;

    public SupplierStockHintEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            SupplierStockHintRepository hintRepository,
            SupplierStockSnapshotReceiptRepository receiptRepository,
            SupplierStockSnapshotChunkRepository chunkRepository) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.hintRepository = hintRepository;
        this.receiptRepository = receiptRepository;
        this.chunkRepository = chunkRepository;
    }

    @KafkaListener(
            topics = "${pos.inventory.kafka.supplier-events-topic:supplier.events.v1}",
            groupId = "${pos.inventory.kafka.supplier-events-consumer-group:pos-inventory-supplier-events}")
    @Transactional
    public void onSupplierEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable supplier event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping supplier event without eventId: type={}", eventType);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (SupplierStockReportUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyStockReportChunk(envelope, eventId);
            } else {
                // The PRICAT feed shares this topic and is pos-catalog's to apply. Recording the
                // eventId keeps a redelivery from re-walking this path.
                log.debug("Ignoring supplier event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            log.error("Rejected malformed supplier stock-report payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed supplier event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyStockReportChunk(JsonNode envelope, String eventId) {
        SupplierStockReportUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), SupplierStockReportUpdatedV1.class);

        if (chunkRepository.existsBySnapshotIdAndChunkSequence(payload.snapshotId(), payload.chunkSequence())) {
            // A re-emit under a fresh eventId: already applied, and applying it again would only
            // move fetchedAt-equal rows around.
            log.debug(
                    "Skipping already-applied stock-report chunk snapshotId={} chunk={}/{} eventId={}",
                    payload.snapshotId(),
                    payload.chunkSequence(),
                    payload.chunkCount(),
                    eventId);
            return;
        }

        Instant now = Instant.now(clock);
        SupplierHintAsOfSource asOfSource =
                payload.snapshotAsOf() != null ? SupplierHintAsOfSource.VENDOR : SupplierHintAsOfSource.FETCH;

        int applied = 0;
        int superseded = 0;
        int withoutIdentity = 0;
        for (SupplierStockReportLine line : payload.lines()) {
            SupplierHintIdentityKind kind = electIdentityKind(line);
            if (kind == null) {
                // The codec rejects an identity-less line upstream; defensive only.
                withoutIdentity++;
                continue;
            }
            String identityValue = identityValue(line, kind);
            Optional<SupplierStockHint> existing = hintRepository.findByVendorProfileIdAndIdentityKindAndIdentityValue(
                    payload.vendorProfileId(), kind, identityValue);
            if (existing.isPresent() && existing.get().getFetchedAt().isAfter(payload.fetchedAt())) {
                // A chunk of an older snapshot arriving after a newer one. Equal fetchedAt applies:
                // that is another chunk of the same snapshot, not an older statement.
                superseded++;
                continue;
            }
            hintRepository.save(merge(existing.orElse(null), payload, line, kind, identityValue, asOfSource, now));
            applied++;
        }

        recordChunk(payload, applied, now);

        if (withoutIdentity > 0 || superseded > 0) {
            log.info(
                    "Applied stock-report chunk snapshotId={} chunk={}/{} applied={} olderSnapshotSkipped={}"
                            + " withoutIdentity={}",
                    payload.snapshotId(),
                    payload.chunkSequence(),
                    payload.chunkCount(),
                    applied,
                    superseded,
                    withoutIdentity);
        }
    }

    /**
     * Elects the identifier this row is keyed on, following the producer's own precedence
     * ({@code SupplierStockReportLine.describeIdentity}): EAN, else our article code as the vendor
     * holds it, else the vendor's own code. Null only for a line stating no identity at all, which
     * the codec rejects upstream.
     */
    @Nullable
    private static SupplierHintIdentityKind electIdentityKind(SupplierStockReportLine line) {
        if (hasText(line.articleEan())) {
            return SupplierHintIdentityKind.EAN;
        }
        if (hasText(line.buyersArticleId())) {
            return SupplierHintIdentityKind.BUYER_ARTICLE;
        }
        if (hasText(line.supplierArticleCode())) {
            return SupplierHintIdentityKind.SUPPLIER_ARTICLE;
        }
        return null;
    }

    private static String identityValue(SupplierStockReportLine line, SupplierHintIdentityKind kind) {
        return switch (kind) {
            case EAN -> line.articleEan().trim();
            case BUYER_ARTICLE -> line.buyersArticleId().trim();
            case SUPPLIER_ARTICLE -> line.supplierArticleCode().trim();
        };
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private SupplierStockHint merge(
            @Nullable SupplierStockHint existing,
            SupplierStockReportUpdatedV1 payload,
            SupplierStockReportLine line,
            SupplierHintIdentityKind kind,
            String identityValue,
            SupplierHintAsOfSource asOfSource,
            Instant now) {
        SupplierStockHint hint = existing != null
                ? existing
                : SupplierStockHint.builder()
                        .vendorProfileId(payload.vendorProfileId())
                        .identityKind(kind)
                        .identityValue(identityValue)
                        .firstSeenAt(now)
                        .build();

        // Decided before any setter runs: an existing row IS the row being updated, so reading its
        // previous state after the setters would compare the new line against itself.
        SupplierHintResolutionStatus status = nextResolutionStatus(existing, line);

        hint.setArticleEan(line.articleEan());
        hint.setSupplierArticleCode(line.supplierArticleCode());
        hint.setBuyersArticleId(line.buyersArticleId());
        hint.setDescription(line.description());
        // Assigned as stated, null included: null is "the vendor listed this and said no quantity",
        // and 0 is "the vendor says none". Neither may be coerced into the other.
        hint.setAvailableQuantity(line.availableQuantity());
        hint.setSourceSnapshotId(payload.snapshotId());
        hint.setSourceChunkSequence(payload.chunkSequence());
        hint.setSupplierRef(payload.supplierRef());
        hint.setSnapshotAsOf(payload.snapshotAsOf());
        hint.setAsOfSource(asOfSource);
        hint.setFetchedAt(payload.fetchedAt());

        hint.setResolutionStatus(status);
        if (status != SupplierHintResolutionStatus.RESOLVED) {
            // The row is no longer resolved, which here means the EAN it resolved on has changed or
            // gone away. The product it used to point at is a statement about a different article,
            // so it is cleared rather than carried: a caller reading a PENDING row must not be
            // shown a product this vendor never tied to the code it is now stating.
            clearResolution(hint);
        }
        return hint;
    }

    /** Drops a resolution that no longer describes the article the row now carries. */
    private static void clearResolution(SupplierStockHint hint) {
        hint.setResolvedProductId(null);
        hint.setResolvedAt(null);
        hint.setResolvedBy(null);
    }

    /**
     * A resolved row keeps its product as long as the EAN it resolved on is unchanged; anything
     * else goes back to {@code PENDING} so the resolver retries. That ties retry cadence to the
     * vendor's own schedule: catalog may carry the product by the next snapshot, and an article
     * that has never stated an EAN has nothing deterministic to match on at all.
     */
    private static SupplierHintResolutionStatus nextResolutionStatus(
            @Nullable SupplierStockHint existing, SupplierStockReportLine line) {
        if (existing != null
                && existing.getResolutionStatus() == SupplierHintResolutionStatus.RESOLVED
                && Objects.equals(existing.getArticleEan(), line.articleEan())) {
            return SupplierHintResolutionStatus.RESOLVED;
        }
        return hasText(line.articleEan())
                ? SupplierHintResolutionStatus.PENDING
                : SupplierHintResolutionStatus.NOT_RESOLVABLE;
    }

    /** Logs the chunk and advances the snapshot's arrival state. */
    private void recordChunk(SupplierStockReportUpdatedV1 payload, int linesApplied, Instant now) {
        chunkRepository.save(SupplierStockSnapshotChunk.builder()
                .snapshotId(payload.snapshotId())
                .chunkSequence(payload.chunkSequence())
                .linesInChunk(payload.lines().size())
                .appliedAt(now)
                .build());

        SupplierStockSnapshotReceipt receipt = receiptRepository
                .findBySnapshotId(payload.snapshotId())
                .orElseGet(() -> SupplierStockSnapshotReceipt.builder()
                        .snapshotId(payload.snapshotId())
                        .vendorProfileId(payload.vendorProfileId())
                        .chunksApplied(0)
                        .linesApplied(0)
                        .complete(false)
                        .firstChunkAt(now)
                        .build());

        receipt.setSupplierRef(payload.supplierRef());
        receipt.setDocumentId(payload.documentId());
        receipt.setBuyerAccountNumber(payload.buyerAccountNumber());
        receipt.setChunkCount(payload.chunkCount());
        receipt.setLinesReported(payload.linesReported());
        receipt.setSnapshotAsOf(payload.snapshotAsOf());
        receipt.setAsOfSource(
                payload.snapshotAsOf() != null ? SupplierHintAsOfSource.VENDOR : SupplierHintAsOfSource.FETCH);
        receipt.setFetchedAt(payload.fetchedAt());
        receipt.setChunksApplied(receipt.getChunksApplied() + 1);
        receipt.setLinesApplied(receipt.getLinesApplied() + linesApplied);
        receipt.setLastChunkAt(now);
        // Incomplete is the resting state: a chunk that never arrives leaves the receipt this way
        // for good, with no timer needed to notice, and every hint from it reads as partial.
        receipt.setComplete(receipt.getChunksApplied() >= payload.chunkCount());
        receiptRepository.save(receipt);
    }
}
