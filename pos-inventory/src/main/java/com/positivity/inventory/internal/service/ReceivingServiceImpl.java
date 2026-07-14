package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.client.SourceDocumentStubClient;
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
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryVarianceRepository;
import com.positivity.inventory.internal.repository.ReceivingSessionRepository;
import com.positivity.inventory.service.ReceivingService;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReceivingServiceImpl implements ReceivingService {

    private static final String PART_MATCH_OVERRIDE_PERMISSION = "inventory:override:part-match";
    private static final UUID DEFAULT_STAGING_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DEFAULT_CROSS_DOCK_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final ReceivingSessionRepository receivingSessionRepository;
    private final InventoryVarianceRepository inventoryVarianceRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final SourceDocumentStubClient sourceDocumentStubClient;
    private final SiteDefaultsService siteDefaultsService;
    private final WorkorderValidationService workorderValidationService;

    @Value("${pos.inventory.receiving.source-document-service:}")
    private String configuredSourceDocumentService;

    @Value("${pos.inventory.receiving.site-id:}")
    private String configuredSiteId;

    @Value("${pos.inventory.receiving.staging-location-id:}")
    private String configuredStagingLocationId;

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
                continue;
            }

            BigDecimal receivedQty = lineReq.getReceivedQuantity();
            BigDecimal expectedQty = line.getExpectedQuantity();

            line.setReceivedQuantity(receivedQty);
            int cmp = receivedQty.compareTo(expectedQty);
            if (cmp == 0) {
                line.setStatus(ReceivingLineStatus.RECEIVED);
            } else if (cmp < 0) {
                line.setStatus(ReceivingLineStatus.RECEIVED_SHORT);
            } else {
                line.setStatus(ReceivingLineStatus.RECEIVED_OVER);
            }

            createGoodsReceiptLedgerEntry(sessionId, line.getLineId(), line.getProductId(), receivedQty, actorUserId);

            if (cmp != 0) {
                InventoryVarianceType varianceType =
                        cmp < 0 ? InventoryVarianceType.SHORTAGE : InventoryVarianceType.OVERAGE;
                BigDecimal varianceQty = expectedQty.subtract(receivedQty).abs();

                InventoryVariance variance = InventoryVariance.builder()
                        .session(session)
                        .line(line)
                        .productId(line.getProductId())
                        .varianceType(varianceType)
                        .varianceQuantity(varianceQty)
                        .expectedQuantity(expectedQty)
                        .receivedQuantity(receivedQty)
                        .recordedByUserId(actorUserId)
                        .build();

                variances.add(inventoryVarianceRepository.save(variance));
            }

            linesProcessed++;
        }

        boolean allReceived = session.getLines().stream()
                .allMatch(line -> line.getStatus() == ReceivingLineStatus.RECEIVED
                        || line.getStatus() == ReceivingLineStatus.RECEIVED_SHORT
                        || line.getStatus() == ReceivingLineStatus.RECEIVED_OVER
                        || line.getStatus() == ReceivingLineStatus.CANCELLED);

        if (allReceived) {
            session.setStatus(ReceivingSessionStatus.COMPLETED);
        } else {
            session.setStatus(ReceivingSessionStatus.IN_PROGRESS);
        }

        if (receivingSessionRepository != null) {
            receivingSessionRepository.save(session);
        }

        log.info("Processed {} lines for session {}, {} variances", linesProcessed, sessionId, variances.size());
        return buildReceiveItemsResponse(session, linesProcessed, variances);
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

        int quantityDelta = toWholeLedgerQuantity(request.getQuantity(), "quantity");
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
        int receiptQuantityAfter = calculateQuantityAfter(line.getProductId(), crossDockLocationId, quantityDelta);

        InventoryLedgerEntry receiptEntry = InventoryLedgerEntry.builder()
                .stockItemId(line.getProductId())
                .locationId(crossDockLocationId)
                .toLocationId(crossDockLocationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantityDelta)
                .quantityAfter(receiptQuantityAfter)
                .transactionUserId(actorUserId)
                .sourceTransactionId(sessionId.toString())
                .notes("Cross-dock GOODS_RECEIPT for workorder " + workorderId)
                .build();

        InventoryLedgerEntry savedReceiptEntry = inventoryLedgerEntryRepository.save(receiptEntry);
        inventoryFactPublisher.markEntry(savedReceiptEntry);
        int issueQuantityAfter = calculateQuantityAfter(line.getProductId(), crossDockLocationId, -quantityDelta);

        InventoryLedgerEntry issueEntry = InventoryLedgerEntry.builder()
                .stockItemId(line.getProductId())
                .locationId(crossDockLocationId)
                .fromLocationId(crossDockLocationId)
                .eventType(InventoryLedgerEventType.GOODS_ISSUE)
                .changeInQuantity(-quantityDelta)
                .quantityAfter(issueQuantityAfter)
                .transactionUserId(actorUserId)
                .sourceTransactionId(sessionId.toString())
                .notes("Cross-dock GOODS_ISSUE to workorder " + workorderId)
                .build();

        InventoryLedgerEntry savedIssueEntry = inventoryLedgerEntryRepository.save(issueEntry);
        inventoryFactPublisher.markEntry(savedIssueEntry);

        line.setWorkorderId(workorderId);
        line.setWorkorderLineId(request.getWorkorderLineId());
        line.setReceivedQuantity(cumulativeReceivedQuantity);

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
            UUID sessionId, UUID lineId, String productId, BigDecimal quantity, String actorUserId) {
        int quantityDelta = toWholeLedgerQuantity(quantity, "receivedQuantity");
        UUID stagingLocationId = resolveStagingLocationId();
        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(productId)
                .locationId(stagingLocationId)
                .toLocationId(stagingLocationId)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(quantityDelta)
                .quantityAfter(calculateQuantityAfter(productId, stagingLocationId, quantityDelta))
                .transactionUserId(actorUserId)
                .sourceTransactionId(sessionId + ":" + lineId)
                .notes("Receiving session " + sessionId + " line " + lineId)
                .build();

        inventoryLedgerEntryRepository.save(entry);
        inventoryFactPublisher.markEntry(entry);
    }

    private int toWholeLedgerQuantity(BigDecimal quantity, String fieldName) {
        if (quantity == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        try {
            return quantity.intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(fieldName + " must be a whole number within 32-bit integer range", ex);
        }
    }

    private int calculateQuantityAfter(String stockItemId, UUID locationId, int quantityDelta) {
        Integer currentOnHand =
                inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(stockItemId, locationId);
        int onHand = currentOnHand != null ? currentOnHand : 0;
        return onHand + quantityDelta;
    }

    private UUID resolveStagingLocationId() {
        UUID fallbackStagingLocationId = resolveLocationId(
                configuredStagingLocationId,
                DEFAULT_STAGING_LOCATION_ID,
                "pos.inventory.receiving.staging-location-id");

        UUID siteId = resolveRequestScopedSiteId().orElseGet(this::resolveConfiguredSiteId);
        if (siteId == null) {
            return fallbackStagingLocationId;
        }

        return siteDefaultsService.getDefaultStagingLocationId(siteId).orElse(fallbackStagingLocationId);
    }

    private Optional<UUID> resolveRequestScopedSiteId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes requestAttributes)) {
            return Optional.empty();
        }

        HttpServletRequest request = requestAttributes.getRequest();
        Optional<UUID> fromHeader = parseSiteId(request.getHeader("X-Site-Id"));
        if (fromHeader.isPresent()) {
            return fromHeader;
        }

        Object uriVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (uriVars instanceof Map<?, ?> values) {
            Object value = values.get("siteId");
            if (value instanceof String siteIdValue) {
                return parseSiteId(siteIdValue);
            }
        }

        return Optional.empty();
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

    private UUID resolveConfiguredSiteId() {
        if (configuredSiteId == null || configuredSiteId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(configuredSiteId.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "pos.inventory.receiving.site-id must be a valid UUID: " + configuredSiteId, ex);
        }
    }

    private Optional<UUID> parseSiteId(String rawSiteId) {
        if (rawSiteId == null || rawSiteId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(rawSiteId.trim()));
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring invalid request-scoped siteId value: {}", rawSiteId);
            return Optional.empty();
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
        List<SourceDocumentLineStub> sourceLines = fetchSourceDocumentLines(sourceDocumentId, sourceDocumentType);
        List<ReceivingLine> lines = new ArrayList<>(sourceLines.size());

        for (SourceDocumentLineStub sourceLine : sourceLines) {
            lines.add(ReceivingLine.builder()
                    .session(session)
                    .productId(sourceLine.productId())
                    .expectedQuantity(sourceLine.expectedQuantity())
                    .receivedQuantity(BigDecimal.ZERO)
                    .status(ReceivingLineStatus.EXPECTED)
                    .build());
        }

        log.debug("Generated {} receiving lines for source document {}", lines.size(), sourceDocumentId);
        return lines;
    }

    private List<SourceDocumentLineStub> fetchSourceDocumentLines(
            String sourceDocumentId, SourceDocumentType sourceDocumentType) {
        String sourceService = resolveSourceDocumentService(sourceDocumentType);

        log.info(
                "Stubbed source-document lookup via service {} for {} {}",
                sourceService,
                sourceDocumentType,
                sourceDocumentId);

        SourceDocumentStubClient.SourceDocumentLinesResponse sourceDocument =
                sourceDocumentStubClient.fetchDocument(sourceService, sourceDocumentType, sourceDocumentId);
        if (sourceDocument != null && sourceDocument.indicatesAlreadyReceived()) {
            throw new SourceDocumentAlreadyReceivedException(sourceDocumentId + " has already been fully received");
        }

        List<SourceDocumentStubClient.SourceDocumentLineDto> stubLines =
                sourceDocument != null ? sourceDocument.getLines() : null;

        if (stubLines == null || stubLines.isEmpty()) {
            throw new SourceDocumentNotFoundException("No receiving lines returned for " + sourceDocumentType + " "
                    + sourceDocumentId + " from service " + sourceService);
        }

        List<SourceDocumentLineStub> lines = new ArrayList<>(stubLines.size());
        for (int i = 0; i < stubLines.size(); i++) {
            lines.add(mapStubLine(stubLines.get(i), sourceDocumentId, i + 1));
        }
        return lines;
    }

    private SourceDocumentLineStub mapStubLine(
            SourceDocumentStubClient.SourceDocumentLineDto stubLine, String sourceDocumentId, int lineNumber) {
        String productId = stubLine != null ? stubLine.getProductId() : null;
        if (productId == null || productId.isBlank()) {
            throw new IllegalStateException("Invalid source document line " + lineNumber + " for " + sourceDocumentId
                    + ": productId is required");
        }

        // stubLine is guaranteed non-null here: the productId guard above throws when it is null (java:S2583).
        BigDecimal expectedQuantity = stubLine.getExpectedQuantity();
        if (expectedQuantity == null || expectedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Invalid source document line " + lineNumber + " for " + sourceDocumentId
                    + ": expectedQuantity must be greater than 0");
        }

        return new SourceDocumentLineStub(productId, expectedQuantity);
    }

    private String resolveSourceDocumentService(SourceDocumentType sourceDocumentType) {
        if (configuredSourceDocumentService != null && !configuredSourceDocumentService.isBlank()) {
            return configuredSourceDocumentService;
        }
        return sourceDocumentType == SourceDocumentType.ASN ? "pos-shipments" : "pos-order";
    }

    private record SourceDocumentLineStub(String productId, BigDecimal expectedQuantity) {}

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
                .build();
    }
}
