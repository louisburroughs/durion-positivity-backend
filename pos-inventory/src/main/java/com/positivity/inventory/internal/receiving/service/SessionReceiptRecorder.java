package com.positivity.inventory.internal.receiving.service;

import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.GoodsReceiptLineEntity;
import com.positivity.inventory.internal.entity.ReceivingLine;
import com.positivity.inventory.internal.entity.ReceivingSession;
import com.positivity.inventory.internal.exception.IdempotencyConflictException;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.service.DocumentQuantityConverter;
import com.positivity.inventory.internal.service.GoodsReceiptFactPublisher;
import com.positivity.inventory.internal.service.GoodsReceiptFactory;
import com.positivity.inventory.internal.service.SourceDocumentResolver;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a receiving session's receive and cross-dock calls into goods-receipt documents and makes
 * those calls idempotent (#2455).
 *
 * <h2>One receipt per call</h2>
 *
 * Each call that received something against a purchase order is recorded as one
 * {@link GoodsReceiptEntity}, with one {@link GoodsReceiptLineEntity} per received request line, so
 * session receipts show up in goods-receipt reads, putaway and traceability like any other. The
 * {@code goodsreceipt.recorded} fact is published from that row.
 *
 * <h2>Idempotency</h2>
 *
 * The receipt row doubles as the replay record. It stores the caller's key, the operation scope
 * (a session's receive, or the cross-dock of one line), a fingerprint of the request, and the
 * response that was returned. A retry with the same key and request finds the row and returns that
 * response without posting anything; the same key on a different request is a conflict. The
 * receipt id and the event id are derived from the key, so a retry that did reach the outbox would
 * carry the id pos-order already de-duplicates on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionReceiptRecorder {

    /** Scope of a session's receive call. */
    public static final String SCOPE_RECEIVE = "RECEIVE";

    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptFactPublisher goodsReceiptFactPublisher;
    private final SourceDocumentResolver sourceDocumentResolver;
    private final ObjectMapper objectMapper;

    /** Scope of the cross-dock of one session line. */
    public static @NonNull String crossDockScope(@NonNull UUID lineId) {
        return "CROSS_DOCK:" + lineId;
    }

    /** A blank key counts as absent. */
    public static @Nullable String normalizeKey(@Nullable String key) {
        return key == null || key.isBlank() ? null : key.trim();
    }

    /**
     * What one received request line contributes to the receipt.
     *
     * @param line       the session line it was received against
     * @param quantity   base quantity this call received
     * @param conversion the document-UoM conversion applied, when one was keyed
     * @param lotNumber  the lot keyed on the request, when any
     */
    public record ReceivedLine(
            @NonNull ReceivingLine line,
            @NonNull BigDecimal quantity,
            DocumentQuantityConverter.@Nullable DocumentConversion conversion,
            @Nullable String lotNumber) {}

    /**
     * The response a prior call with this key returned, when the call has run before.
     *
     * @throws IdempotencyConflictException when the key was used for a different request
     */
    public <T> @NonNull Optional<T> findReplay(
            @NonNull UUID sessionId,
            @NonNull String scope,
            @Nullable String key,
            @NonNull String fingerprint,
            @NonNull Class<T> responseType) {
        if (key == null) {
            return Optional.empty();
        }
        return goodsReceiptRepository
                .findByReceivingSessionIdAndIdempotencyScopeAndIdempotencyKey(sessionId, scope, key)
                .map(prior -> {
                    if (!fingerprint.equals(prior.getRequestFingerprint())) {
                        throw new IdempotencyConflictException("Idempotency key '" + key
                                + "' was already used for a different request on receiving session " + sessionId);
                    }
                    log.info("Replaying {} for receiving session {} under key {}", scope, sessionId, key);
                    return objectMapper.readValue(prior.getResponseSnapshot(), responseType);
                });
    }

    /**
     * Records the call as a goods receipt and publishes {@code goodsreceipt.recorded} from it.
     * Records nothing when the call received nothing or the session has no purchase order to
     * receive against.
     */
    public void record(
            @NonNull ReceivingSession session,
            @NonNull String scope,
            @Nullable String key,
            @NonNull String fingerprint,
            @Nullable UUID locationId,
            @NonNull List<ReceivedLine> received,
            @NonNull String actorUserId,
            @NonNull Object response) {
        if (received.isEmpty() || locationId == null) {
            return;
        }
        Optional<UUID> purchaseOrderId = sourceDocumentResolver.receivingPurchaseOrderId(
                session.getSourceDocumentType(), session.getSourceDocumentId());
        if (purchaseOrderId.isEmpty()) {
            return;
        }

        UUID sessionId = session.getSessionId();
        String effectiveKey = key != null ? key : UUIDv7Generator.generate().toString();
        // Ids are ordinary UUIDv7 (ADR-0013). The receipt id is generated by @UUIDv7Id; the event id is
        // kept on the row. A retry replays the stored response and never publishes again, so the
        // event id is never reused and pos-order's event-id de-duplication stays intact.
        UUID eventId = UUIDv7Generator.generate();

        GoodsReceiptEntity receipt = GoodsReceiptEntity.builder()
                .eventId(eventId)
                .receiptNumber(GoodsReceiptFactory.newReceiptNumber())
                .purchaseOrderId(purchaseOrderId.get())
                .locationId(locationId)
                .createdBy(actorUserId)
                .receivingSessionId(sessionId)
                .idempotencyScope(scope)
                .idempotencyKey(effectiveKey)
                .requestFingerprint(fingerprint)
                .responseSnapshot(objectMapper.writeValueAsString(response))
                .build();

        List<GoodsReceiptLineEntity> lineEntities = new ArrayList<>();
        List<GoodsReceiptFactPublisher.GoodsReceiptLineFact> facts = new ArrayList<>();
        long total = 0L;
        for (ReceivedLine receivedLine : received) {
            ReceivingLine line = receivedLine.line();
            SourceDocumentResolver.ReceiptLineValue value = sourceDocumentResolver.valueReceiptLine(
                    purchaseOrderId.get(), line.getSourceLineId(), line.getProductId(), receivedLine.quantity());
            DocumentQuantityConverter.DocumentConversion conversion = receivedLine.conversion();
            lineEntities.add(GoodsReceiptLineEntity.builder()
                    .goodsReceipt(receipt)
                    .poLineId(value.poLineId())
                    .sku(line.getProductId())
                    .quantityReceived(receivedLine.quantity())
                    .unitCostMinor(unitCostMinor(value.accruedAmountMinor(), receivedLine.quantity()))
                    .lineAccruedAmountMinor(value.accruedAmountMinor())
                    .lotNumber(receivedLine.lotNumber())
                    .documentUom(conversion == null ? null : conversion.documentUom())
                    .documentQuantity(conversion == null ? null : conversion.documentQuantity())
                    .conversionFactor(conversion == null ? null : conversion.conversionFactor())
                    .receivingLineId(line.getLineId())
                    .build());
            facts.add(new GoodsReceiptFactPublisher.GoodsReceiptLineFact(
                    value.poLineId(), line.getProductId(), receivedLine.quantity(), value.accruedAmountMinor()));
            total += value.accruedAmountMinor();
        }
        receipt.setLines(lineEntities);
        receipt.setTotalAccruedAmountMinor(total);

        GoodsReceiptEntity saved = goodsReceiptRepository.save(receipt);
        goodsReceiptFactPublisher.publish(saved, facts, eventId);
    }

    private static long unitCostMinor(long accruedMinor, @NonNull BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(accruedMinor)
                .divide(quantity, 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /** Canonical form of a receive request: normalized values in a fixed structure, so no two requests share one. */
    private record ReceiveFingerprint(String operation, List<ReceiveLineFingerprint> lines) {}

    private record ReceiveLineFingerprint(
            UUID lineId,
            String receivedQuantity,
            String lotNumber,
            String documentUom,
            String documentQuantity,
            String expirationDate,
            List<String> serialNumbers) {}

    private record CrossDockFingerprint(
            String operation,
            String workorderId,
            String workorderLineId,
            String quantity,
            String lotNumber,
            String notes) {}

    /** Fingerprint of a receive request: SHA-256 of its structured JSON, unambiguous for any field content. */
    public @NonNull String fingerprintReceive(@NonNull ReceiveItemsRequest request) {
        List<ReceiveLineFingerprint> lines = request.getLines().stream()
                .map(line -> new ReceiveLineFingerprint(
                        line.getLineId(),
                        plain(line.getReceivedQuantity()),
                        line.getLotNumber(),
                        line.getDocumentUom(),
                        plain(line.getDocumentQuantity()),
                        line.getExpirationDate() == null
                                ? null
                                : line.getExpirationDate().toString(),
                        line.getSerialNumbers()))
                .toList();
        return fingerprint(objectMapper.writeValueAsString(new ReceiveFingerprint("receive", lines)));
    }

    /** Fingerprint of a cross-dock request, built the same way. */
    public @NonNull String fingerprintCrossDock(@NonNull CrossDockRequest request) {
        return fingerprint(objectMapper.writeValueAsString(new CrossDockFingerprint(
                "cross-dock",
                request.getWorkorderId(),
                request.getWorkorderLineId(),
                plain(request.getQuantity()),
                request.getLotNumber(),
                request.getNotes())));
    }

    private static @Nullable String plain(@Nullable BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** SHA-256 of the canonical text of a request, hex encoded. */
    public static @NonNull String fingerprint(@NonNull String canonicalRequest) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
