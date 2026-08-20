package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.dto.transfer.CreateTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.DispatchTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.ReceiveTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.ShortCloseTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderLineRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderLineResponse;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.dto.transfer.TransferQuantityLineRequest;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.TransferOrder;
import com.positivity.inventory.internal.entity.TransferOrderLine;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.internal.enums.TransferShortCloseDisposition;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.TransferLocationNotEligibleException;
import com.positivity.inventory.internal.exception.TransferOrderNotFoundException;
import com.positivity.inventory.internal.exception.TransferQuantityExceededException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.TransferOrderRepository;
import com.positivity.inventory.service.TransferOrderService;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of the transfer-order lifecycle (odoo-parity C1, issue #1035).
 *
 * <p>Movement eligibility (spec C5 / DECISION-INVENTORY-009): both sites must exist in the
 * {@code LocationRef} roster with status ACTIVE — INACTIVE and PENDING sites are blocked for
 * movement. An unknown site is a 404; a known-but-ineligible site is a deterministic 422
 * ({@code TRANSFER_LOCATION_NOT_ELIGIBLE}). Optional bin-level storage locations are checked
 * against the {@code ext_storage_location} replica when a row exists; ids the replica cannot
 * resolve pass through (the replica is eventually consistent and bins are advisory here — the
 * parity-C2 posting path re-validates stock physically via the negative-stock matrix).
 *
 * <p>Approval step (decision D-8): {@code pos.inventory.transfer.approval-required} — default
 * off (DRAFT dispatches directly, approve is a 409). When on, DRAFT must be approved before
 * dispatch. Approval authority rides on {@code inventory:transfer:dispatch} (the controller
 * gate): whoever may move the stock may authorize moving it.
 *
 * <p>Short-close ledger shape (parity-C3, issue #1039; spec C4). In-transit is derived purely
 * from ledger shape: {@code TRANSFER_OUT} with a {@code toLocationId} contributes at the
 * DESTINATION key, {@code TRANSFER_IN} cancels at its OWN key (C2 rule in
 * {@code LedgerPostingServiceImpl}/{@code StockSummaryRebuildServiceImpl} and the drift SQL).
 * Short-close therefore never needs new reconstruction rules — each line's remainder
 * ({@code dispatched − received}) posts one of two same-batch shapes, both of which zero the
 * destination in-transit through the existing rules:
 * <ul>
 *   <li>{@code LOST_IN_TRANSIT} — constructive {@code TRANSFER_IN} at the destination key
 *       (cancels the outstanding in-transit, briefly raises destination on-hand) immediately
 *       paired with {@code SCRAP_OUT} at the same key ({@code reasonCode = LOST}, WS-D
 *       taxonomy, latest-receipt cost snapshot). Net: destination on-hand unchanged,
 *       destination in-transit zero, GLOBAL on-hand down by the remainder. A direct
 *       "SCRAP_OUT from transit" was rejected: {@code SCRAP_OUT} carries no in-transit
 *       semantics anywhere (posting, rebuild, drift SQL), so it could only zero transit via a
 *       special-case exemption in all three places — the paired shape needs none.</li>
 *   <li>{@code RETURNED_TO_SOURCE} — constructive {@code TRANSFER_IN} at the destination,
 *       {@code TRANSFER_OUT} destination→source, {@code TRANSFER_IN} at the source (the
 *       intra-transaction OUT+IN pair self-cancels its own transit contribution at the source
 *       key, exactly like intra-site bin moves). Net: destination unchanged, in-transit zero
 *       at both keys, source on-hand restored by the remainder. A single {@code TRANSFER_IN}
 *       at the source WITHOUT the pair was rejected: in-transit is keyed at the destination
 *       via {@code TRANSFER_OUT.toLocationId}, so a source-keyed {@code TRANSFER_IN} would
 *       leave destination transit standing and drive source transit negative.</li>
 * </ul>
 * All entries of a short-close post atomically through the funnel with
 * {@code sourceTransactionId = transferOrderId}. Within each line the constructive
 * {@code TRANSFER_IN} precedes the outbound entry, so the negative-stock projection at the
 * destination never dips below its starting balance. No scrap document is created for transit
 * losses (a document would re-gate an already-permissioned short-close behind the D1 approval
 * flow) and consequently no {@code ScrapPostedV1} fact is emitted — the terminal
 * {@link TransferOrderUpdatedV1} fact carries the disposition instead.
 *
 * <p>Facts: every state change queues a {@link TransferOrderUpdatedV1} occurrence fact through
 * {@link InventoryFactPublisher} (outbox at beforeCommit).
 */
@Service
@Slf4j
public class TransferOrderServiceImpl implements TransferOrderService {

    private static final String ELIGIBLE_STATUS = "ACTIVE";

    private final TransferOrderRepository transferOrderRepository;
    private final LocationRefRepository locationRefRepository;
    private final ExtStorageLocationReplicaRepository storageLocationRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryLedgerEntryRepository ledgerRepository;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final InventoryLotOutboundService lotOutboundService;
    private final Clock clock;
    private final boolean approvalRequired;

    public TransferOrderServiceImpl(
            TransferOrderRepository transferOrderRepository,
            LocationRefRepository locationRefRepository,
            ExtStorageLocationReplicaRepository storageLocationRepository,
            LedgerPostingService ledgerPostingService,
            InventoryLedgerEntryRepository ledgerRepository,
            InventoryFactPublisher inventoryFactPublisher,
            InventoryLotOutboundService lotOutboundService,
            Clock clock,
            @Value("${pos.inventory.transfer.approval-required:false}") boolean approvalRequired) {
        this.transferOrderRepository = transferOrderRepository;
        this.locationRefRepository = locationRefRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.ledgerRepository = ledgerRepository;
        this.inventoryFactPublisher = inventoryFactPublisher;
        this.lotOutboundService = lotOutboundService;
        this.clock = clock;
        this.approvalRequired = approvalRequired;
    }

    @Override
    @Transactional
    public @NonNull TransferOrderResponse createTransferOrder(@NonNull CreateTransferOrderRequest request) {
        if (request.getSourceLocationId().equals(request.getDestinationLocationId())) {
            throw new IllegalArgumentException("Source and destination must be different sites; intra-site bin moves"
                    + " use POST /v1/inventory/stock-movements");
        }
        requireEligibleSite(request.getSourceLocationId(), "source");
        requireEligibleSite(request.getDestinationLocationId(), "destination");
        requireStorageLocationUnderSite(request.getSourceStorageLocationId(), request.getSourceLocationId(), "source");
        requireStorageLocationUnderSite(
                request.getDestinationStorageLocationId(), request.getDestinationLocationId(), "destination");

        TransferOrder order = TransferOrder.builder()
                .sourceLocationId(request.getSourceLocationId())
                .sourceStorageLocationId(request.getSourceStorageLocationId())
                .destinationLocationId(request.getDestinationLocationId())
                .destinationStorageLocationId(request.getDestinationStorageLocationId())
                .status(TransferOrderStatus.DRAFT)
                .notes(request.getNotes())
                .createdBy(currentActor())
                .build();
        int lineNumber = 1;
        for (TransferOrderLineRequest line : request.getLines()) {
            order.addLine(TransferOrderLine.builder()
                    .lineNumber(lineNumber++)
                    .sku(line.getSku())
                    .requestedQty(line.getRequestedQty())
                    .build());
        }
        order = transferOrderRepository.save(order);

        log.info(
                "Transfer order {} created: {} -> {} ({} lines)",
                order.getTransferOrderId(),
                order.getSourceLocationId(),
                order.getDestinationLocationId(),
                order.getLines().size());
        recordFact(order);
        return toResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull TransferOrderResponse getTransferOrder(@NonNull UUID transferOrderId) {
        return toResponse(load(transferOrderId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<TransferOrderResponse> listTransferOrders(
            @Nullable TransferOrderStatus status,
            @Nullable UUID sourceLocationId,
            @Nullable UUID destinationLocationId) {
        Specification<TransferOrder> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (sourceLocationId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("sourceLocationId"), sourceLocationId));
        }
        if (destinationLocationId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("destinationLocationId"), destinationLocationId));
        }
        return transferOrderRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull TransferOrderResponse approveTransferOrder(@NonNull UUID transferOrderId) {
        if (!approvalRequired) {
            // Decision D-8: with the flag off there is no approval step to perform — DRAFT
            // dispatches directly, so an approve call signals a client/config mismatch.
            throw new IllegalStateException("Transfer-order approval step is disabled"
                    + " (pos.inventory.transfer.approval-required=false); DRAFT orders dispatch directly");
        }
        TransferOrder order = load(transferOrderId);
        if (order.getStatus() != TransferOrderStatus.DRAFT) {
            throw new IllegalStateException("Cannot approve transfer order in status: " + order.getStatus());
        }
        order.setStatus(TransferOrderStatus.APPROVED);
        order.setApprovedBy(currentActor());
        order = transferOrderRepository.save(order);

        log.info("Transfer order {} approved by {}", transferOrderId, order.getApprovedBy());
        recordFact(order);
        return toResponse(order);
    }

    @Override
    @Transactional
    public @NonNull TransferOrderResponse cancelTransferOrder(@NonNull UUID transferOrderId) {
        TransferOrder order = load(transferOrderId);
        if (!order.getStatus().cancellable()) {
            throw new IllegalStateException("Cannot cancel transfer order in status: " + order.getStatus()
                    + "; dispatched stock is resolved via short-close, not cancellation");
        }
        order.setStatus(TransferOrderStatus.CANCELLED);
        order = transferOrderRepository.save(order);

        log.info("Transfer order {} cancelled by {}", transferOrderId, currentActor());
        recordFact(order);
        return toResponse(order);
    }

    @Override
    @Transactional
    public @NonNull TransferOrderResponse dispatchTransferOrder(
            @NonNull UUID transferOrderId, @NonNull DispatchTransferOrderRequest request) {
        TransferOrder order = load(transferOrderId);
        requireDispatchableStatus(order);
        // Spec C5: both endpoints of the movement re-validated at posting time.
        requireEligibleSite(order.getSourceLocationId(), "source");
        requireEligibleSite(order.getDestinationLocationId(), "destination");

        Map<UUID, TransferQuantityLineRequest> explicit = explicitLines(request.getLines(), order);
        UUID sourcePosting = sourcePostingLocation(order);
        UUID destinationPosting = destinationPostingLocation(order);
        String actor = currentActor();

        List<InventoryLedgerEntry> entries = new ArrayList<>();
        Map<String, BigDecimal> runningDelta = new HashMap<>();
        for (TransferOrderLine line : order.getLines()) {
            TransferQuantityLineRequest explicitLine = explicit.get(line.getLineId());
            int quantity = explicitLine != null ? explicitLine.getQuantity() : line.getRequestedQty();
            if (quantity > line.getRequestedQty()) {
                throw TransferQuantityExceededException.dispatchExceedsRequested(
                        line.getLineId(), line.getSku(), quantity, line.getRequestedQty());
            }
            // odoo-parity E2 (#1042): LOT-tracked SKUs must key the lot being dispatched
            // (422 LOT_NUMBER_REQUIRED / LOT_UNKNOWN / LOT_NOT_AVAILABLE); the resolved lot is
            // pinned on the line so receive and short-close post the SAME lot on both sides —
            // per-lot balances then conserve across sites exactly like the lot-agnostic ones.
            // Untracked SKUs resolve to null, byte-identical to pre-E2.
            UUID lotId = lotOutboundService.resolveOutboundLot(
                    line.getSku(), explicitLine != null ? explicitLine.getLotNumber() : null);
            line.setLotId(lotId);
            line.setLotNumber(lotId == null || explicitLine == null ? null : explicitLine.getLotNumber());
            entries.add(transferEntry(
                    order,
                    line.getSku(),
                    InventoryLedgerEventType.TRANSFER_OUT,
                    -quantity,
                    sourcePosting,
                    sourcePosting,
                    destinationPosting,
                    lotId,
                    runningDelta,
                    actor));
            line.setDispatchedQty(quantity);
        }

        // Funnel enforces the negative-stock matrix: TRANSFER_OUT is BLOCKED below zero, so an
        // over-dispatch of physical stock rejects the whole batch (422 INSUFFICIENT_STOCK).
        List<InventoryLedgerEntry> saved = ledgerPostingService.postAll(entries);
        inventoryFactPublisher.markEntries(saved);

        order.setStatus(TransferOrderStatus.DISPATCHED);
        order.setDispatchedBy(actor);
        order = transferOrderRepository.save(order);

        log.info(
                "Transfer order {} dispatched by {}: {} -> {} ({} lines)",
                transferOrderId,
                actor,
                sourcePosting,
                destinationPosting,
                saved.size());
        recordFact(order);
        return toResponse(order);
    }

    @Override
    @Transactional
    public @NonNull TransferOrderResponse receiveTransferOrder(
            @NonNull UUID transferOrderId, @NonNull ReceiveTransferOrderRequest request) {
        TransferOrder order = load(transferOrderId);
        if (!order.getStatus().receivable()) {
            throw new IllegalStateException("Cannot receive transfer order in status: " + order.getStatus());
        }
        // Spec C5: the receiving end re-validated at posting time.
        requireEligibleSite(order.getDestinationLocationId(), "destination");

        Map<UUID, TransferQuantityLineRequest> explicit = explicitLines(request.getLines(), order);
        UUID sourcePosting = sourcePostingLocation(order);
        UUID destinationPosting = destinationPostingLocation(order);
        String actor = currentActor();

        List<InventoryLedgerEntry> entries = new ArrayList<>();
        Map<String, BigDecimal> runningDelta = new HashMap<>();
        for (TransferOrderLine line : order.getLines()) {
            int remaining = line.getDispatchedQty() - line.getReceivedQty();
            TransferQuantityLineRequest explicitLine = explicit.get(line.getLineId());
            int quantity = explicitLine != null ? explicitLine.getQuantity() : remaining;
            if (quantity > remaining) {
                throw TransferQuantityExceededException.receiveExceedsDispatched(
                        line.getLineId(), line.getSku(), quantity, remaining);
            }
            if (quantity == 0) {
                continue;
            }
            // E2: the arrival carries the lot pinned at dispatch — never a request-keyed one.
            entries.add(transferEntry(
                    order,
                    line.getSku(),
                    InventoryLedgerEventType.TRANSFER_IN,
                    quantity,
                    destinationPosting,
                    sourcePosting,
                    destinationPosting,
                    line.getLotId(),
                    runningDelta,
                    actor));
            line.setReceivedQty(line.getReceivedQty() + quantity);
        }

        List<InventoryLedgerEntry> saved = ledgerPostingService.postAll(entries);
        inventoryFactPublisher.markEntries(saved);

        boolean fullyReceived =
                order.getLines().stream().allMatch(line -> line.getReceivedQty().equals(line.getDispatchedQty()));
        order.setStatus(fullyReceived ? TransferOrderStatus.RECEIVED : TransferOrderStatus.PARTIALLY_RECEIVED);
        order = transferOrderRepository.save(order);

        log.info(
                "Transfer order {} received by {} at {}: {} entries, status {}",
                transferOrderId,
                actor,
                destinationPosting,
                saved.size(),
                order.getStatus());
        recordFact(order);
        return toResponse(order);
    }

    @Override
    @Transactional
    public @NonNull TransferOrderResponse shortCloseTransferOrder(
            @NonNull UUID transferOrderId, @NonNull ShortCloseTransferOrderRequest request) {
        TransferOrder order = load(transferOrderId);
        if (!order.getStatus().receivable()) {
            throw new IllegalStateException("Cannot short-close transfer order in status: " + order.getStatus()
                    + "; short-close resolves the in-transit remainder of a DISPATCHED or PARTIALLY_RECEIVED order");
        }
        int totalRemainder = order.getLines().stream()
                .mapToInt(line -> line.getDispatchedQty() - line.getReceivedQty())
                .sum();
        if (totalRemainder <= 0) {
            throw new IllegalStateException(
                    "Cannot short-close transfer order " + transferOrderId + ": no outstanding in-transit remainder");
        }
        // RETURNED_TO_SOURCE physically lands stock back at the source site, so the source is
        // re-validated for movement eligibility (DECISION-INVENTORY-009). LOST_IN_TRANSIT lands
        // nothing anywhere — deliberately no eligibility check, so a transfer toward a
        // since-deactivated destination can still be resolved as a loss.
        if (request.getDisposition() == TransferShortCloseDisposition.RETURNED_TO_SOURCE) {
            requireEligibleSite(order.getSourceLocationId(), "source");
        }

        UUID sourcePosting = sourcePostingLocation(order);
        UUID destinationPosting = destinationPostingLocation(order);
        String actor = currentActor();

        List<InventoryLedgerEntry> entries = new ArrayList<>();
        Map<String, BigDecimal> runningDelta = new HashMap<>();
        for (TransferOrderLine line : order.getLines()) {
            int remainder = line.getDispatchedQty() - line.getReceivedQty();
            if (remainder == 0) {
                continue;
            }
            // Constructive arrival at the destination key: cancels this line's outstanding
            // in-transit (see the short-close ledger-shape javadoc on this class). Every entry
            // of the shape carries the lot pinned at dispatch (E2), so the per-lot rows walk
            // through the same conserving pairs as the lot-agnostic balances.
            entries.add(transferEntry(
                    order,
                    line.getSku(),
                    InventoryLedgerEventType.TRANSFER_IN,
                    remainder,
                    destinationPosting,
                    sourcePosting,
                    destinationPosting,
                    line.getLotId(),
                    runningDelta,
                    actor));
            if (request.getDisposition() == TransferShortCloseDisposition.LOST_IN_TRANSIT) {
                entries.add(
                        lostRemainderEntry(order, line, remainder, destinationPosting, request, runningDelta, actor));
            } else {
                entries.add(transferEntry(
                        order,
                        line.getSku(),
                        InventoryLedgerEventType.TRANSFER_OUT,
                        -remainder,
                        destinationPosting,
                        destinationPosting,
                        sourcePosting,
                        line.getLotId(),
                        runningDelta,
                        actor));
                entries.add(transferEntry(
                        order,
                        line.getSku(),
                        InventoryLedgerEventType.TRANSFER_IN,
                        remainder,
                        sourcePosting,
                        destinationPosting,
                        sourcePosting,
                        line.getLotId(),
                        runningDelta,
                        actor));
            }
        }

        List<InventoryLedgerEntry> saved = ledgerPostingService.postAll(entries);
        inventoryFactPublisher.markEntries(saved);

        order.setStatus(TransferOrderStatus.SHORT_CLOSED);
        order.setShortCloseDisposition(request.getDisposition());
        order.setShortCloseReason(request.getReason());
        order.setShortCloseNotes(request.getNotes());
        order.setShortClosedBy(actor);
        order.setShortClosedAt(Instant.now(clock));
        order = transferOrderRepository.save(order);

        log.info(
                "Transfer order {} short-closed by {} with disposition {}: remainder {} resolved in {} entries",
                transferOrderId,
                actor,
                request.getDisposition(),
                totalRemainder,
                saved.size());
        recordFact(order);
        return toResponse(order);
    }

    /**
     * Builds the {@code SCRAP_OUT} half of a LOST_IN_TRANSIT line (spec C4 via the WS-D reason
     * taxonomy): posted at the destination key directly behind the constructive
     * {@code TRANSFER_IN}, with {@code reasonCode = LOST},
     * {@code sourceTransactionId = transferOrderId}, the request notes, and the D1 interim cost
     * snapshot (latest {@code GOODS_RECEIPT} unit cost, falling back to the latest non-null
     * unit cost of any entry — same rule as {@code ScrapServiceImpl}; WS-J refines the source).
     * Deliberately NO {@code ScrapRecord} document: short-close is already permission-gated and
     * loss-accepting, so a scrap document would only double-gate it behind the D1 approval
     * tiers; accounting reads the disposition off the {@code TransferOrderUpdatedV1} fact.
     */
    private InventoryLedgerEntry lostRemainderEntry(
            TransferOrder order,
            TransferOrderLine line,
            int remainder,
            UUID destinationPosting,
            ShortCloseTransferOrderRequest request,
            Map<String, BigDecimal> runningDelta,
            String actor) {
        String sku = line.getSku();
        // Transfer-order line quantities are still ints (the transfer document widens with the
        // UOM work); the running ledger totals they are added to are decimal.
        BigDecimal lostQuantity = BigDecimal.valueOf(remainder);
        BigDecimal currentOnHand =
                Quantities.nz(ledgerRepository.calculateOnHandQuantityAtLocation(sku, destinationPosting));
        String deltaKey = runningDeltaKey(sku, destinationPosting);
        BigDecimal before = currentOnHand.add(runningDelta.getOrDefault(deltaKey, BigDecimal.ZERO));
        runningDelta.merge(deltaKey, lostQuantity.negate(), BigDecimal::add);
        return InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(destinationPosting)
                .eventType(InventoryLedgerEventType.SCRAP_OUT)
                .changeInQuantity(lostQuantity.negate())
                .quantityAfter(before.subtract(lostQuantity))
                .unitCost(latestUnitCost(sku))
                .reasonCode(ScrapReasonCode.LOST.name())
                .lotId(line.getLotId())
                .sourceTransactionId(order.getTransferOrderId().toString())
                .transactionUserId(actor)
                .notes(request.getNotes())
                .timestamp(Instant.now(clock))
                .build();
    }

    /**
     * D1's interim cost snapshot rule (ADR-0048 / plan D-6): most recent {@code GOODS_RECEIPT}
     * unit cost for the SKU, falling back to the latest non-null unit cost of any entry, else
     * null. odoo-parity J1 replaces this with the authoritative cost source.
     */
    private @Nullable BigDecimal latestUnitCost(String sku) {
        List<BigDecimal> receiptCosts = ledgerRepository.findLatestUnitCostByStockItemAndEventType(
                sku, InventoryLedgerEventType.GOODS_RECEIPT, PageRequest.of(0, 1));
        if (!receiptCosts.isEmpty()) {
            return receiptCosts.getFirst();
        }
        List<BigDecimal> anyCosts = ledgerRepository.findLatestUnitCostByStockItem(sku, PageRequest.of(0, 1));
        return anyCosts.isEmpty() ? null : anyCosts.getFirst();
    }

    /**
     * Decision D-8 status gate for dispatch: flag off — DRAFT dispatches directly (APPROVED is
     * unreachable); flag on — only APPROVED dispatches, a DRAFT must be approved first.
     */
    private void requireDispatchableStatus(TransferOrder order) {
        TransferOrderStatus expected = approvalRequired ? TransferOrderStatus.APPROVED : TransferOrderStatus.DRAFT;
        if (order.getStatus() != expected) {
            String hint = approvalRequired && order.getStatus() == TransferOrderStatus.DRAFT
                    ? " (approval is required before dispatch: pos.inventory.transfer.approval-required=true)"
                    : "";
            throw new IllegalStateException("Cannot dispatch transfer order in status: " + order.getStatus() + hint);
        }
    }

    /** Maps explicit request lines to their order lines; unknown line ids are a 400. */
    private Map<UUID, TransferQuantityLineRequest> explicitLines(
            @Nullable List<TransferQuantityLineRequest> requestLines, TransferOrder order) {
        Map<UUID, TransferQuantityLineRequest> byLineId = new LinkedHashMap<>();
        if (requestLines == null) {
            return byLineId;
        }
        for (TransferQuantityLineRequest requestLine : requestLines) {
            boolean known =
                    order.getLines().stream().anyMatch(line -> line.getLineId().equals(requestLine.getLineId()));
            if (!known) {
                throw new IllegalArgumentException("Unknown transfer order line: " + requestLine.getLineId());
            }
            if (byLineId.putIfAbsent(requestLine.getLineId(), requestLine) != null) {
                throw new IllegalArgumentException("Duplicate transfer order line: " + requestLine.getLineId());
            }
        }
        return byLineId;
    }

    /**
     * Builds one transfer posting entry. {@code runningDelta} accumulates per-(SKU, posting
     * location) deltas within the batch so {@code quantityAfter} stays correct when multiple
     * entries share a SKU — across lines (dispatch/receive) or across the two ends of a
     * short-close return shape (the batch posts atomically, so each entry's after-quantity
     * must include earlier entries at its own key).
     */
    private InventoryLedgerEntry transferEntry(
            TransferOrder order,
            String sku,
            InventoryLedgerEventType eventType,
            int changeInQuantity,
            UUID postingLocationId,
            UUID fromLocationId,
            UUID toLocationId,
            @Nullable UUID lotId,
            Map<String, BigDecimal> runningDelta,
            String actor) {
        BigDecimal change = BigDecimal.valueOf(changeInQuantity);
        BigDecimal currentOnHand =
                Quantities.nz(ledgerRepository.calculateOnHandQuantityAtLocation(sku, postingLocationId));
        String deltaKey = runningDeltaKey(sku, postingLocationId);
        BigDecimal before = currentOnHand.add(runningDelta.getOrDefault(deltaKey, BigDecimal.ZERO));
        runningDelta.merge(deltaKey, change, BigDecimal::add);
        return InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(postingLocationId)
                .fromLocationId(fromLocationId)
                .toLocationId(toLocationId)
                .eventType(eventType)
                .changeInQuantity(change)
                .quantityAfter(before.add(change))
                .lotId(lotId)
                .sourceTransactionId(order.getTransferOrderId().toString())
                .transactionUserId(actor)
                .timestamp(Instant.now(clock))
                .build();
    }

    /** Within-batch quantity bookkeeping key: one running delta per (SKU, posting location). */
    private static String runningDeltaKey(String sku, UUID postingLocationId) {
        return sku + "@" + postingLocationId;
    }

    /** Bin-level source posting location when pinned on the order, else the source site key. */
    private UUID sourcePostingLocation(TransferOrder order) {
        return order.getSourceStorageLocationId() != null
                ? order.getSourceStorageLocationId()
                : order.getSourceLocationId();
    }

    /** Bin-level destination posting location when pinned on the order, else the destination site key. */
    private UUID destinationPostingLocation(TransferOrder order) {
        return order.getDestinationStorageLocationId() != null
                ? order.getDestinationStorageLocationId()
                : order.getDestinationLocationId();
    }

    private TransferOrder load(UUID transferOrderId) {
        return transferOrderRepository
                .findById(transferOrderId)
                .orElseThrow(() -> new TransferOrderNotFoundException(transferOrderId));
    }

    /**
     * Movement eligibility per DECISION-INVENTORY-009: the site must exist in the LocationRef
     * roster (404 otherwise) with status ACTIVE (422 {@code TRANSFER_LOCATION_NOT_ELIGIBLE}
     * for INACTIVE/PENDING or any other status).
     */
    void requireEligibleSite(UUID siteId, String role) {
        LocationRefEntity site =
                locationRefRepository.findByLocationId(siteId).orElseThrow(() -> new LocationNotFoundException(siteId));
        if (!ELIGIBLE_STATUS.equalsIgnoreCase(site.getStatus())) {
            throw new TransferLocationNotEligibleException(siteId, site.getStatus(), role);
        }
    }

    /**
     * Optional bin validation: when the {@code ext_storage_location} replica knows the bin, it
     * must belong to the given site. Unknown bins pass (replica is eventually consistent).
     */
    private void requireStorageLocationUnderSite(@Nullable UUID storageLocationId, UUID siteId, String role) {
        if (storageLocationId == null) {
            return;
        }
        storageLocationRepository.findById(storageLocationId).ifPresent(bin -> {
            if (bin.getSiteId() != null && !bin.getSiteId().equals(siteId)) {
                throw new IllegalArgumentException("Storage location " + storageLocationId + " belongs to site "
                        + bin.getSiteId() + ", not the " + role + " site " + siteId);
            }
        });
    }

    /** Queues the {@code TransferOrderUpdatedV1} occurrence fact for the current transaction. */
    void recordFact(TransferOrder order) {
        inventoryFactPublisher.recordTransferOrderUpdated(new TransferOrderUpdatedV1(
                order.getTransferOrderId(),
                order.getStatus().name(),
                order.getSourceLocationId(),
                order.getSourceStorageLocationId(),
                order.getDestinationLocationId(),
                order.getDestinationStorageLocationId(),
                order.getLines().stream()
                        .map(line -> new TransferOrderUpdatedV1.LineSummary(
                                line.getSku(), line.getRequestedQty(), line.getDispatchedQty(), line.getReceivedQty()))
                        .toList(),
                order.getShortCloseDisposition() != null
                        ? order.getShortCloseDisposition().name()
                        : null,
                Instant.now(clock)));
    }

    String currentActor() {
        // Falls back to the "system" actor for automated callers with no user context
        // (odoo-parity F5 replenishment sourcing creates transfers from the scheduled scan) —
        // module convention for system-initiated writes. User-facing endpoints are
        // permission-gated at the controller, so a real username is always present for them.
        return SecurityContextHelper.getCurrentUsernameOrDefault("system");
    }

    TransferOrderResponse toResponse(TransferOrder order) {
        return TransferOrderResponse.builder()
                .transferOrderId(order.getTransferOrderId())
                .sourceLocationId(order.getSourceLocationId())
                .sourceStorageLocationId(order.getSourceStorageLocationId())
                .destinationLocationId(order.getDestinationLocationId())
                .destinationStorageLocationId(order.getDestinationStorageLocationId())
                .status(order.getStatus())
                .lines(order.getLines().stream().map(this::toLineResponse).toList())
                .notes(order.getNotes())
                .createdBy(order.getCreatedBy())
                .approvedBy(order.getApprovedBy())
                .dispatchedBy(order.getDispatchedBy())
                .shortCloseDisposition(order.getShortCloseDisposition())
                .shortCloseReason(order.getShortCloseReason())
                .shortCloseNotes(order.getShortCloseNotes())
                .shortClosedBy(order.getShortClosedBy())
                .shortClosedAt(order.getShortClosedAt())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    private TransferOrderLineResponse toLineResponse(TransferOrderLine line) {
        return TransferOrderLineResponse.builder()
                .lineId(line.getLineId())
                .lineNumber(line.getLineNumber())
                .sku(line.getSku())
                .requestedQty(line.getRequestedQty())
                .dispatchedQty(line.getDispatchedQty())
                .receivedQty(line.getReceivedQty())
                .lotId(line.getLotId())
                .lotNumber(line.getLotNumber())
                .createdAt(line.getCreatedAt())
                .updatedAt(line.getUpdatedAt())
                .build();
    }
}
