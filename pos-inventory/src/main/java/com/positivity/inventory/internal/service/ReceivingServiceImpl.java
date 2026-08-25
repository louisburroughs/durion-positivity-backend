package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveLineRequest;
import com.positivity.inventory.internal.dto.receiving.ReceivingLineResponse;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryVariance;
import com.positivity.inventory.internal.entity.ReceivingLine;
import com.positivity.inventory.internal.entity.ReceivingSession;
import com.positivity.inventory.internal.enums.EntryMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.InventoryVarianceType;
import com.positivity.inventory.internal.enums.ReceivingLineStatus;
import com.positivity.inventory.internal.enums.ReceivingSessionStatus;
import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.inventory.internal.exception.PartMatchPermissionException;
import com.positivity.inventory.internal.exception.ReceivingSessionNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryVarianceRepository;
import com.positivity.inventory.internal.repository.ReceivingSessionRepository;
import com.positivity.inventory.service.ReceivingService;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReceivingServiceImpl implements ReceivingService {

    private static final String PART_MATCH_OVERRIDE_PERMISSION = "inventory:override:part-match";
    private static final UUID DEFAULT_CROSS_DOCK_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final ReceivingSessionRepository receivingSessionRepository;
    private final InventoryVarianceRepository inventoryVarianceRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final SourceDocumentResolver sourceDocumentResolver;
    private final StagingLocationResolver stagingLocationResolver;
    private final WorkorderValidationService workorderValidationService;
    private final DocumentQuantityConverter documentQuantityConverter;
    private final InventoryLotCaptureService lotCaptureService;
    private final QuantityScaleGuard quantityScaleGuard;

    @Value("${pos.inventory.receiving.cross-dock-location-id:}")
    private String configuredCrossDockLocationId;

    @Override
    public @NonNull ReceivingSessionResponse createReceivingSession(
            @NonNull CreateReceivingSessionRequest request, @NonNull String actorUserId) {

        String sourceDocumentId = request.getSourceDocumentId();
        EntryMethod entryMethod = parseEntryMethod(request.getEntryMethod());
        SourceDocumentType sourceDocumentType = detectSourceDocumentType(sourceDocumentId);

        ReceivingSession session = ReceivingSession.builder()
                .sourceDocumentId(sourceDocumentId)
                .sourceDocumentType(sourceDocumentType)
                .status(ReceivingSessionStatus.OPEN)
                .entryMethod(entryMethod)
                .createdByUserId(actorUserId)
                .build();

        List<ReceivingLine> lines = buildLinesFromDocument(sourceDocumentId, sourceDocumentType, session);
        session.setLines(lines);

        ReceivingSession saved = receivingSessionRepository.save(session);
        log.info("Created receiving session {} for source document {}", saved.getSessionId(), sourceDocumentId);
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ReceivingSessionResponse getReceivingSession(@NonNull UUID sessionId) {
        ReceivingSession session = receivingSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ReceivingSessionNotFoundException("Receiving session not found: " + sessionId));
        return mapToResponse(session);
    }

    @Override
    @NonNull
    public ReceiveItemsResponse receiveItemsIntoStaging(
            @NonNull UUID sessionId, @NonNull ReceiveItemsRequest request, @NonNull String actorUserId) {
        ReceivingSession session = resolveSessionForReceive(sessionId);

        Map<UUID, ReceivingLine> lineMap = session.getLines().stream()
                .filter(line -> line.getLineId() != null)
                .collect(Collectors.toMap(ReceivingLine::getLineId, line -> line, (left, right) -> left));

        List<InventoryVariance> variances = new ArrayList<>();
        int linesProcessed = 0;

        for (ReceiveLineRequest lineReq : request.getLines()) {
            ReceivingLine line = lineMap.get(lineReq.getLineId());
            if (line == null) {
                // A line this session does not have: the request names it, we do not invent it.
                continue;
            }
            receiveLine(session, line, lineReq, sessionId, actorUserId, variances);
            linesProcessed++;
        }

        session.setStatus(
                allLinesSettled(session) ? ReceivingSessionStatus.COMPLETED : ReceivingSessionStatus.IN_PROGRESS);
        if (receivingSessionRepository != null) {
            receivingSessionRepository.save(session);
        }

        log.info("Processed {} lines for session {}, {} variances", linesProcessed, sessionId, variances.size());
        return buildReceiveItemsResponse(session, linesProcessed, variances);
    }

    /** Receives one line into staging: quantity, lot, status, ledger entry, and any variance. */
    private void receiveLine(
            @NonNull ReceivingSession session,
            @NonNull ReceivingLine line,
            @NonNull ReceiveLineRequest lineReq,
            @NonNull UUID sessionId,
            @NonNull String actorUserId,
            @NonNull List<InventoryVariance> variances) {
        // odoo-parity B2 (#1034): an optional document UoM converts to base BEFORE the
        // expected-vs-received comparison and the ledger posting; keyed values are kept
        // on the line for audit.
        DocumentQuantityConverter.DocumentConversion conversion = documentQuantityConverter
                .convertIfPresent(
                        parseProductId(line.getProductId()),
                        line.getProductId(),
                        lineReq.getDocumentUom(),
                        lineReq.getDocumentQuantity())
                .orElse(null);
        BigDecimal receivedQty = conversion != null ? conversion.baseQuantity() : lineReq.getReceivedQuantity();
        if (receivedQty == null) {
            throw new IllegalArgumentException(
                    "receivedQuantity is required when documentUom/documentQuantity are absent");
        }
        BigDecimal expectedQty = line.getExpectedQuantity();

        // odoo-parity E1 (#1038): LOT-tracked products require a lotNumber (422
        // LOT_NUMBER_REQUIRED) and find-or-create the lot; untracked products pass
        // through with a null lot, byte-identical to pre-E1 behavior. odoo-parity E3
        // (#1047): an optional line expirationDate is stamped on first lot creation.
        UUID lotId = lotCaptureService.resolveReceiptLot(
                line.getProductId(),
                lineReq.getLotNumber(),
                parseSupplierVendorId(session.getSupplierId()),
                lineReq.getExpirationDate());

        line.setReceivedQuantity(receivedQty);
        line.setLotNumber(lineReq.getLotNumber());
        line.setDocumentUom(conversion == null ? null : conversion.documentUom());
        line.setDocumentQuantity(conversion == null ? null : conversion.documentQuantity());
        line.setConversionFactor(conversion == null ? null : conversion.conversionFactor());

        int cmp = receivedQty.compareTo(expectedQty);
        line.setStatus(statusFor(cmp));

        createGoodsReceiptLedgerEntry(
                sessionId,
                line.getLineId(),
                line.getProductId(),
                receivedQty,
                lotId,
                lineReq.getSerialNumbers(),
                actorUserId);

        if (cmp != 0) {
            variances.add(recordVariance(session, line, expectedQty, receivedQty, cmp, actorUserId));
        }
    }

    /** Received exactly what was expected, less, or more. */
    @NonNull
    private static ReceivingLineStatus statusFor(int expectedVsReceived) {
        if (expectedVsReceived == 0) {
            return ReceivingLineStatus.RECEIVED;
        }
        return expectedVsReceived < 0 ? ReceivingLineStatus.RECEIVED_SHORT : ReceivingLineStatus.RECEIVED_OVER;
    }

    /** Records the shortage or overage a non-matching receipt produced. */
    @NonNull
    private InventoryVariance recordVariance(
            @NonNull ReceivingSession session,
            @NonNull ReceivingLine line,
            @NonNull BigDecimal expectedQty,
            @NonNull BigDecimal receivedQty,
            int expectedVsReceived,
            @NonNull String actorUserId) {
        InventoryVarianceType varianceType =
                expectedVsReceived < 0 ? InventoryVarianceType.SHORTAGE : InventoryVarianceType.OVERAGE;
        return inventoryVarianceRepository.save(InventoryVariance.builder()
                .session(session)
                .line(line)
                .productId(line.getProductId())
                .varianceType(varianceType)
                .varianceQuantity(expectedQty.subtract(receivedQty).abs())
                .expectedQuantity(expectedQty)
                .receivedQuantity(receivedQty)
                .recordedByUserId(actorUserId)
                .build());
    }

    /**
     * Whether every line has reached a terminal state.
     *
     * <p>CANCELLED counts as settled: a cancelled line is never going to be received, so waiting
     * on it would leave the session IN_PROGRESS forever.
     */
    private static boolean allLinesSettled(@NonNull ReceivingSession session) {
        return session.getLines().stream()
                .allMatch(line -> line.getStatus() == ReceivingLineStatus.RECEIVED
                        || line.getStatus() == ReceivingLineStatus.RECEIVED_SHORT
                        || line.getStatus() == ReceivingLineStatus.RECEIVED_OVER
                        || line.getStatus() == ReceivingLineStatus.CANCELLED);
    }

    @Override
    @NonNull
    public CrossDockResponse crossDockLineToWorkorder(
            @NonNull UUID sessionId,
            @NonNull UUID lineId,
            @NonNull CrossDockRequest request,
            @NonNull String actorUserId) {
        ReceivingSession session = receivingSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ReceivingSessionNotFoundException("Receiving session not found: " + sessionId));

        ReceivingLine line = session.getLines().stream()
                .filter(candidate -> lineId.equals(candidate.getLineId()))
                .findFirst()
                .orElseThrow(
                        () -> new ReceivingSessionNotFoundException("Receiving line not found in session: " + lineId));

        String workorderId = request.getWorkorderId();
        WorkorderValidationService.WorkorderLineValidation workorderValidation =
                workorderValidationService.getWorkorderLineValidation(workorderId, request.getWorkorderLineId());
        if (isClosedWorkorderStatus(workorderValidation.status())) {
            throw new WorkorderClosedException("Cannot issue parts to a closed workorder: " + workorderId);
        }
        validatePartMatchOrOverride(
                line, actorUserId, request.getWorkorderLineId(), workorderValidation.demandedProductId());

        BigDecimal quantityDelta = toLedgerQuantity(line.getProductId(), request.getQuantity(), "quantity");
        BigDecimal existingReceivedQuantity =
                line.getReceivedQuantity() != null ? line.getReceivedQuantity() : BigDecimal.ZERO;
        BigDecimal expectedQuantity = line.getExpectedQuantity() != null ? line.getExpectedQuantity() : BigDecimal.ZERO;
        BigDecimal cumulativeReceivedQuantity = existingReceivedQuantity.add(request.getQuantity());
        if (cumulativeReceivedQuantity.compareTo(expectedQuantity) > 0) {
            throw new IllegalArgumentException("Cross-dock quantity exceeds expected quantity for line "
                    + lineId
                    + " (expected="
                    + expectedQuantity
                    + ", currentReceived="
                    + existingReceivedQuantity
                    + ", requested="
                    + request.getQuantity()
                    + ")");
        }

        UUID crossDockLocationId = resolveCrossDockLocationId();
        BigDecimal receiptQuantityAfter =
                calculateQuantityAfter(line.getProductId(), crossDockLocationId, quantityDelta);

        // odoo-parity E2 (#1042): cross-dock is lot-gated like any other receipt of a
        // LOT-tracked product (E1 deferred this because only the receipt half could have been
        // stamped, stranding phantom per-lot on-hand). The lot number comes from the request,
        // falling back to one already keyed on the receiving line, and the lot is
        // found-or-created (inbound semantics — this receipt IS the lot's first sighting).
        // BOTH paired entries carry the same lotId, so the per-lot row nets to zero and the
        // funnel's status reconciler marks the lot CONSUMED — stock went straight to the
        // workorder. Untracked products resolve to null, byte-identical to pre-E2.
        String effectiveLotNumber =
                request.getLotNumber() != null && !request.getLotNumber().isBlank()
                        ? request.getLotNumber()
                        : line.getLotNumber();
        UUID lotId = lotCaptureService.resolveReceiptLot(
                line.getProductId(), effectiveLotNumber, parseSupplierVendorId(session.getSupplierId()));

        InventoryLedgerEntry receiptEntry = InventoryLedgerEntry.builder()
                .stockItemId(line.getProductId())
                .locationId(crossDockLocationId)
                .toLocationId(crossDockLocationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantityDelta)
                .quantityAfter(receiptQuantityAfter)
                .lotId(lotId)
                .transactionUserId(actorUserId)
                .sourceTransactionId(sessionId.toString())
                .notes("Cross-dock GOODS_RECEIPT for workorder " + workorderId)
                .build();

        InventoryLedgerEntry savedReceiptEntry = ledgerPostingService.post(receiptEntry);
        inventoryFactPublisher.markEntry(savedReceiptEntry);
        BigDecimal issueQuantityAfter =
                calculateQuantityAfter(line.getProductId(), crossDockLocationId, quantityDelta.negate());

        InventoryLedgerEntry issueEntry = InventoryLedgerEntry.builder()
                .stockItemId(line.getProductId())
                .locationId(crossDockLocationId)
                .fromLocationId(crossDockLocationId)
                .eventType(InventoryLedgerEventType.GOODS_ISSUE)
                .changeInQuantity(quantityDelta.negate())
                .quantityAfter(issueQuantityAfter)
                .lotId(lotId)
                .transactionUserId(actorUserId)
                .sourceTransactionId(sessionId.toString())
                .notes("Cross-dock GOODS_ISSUE to workorder " + workorderId)
                .build();

        InventoryLedgerEntry savedIssueEntry = ledgerPostingService.post(issueEntry);
        inventoryFactPublisher.markEntry(savedIssueEntry);

        line.setWorkorderId(workorderId);
        line.setWorkorderLineId(request.getWorkorderLineId());
        line.setReceivedQuantity(cumulativeReceivedQuantity);
        if (lotId != null && effectiveLotNumber != null) {
            line.setLotNumber(effectiveLotNumber.trim());
        }

        int quantityCompare = cumulativeReceivedQuantity.compareTo(expectedQuantity);
        if (quantityCompare == 0) {
            line.setStatus(ReceivingLineStatus.RECEIVED);
        } else if (quantityCompare < 0) {
            line.setStatus(ReceivingLineStatus.RECEIVED_SHORT);
        } else {
            line.setStatus(ReceivingLineStatus.RECEIVED_OVER);
        }

        boolean allReceived = session.getLines().stream()
                .allMatch(existingLine -> existingLine.getStatus() == ReceivingLineStatus.RECEIVED
                        || existingLine.getStatus() == ReceivingLineStatus.RECEIVED_SHORT
                        || existingLine.getStatus() == ReceivingLineStatus.RECEIVED_OVER
                        || existingLine.getStatus() == ReceivingLineStatus.CANCELLED);
        if (allReceived) {
            session.setStatus(ReceivingSessionStatus.COMPLETED);
        } else {
            session.setStatus(ReceivingSessionStatus.IN_PROGRESS);
        }

        receivingSessionRepository.save(session);

        List<String> ledgerEntryIds = new ArrayList<>();
        if (savedReceiptEntry != null && savedReceiptEntry.getLedgerEntryId() != null) {
            ledgerEntryIds.add(savedReceiptEntry.getLedgerEntryId().toString());
        }
        if (savedIssueEntry != null && savedIssueEntry.getLedgerEntryId() != null) {
            ledgerEntryIds.add(savedIssueEntry.getLedgerEntryId().toString());
        }

        return CrossDockResponse.builder()
                .lineId(lineId)
                .workorderId(workorderId)
                .workorderLineId(request.getWorkorderLineId())
                .crossDockedQuantity(request.getQuantity())
                .unitOfMeasure(line.getDocumentUom())
                .sessionStatus(session.getStatus().name())
                .lineStatus(line.getStatus().name())
                .ledgerEntryIds(ledgerEntryIds)
                .build();
    }

    private ReceivingSession resolveSessionForReceive(UUID sessionId) {
        var sessionOptional = receivingSessionRepository.findById(sessionId);
        if (sessionOptional.isPresent()) {
            return sessionOptional.get();
        }

        throw new ReceivingSessionNotFoundException("Receiving session not found: " + sessionId);
    }

    private void createGoodsReceiptLedgerEntry(
            UUID sessionId,
            UUID lineId,
            String productId,
            BigDecimal quantity,
            UUID lotId,
            java.util.List<String> serialNumbers,
            String actorUserId) {
        BigDecimal quantityDelta = toLedgerQuantity(productId, quantity, "receivedQuantity");
        UUID stagingLocationId = stagingLocationResolver.resolveStagingLocationId();
        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(productId)
                .locationId(stagingLocationId)
                .toLocationId(stagingLocationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantityDelta)
                .quantityAfter(calculateQuantityAfter(productId, stagingLocationId, quantityDelta))
                .lotId(lotId)
                // odoo-parity E4 (#1050): the funnel enumerates these serials for SERIAL-tracked
                // products (422 SERIAL_COUNT_MISMATCH if the count != received qty); ignored otherwise.
                .serialNumbers(serialNumbers == null ? java.util.List.of() : serialNumbers)
                .transactionUserId(actorUserId)
                .sourceTransactionId(sessionId + ":" + lineId)
                .notes("Receiving session " + sessionId + " line " + lineId)
                .build();

        ledgerPostingService.post(entry);
        inventoryFactPublisher.markEntry(entry);
    }

    /**
     * Parses the session's free-text supplier reference as a vendor UUID for lot stamping
     * (odoo-parity E1, #1038); null when the reference is absent or not a UUID.
     */
    private UUID parseSupplierVendorId(String supplierId) {
        if (supplierId == null || supplierId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(supplierId.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The base quantity a receipt may post, checked against the product's declared divisibility
     * (ADR-0055, #1414).
     *
     * <p>This replaces an {@code intValueExact()} that refused every fraction for every product.
     * That refusal was right for the whole catalog as seeded and would have been wrong for the
     * first product to declare {@code precision_scale > 0} — the conversion subsystem already
     * produces fractional base quantities for such a product (its scale-2 fixture asserts
     * {@code 1 BAG -> 1.01 LB}), and the ledger would have refused to hold what the conversion
     * computed. The guard is still fail-closed; what changed is that the rule is read from the
     * catalog rather than hardcoded, so a product declaring nothing is refused a fraction exactly
     * as before.
     */
    private BigDecimal toLedgerQuantity(String stockItemId, BigDecimal quantity, String fieldName) {
        if (quantity == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return quantityScaleGuard.requirePostable(
                parseProductId(stockItemId), String.valueOf(stockItemId), fieldName, quantity);
    }

    /**
     * Parses a receiving line's free-text productId as a catalog product UUID; {@code null} when
     * it does not parse (a document UoM on such a line raises {@code UOM_CONVERSION_UNDEFINED} —
     * odoo-parity B2, #1034).
     */
    private UUID parseProductId(String productId) {
        if (productId == null || productId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(productId.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private BigDecimal calculateQuantityAfter(String stockItemId, UUID locationId, BigDecimal quantityDelta) {
        BigDecimal currentOnHand =
                inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(stockItemId, locationId);
        return Quantities.nz(currentOnHand).add(quantityDelta);
    }

    private UUID resolveCrossDockLocationId() {
        return resolveLocationId(
                configuredCrossDockLocationId,
                DEFAULT_CROSS_DOCK_LOCATION_ID,
                "pos.inventory.receiving.cross-dock-location-id");
    }

    private UUID resolveLocationId(String configuredValue, UUID defaultValue, String propertyName) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return defaultValue;
        }
        try {
            return UUID.fromString(configuredValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(propertyName + " must be a valid UUID: " + configuredValue, ex);
        }
    }

    private void validatePartMatchOrOverride(
            ReceivingLine line, String actorUserId, String workorderLineId, String demandedProductId) {
        if (workorderLineId == null || workorderLineId.isBlank()) {
            throw new IllegalArgumentException("workorderLineId is required");
        }
        if (demandedProductId == null || demandedProductId.isBlank()) {
            throw new IllegalStateException("Workorder line " + workorderLineId + " is missing demanded product");
        }

        if (isSameProduct(line.getProductId(), demandedProductId)) {
            return;
        }

        if (SecurityContextHelper.hasAuthority(PART_MATCH_OVERRIDE_PERMISSION)) {
            log.warn(
                    "Part mismatch override accepted for workorderLineId={} by actor={} (requiredPermission={})",
                    workorderLineId,
                    actorUserId,
                    PART_MATCH_OVERRIDE_PERMISSION);
            return;
        }

        throw new PartMatchPermissionException(String.format(
                "PART_MISMATCH_WITH_WORKORDER: received product %s does not match demanded product %s for workorderLineId %s. Required permission: %s",
                line.getProductId(), demandedProductId, workorderLineId, PART_MATCH_OVERRIDE_PERMISSION));
    }

    private boolean isClosedWorkorderStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        return "COMPLETED".equals(normalizedStatus)
                || "CANCELLED".equals(normalizedStatus)
                || "CLOSED".equals(normalizedStatus);
    }

    private boolean isSameProduct(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private ReceiveItemsResponse buildReceiveItemsResponse(
            ReceivingSession session, int linesProcessed, List<InventoryVariance> variances) {
        List<ReceiveItemsResponse.VarianceSummaryResponse> varianceSummaries = variances.stream()
                .map(variance -> ReceiveItemsResponse.VarianceSummaryResponse.builder()
                        .lineId(
                                variance.getLine() == null
                                        ? null
                                        : variance.getLine().getLineId())
                        .productId(variance.getProductId())
                        .varianceType(variance.getVarianceType().name())
                        .varianceQuantity(variance.getVarianceQuantity())
                        .expectedQuantity(variance.getExpectedQuantity())
                        .receivedQuantity(variance.getReceivedQuantity())
                        .unitOfMeasure(
                                variance.getLine() == null
                                        ? null
                                        : variance.getLine().getDocumentUom())
                        .build())
                .toList();

        return ReceiveItemsResponse.builder()
                .sessionId(session.getSessionId())
                .sessionStatus(session.getStatus().name())
                .linesProcessed(linesProcessed)
                .variances(varianceSummaries)
                .build();
    }

    private EntryMethod parseEntryMethod(String entryMethodStr) {
        if (entryMethodStr == null || entryMethodStr.isBlank()) {
            return EntryMethod.MANUAL;
        }

        try {
            return EntryMethod.valueOf(entryMethodStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid entry method: " + entryMethodStr, ex);
        }
    }

    private SourceDocumentType detectSourceDocumentType(String sourceDocumentId) {
        if (sourceDocumentId != null
                && sourceDocumentId.toUpperCase(Locale.ROOT).startsWith("ASN")) {
            return SourceDocumentType.ASN;
        }
        return SourceDocumentType.PO;
    }

    private List<ReceivingLine> buildLinesFromDocument(
            String sourceDocumentId, SourceDocumentType sourceDocumentType, ReceivingSession session) {
        List<SourceDocumentResolver.SourceDocumentLine> sourceLines =
                fetchSourceDocumentLines(sourceDocumentId, sourceDocumentType);
        List<ReceivingLine> lines = new ArrayList<>(sourceLines.size());

        for (SourceDocumentResolver.SourceDocumentLine sourceLine : sourceLines) {
            // expectedQuantity is the base still-open quantity; the document unit and quantity are
            // recorded per receive, from the receive request (odoo-parity B2, #1034).
            lines.add(ReceivingLine.builder()
                    .session(session)
                    .productId(sourceLine.getProductId())
                    .expectedQuantity(sourceLine.getExpectedQuantity())
                    .receivedQuantity(BigDecimal.ZERO)
                    .status(ReceivingLineStatus.EXPECTED)
                    .build());
        }

        log.debug("Generated {} receiving lines for source document {}", lines.size(), sourceDocumentId);
        return lines;
    }

    /**
     * Resolves the source document's still-expected lines from the projected purchase order
     * (#1480). No stub, and no config flag whose default answers 404 — and no synchronous call to
     * pos-order either, which ADR-0044's domain wall forbids and which the projection makes
     * unnecessary.
     */
    private List<SourceDocumentResolver.SourceDocumentLine> fetchSourceDocumentLines(
            String sourceDocumentId, SourceDocumentType sourceDocumentType) {
        log.info(
                "Resolving source document {} {} from the purchase-order projection",
                sourceDocumentType,
                sourceDocumentId);

        SourceDocumentResolver.SourceDocument sourceDocument =
                sourceDocumentResolver.resolve(sourceDocumentType, sourceDocumentId);

        List<SourceDocumentResolver.SourceDocumentLine> lines = sourceDocument.lines();
        for (int i = 0; i < lines.size(); i++) {
            requireUsableLine(lines.get(i), sourceDocumentId, i + 1);
        }
        return lines;
    }

    private void requireUsableLine(
            SourceDocumentResolver.SourceDocumentLine line, String sourceDocumentId, int lineNumber) {
        String productId = line.getProductId();
        if (productId == null || productId.isBlank()) {
            throw new IllegalStateException("Invalid source document line " + lineNumber + " for " + sourceDocumentId
                    + ": productId is required");
        }

        BigDecimal expectedQuantity = line.getExpectedQuantity();
        if (expectedQuantity == null || expectedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Invalid source document line " + lineNumber + " for " + sourceDocumentId
                    + ": expectedQuantity must be greater than 0");
        }
    }

    private ReceivingSessionResponse mapToResponse(ReceivingSession session) {
        List<ReceivingLineResponse> lineResponses =
                session.getLines().stream().map(this::mapLineToResponse).toList();

        return ReceivingSessionResponse.builder()
                .sessionId(session.getSessionId())
                .sourceDocumentId(session.getSourceDocumentId())
                .sourceDocumentType(
                        session.getSourceDocumentType() != null
                                ? session.getSourceDocumentType().name()
                                : null)
                .supplierId(session.getSupplierId())
                .shipmentReference(session.getShipmentReference())
                .status(session.getStatus() != null ? session.getStatus().name() : null)
                .entryMethod(
                        session.getEntryMethod() != null
                                ? session.getEntryMethod().name()
                                : null)
                .createdByUserId(session.getCreatedByUserId())
                .createdAt(session.getCreatedAt())
                .lines(lineResponses)
                .build();
    }

    private ReceivingLineResponse mapLineToResponse(ReceivingLine line) {
        return ReceivingLineResponse.builder()
                .lineId(line.getLineId())
                .productId(line.getProductId())
                .expectedQuantity(line.getExpectedQuantity())
                .receivedQuantity(line.getReceivedQuantity())
                .status(line.getStatus() != null ? line.getStatus().name() : null)
                .workorderId(line.getWorkorderId())
                .workorderLineId(line.getWorkorderLineId())
                .lotNumber(line.getLotNumber())
                .documentUom(line.getDocumentUom())
                .documentQuantity(line.getDocumentQuantity())
                .conversionFactor(line.getConversionFactor())
                .build();
    }
}
