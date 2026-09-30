package com.positivity.inventory.internal.receiving.service;

import com.positivity.inventory.internal.dto.returns.ReasonCodeDto;
import com.positivity.inventory.internal.dto.returns.ReturnItemLine;
import com.positivity.inventory.internal.dto.returns.ReturnItemsRequest;
import com.positivity.inventory.internal.dto.returns.ReturnLineDto;
import com.positivity.inventory.internal.dto.returns.ReturnResponse;
import com.positivity.inventory.internal.dto.returns.ReturnSubmissionResultDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest;
import com.positivity.inventory.internal.dto.returns.ReturnableItemDto;
import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import com.positivity.inventory.internal.entity.ExtWorkorderReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryReturnEntity;
import com.positivity.inventory.internal.entity.InventoryReturnLineEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.ReplicationPendingCodes;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.internal.exception.WorkorderNotReturnableException;
import com.positivity.inventory.internal.repository.ExtWorkorderPartReplicaRepository;
import com.positivity.inventory.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryReturnLineRepository;
import com.positivity.inventory.internal.repository.InventoryReturnRepository;
import com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver;
import com.positivity.inventory.internal.service.DocumentQuantityConverter;
import com.positivity.inventory.internal.service.InventoryFactPublisher;
import com.positivity.inventory.internal.service.InventoryLotOutboundService;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.internal.service.Quantities;
import com.positivity.inventory.internal.service.QuantityScaleGuard;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.web.common.ReplicationPendingException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReturnServiceImpl implements ReturnService {

    private final InventoryReturnRepository inventoryReturnRepository;
    private final InventoryReturnLineRepository inventoryReturnLineRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final ExtWorkorderPartReplicaRepository extWorkorderPartReplicaRepository;
    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final DocumentQuantityConverter documentQuantityConverter;
    private final BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;
    private final Clock clock;
    private final @Nullable InventoryLotOutboundService lotOutboundService;
    private final QuantityScaleGuard quantityScaleGuard;

    /** CAP-218 Story #177: only a workorder in one of these statuses accepts a return. */
    private static final Set<String> RETURNABLE_WORKORDER_STATUSES = Set.of("COMPLETED", "CLOSED");

    @Autowired
    public ReturnServiceImpl(
            InventoryReturnRepository inventoryReturnRepository,
            InventoryReturnLineRepository inventoryReturnLineRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            ExtWorkorderPartReplicaRepository extWorkorderPartReplicaRepository,
            ExtWorkorderReplicaRepository extWorkorderReplicaRepository,
            LedgerPostingService ledgerPostingService,
            InventoryFactPublisher inventoryFactPublisher,
            DocumentQuantityConverter documentQuantityConverter,
            BaseUnitOfMeasureResolver baseUnitOfMeasureResolver,
            Clock clock,
            InventoryLotOutboundService lotOutboundService,
            QuantityScaleGuard quantityScaleGuard) {
        this.inventoryReturnRepository = inventoryReturnRepository;
        this.inventoryReturnLineRepository = inventoryReturnLineRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.extWorkorderPartReplicaRepository = extWorkorderPartReplicaRepository;
        this.extWorkorderReplicaRepository = extWorkorderReplicaRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.inventoryFactPublisher = inventoryFactPublisher;
        this.documentQuantityConverter = documentQuantityConverter;
        this.baseUnitOfMeasureResolver = baseUnitOfMeasureResolver;
        this.clock = clock;
        this.lotOutboundService = lotOutboundService;
        this.quantityScaleGuard = quantityScaleGuard;
    }

    /**
     * Lot-gate-less constructor kept for the pre-E2 unit-test fixtures: without an
     * {@link InventoryLotOutboundService} every SKU behaves untracked (no lot validation,
     * lot-null postings) — identical to the service's behavior for NONE-tracked products
     * (odoo-parity E2, issue #1042).
     */
    public ReturnServiceImpl(
            InventoryReturnRepository inventoryReturnRepository,
            InventoryReturnLineRepository inventoryReturnLineRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            ExtWorkorderPartReplicaRepository extWorkorderPartReplicaRepository,
            ExtWorkorderReplicaRepository extWorkorderReplicaRepository,
            LedgerPostingService ledgerPostingService,
            InventoryFactPublisher inventoryFactPublisher,
            DocumentQuantityConverter documentQuantityConverter,
            BaseUnitOfMeasureResolver baseUnitOfMeasureResolver,
            Clock clock,
            QuantityScaleGuard quantityScaleGuard) {
        this(
                inventoryReturnRepository,
                inventoryReturnLineRepository,
                inventoryLedgerEntryRepository,
                extWorkorderPartReplicaRepository,
                extWorkorderReplicaRepository,
                ledgerPostingService,
                inventoryFactPublisher,
                documentQuantityConverter,
                baseUnitOfMeasureResolver,
                clock,
                null,
                quantityScaleGuard);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReturnableItemDto> listReturnableItems(@NonNull UUID workorderId) {
        List<ExtWorkorderPartReplica> lines = extWorkorderPartReplicaRepository.findByWorkorderId(workorderId);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<UUID> lineIds =
                lines.stream().map(ExtWorkorderPartReplica::getWorkorderLineId).toList();
        Map<UUID, BigDecimal> consumedByLine = sumConsumedByLine(workorderId, lineIds);
        Map<UUID, BigDecimal> returnedByLine = sumReturnedByLine(lineIds);
        Map<UUID, String> uomByProduct = baseUnitOfMeasureResolver.resolveAll(
                lines.stream().map(ExtWorkorderPartReplica::getProductEntityId).toList());

        List<ReturnableItemDto> result = new ArrayList<>(lines.size());
        for (ExtWorkorderPartReplica line : lines) {
            BigDecimal returnable = returnableQuantity(consumedByLine, returnedByLine, line.getWorkorderLineId());
            UUID productId = line.getProductEntityId();
            result.add(ReturnableItemDto.builder()
                    .itemId(line.getWorkorderLineId())
                    .workorderLineId(line.getWorkorderLineId())
                    .sku(productId == null ? null : productId.toString())
                    .uom(uomByProduct.get(productId))
                    .quantityReturnable(returnable.intValue())
                    .workorderId(workorderId)
                    .build());
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReasonCodeDto> listReturnReasonCodes() {
        // CAP-218 Story #177: the closed set a submitToStock line's reasonCode must belong to.
        return List.of(
                ReasonCodeDto.builder()
                        .code("NOT_NEEDED")
                        .description("Part is no longer needed for the repair")
                        .category("PLANNING")
                        .build(),
                ReasonCodeDto.builder()
                        .code("WRONG_PART")
                        .description("The wrong part was picked or issued")
                        .category("ERROR")
                        .build(),
                ReasonCodeDto.builder()
                        .code("CUSTOMER_REFUSED")
                        .description("Customer declined the recommended part")
                        .category("CUSTOMER")
                        .build());
    }

    @Override
    @Transactional
    public @NonNull ReturnSubmissionResultDto submitToStock(@NonNull ReturnSubmitRequest request) {
        List<ReturnLineDto> lines = request.getLines() == null ? List.of() : request.getLines();
        UUID workorderId = request.getWorkorderId();

        requireReturnableWorkorder(workorderId);

        // #2227 review item 6: a workorderLineId named twice must not be validated
        // independently against the same returnable baseline — reject rather than silently
        // aggregate, so the caller sees exactly which line was duplicated.
        Set<UUID> seenLineIds = new HashSet<>();
        for (ReturnLineDto line : lines) {
            if (!seenLineIds.add(line.getItemId())) {
                throw new IllegalArgumentException(
                        "lines[].itemId must not repeat a workorderLineId within one submission: " + line.getItemId());
            }
        }

        List<UUID> workorderLineIds =
                lines.stream().map(ReturnLineDto::getItemId).toList();
        Map<UUID, ExtWorkorderPartReplica> partLinesById =
                extWorkorderPartReplicaRepository.findAllById(workorderLineIds).stream()
                        .collect(Collectors.toMap(ExtWorkorderPartReplica::getWorkorderLineId, line -> line));
        ConsumptionLedger consumption = consumptionLedger(workorderId, workorderLineIds);
        Map<UUID, BigDecimal> consumedByLine = consumption.quantityByLine();
        Map<UUID, BigDecimal> returnedByLine = sumReturnedByLine(workorderLineIds);

        // Validate every line before writing anything: a quantity that exceeds what remains
        // returnable must leave no state change (422 RETURN_QUANTITY_EXCEEDED).
        //
        // #1994: the ext_workorder_part row is not what proves a line exists — the consumption
        // ledger this module owns is, and it is what returnable is computed from. A line the
        // replica has not caught up with is therefore not a 404: it is returnable to the extent it
        // was consumed, and the replica is consulted only for the product id (falling back to the
        // ledger's own record of it, then to the line id in the refusal message).
        BigDecimal totalReturned = BigDecimal.ZERO;
        Map<UUID, UUID> productByLine = new HashMap<>();
        for (ReturnLineDto line : lines) {
            UUID workorderLineId = line.getItemId();
            ExtWorkorderPartReplica partLine = partLinesById.get(workorderLineId);
            UUID productId = productFor(partLine, consumption, workorderLineId);
            if (productId != null) {
                productByLine.put(workorderLineId, productId);
            }
            BigDecimal returnable = returnableQuantity(consumedByLine, returnedByLine, workorderLineId);
            BigDecimal requested = BigDecimal.valueOf(line.getQuantity());
            if (requested.compareTo(returnable) > 0) {
                throw new ReturnQuantityExceededException(
                        productId != null ? productId : workorderLineId, requested, returnable);
            }
            totalReturned = totalReturned.add(requested);
        }

        InventoryReturnEntity inventoryReturn = InventoryReturnEntity.builder()
                .workorderId(workorderId)
                .returnReason(lines.stream()
                        .map(ReturnLineDto::getReasonCode)
                        .distinct()
                        .reduce((left, right) -> left + "," + right)
                        .orElse(""))
                .totalItemsReturned(totalReturned)
                .build();

        List<InventoryReturnLineEntity> returnLines = new ArrayList<>(lines.size());
        List<InventoryLedgerEntry> ledgerEntries = new ArrayList<>(lines.size());
        for (ReturnLineDto line : lines) {
            UUID workorderLineId = line.getItemId();
            UUID productId = productByLine.get(workorderLineId);
            if (productId == null) {
                throw new IllegalStateException(
                        "No product recorded for workorder line " + workorderLineId + " on workorder " + workorderId);
            }
            BigDecimal quantity = BigDecimal.valueOf(line.getQuantity());

            returnLines.add(InventoryReturnLineEntity.builder()
                    .inventoryReturn(inventoryReturn)
                    .skuId(productId)
                    .workorderLineId(workorderLineId)
                    .quantityReturned(quantity)
                    .build());

            UUID destinationLocationId =
                    line.getStorageLocationId() != null ? line.getStorageLocationId() : line.getLocationId();
            ledgerEntries.add(InventoryLedgerEntry.builder()
                    .stockItemId(productId.toString())
                    .eventType(InventoryLedgerEventType.RETURN_TO_STOCK)
                    .changeInQuantity(quantity.abs())
                    .quantityAfter(BigDecimal.ZERO)
                    .locationId(destinationLocationId)
                    .toLocationId(destinationLocationId)
                    .workorderId(workorderId)
                    .workorderLineId(workorderLineId)
                    .reasonCode(line.getReasonCode())
                    .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .notes("Returned to stock from workorder " + workorderId + " line " + workorderLineId + ": "
                            + line.getReasonCode())
                    .build());
        }
        inventoryReturn.setLines(returnLines);
        InventoryReturnEntity savedReturn = Objects.requireNonNull(
                inventoryReturnRepository.save(inventoryReturn), "inventoryReturnRepository.save(...) returned null");

        List<InventoryLedgerEntry> savedEntries = ledgerPostingService.postAll(ledgerEntries);
        inventoryFactPublisher.markEntries(savedEntries);

        return ReturnSubmissionResultDto.builder()
                .returnId(savedReturn.getReturnId())
                .workorderId(workorderId)
                .processedLines(lines.size())
                .status("SUBMITTED")
                .processedAt(savedReturn.getCreatedAt() != null ? savedReturn.getCreatedAt() : Instant.now(clock))
                .build();
    }

    /**
     * CAP-218 Story #177 (PR #2227 review item 5): a return may only be submitted once the
     * workorder is COMPLETED or CLOSED — parts are handed back once the job is done, not
     * mid-repair. 503 {@code WORKORDER_REPLICATION_PENDING} when the replica has no row for the
     * workorder yet (nothing to check against, and the workorder may simply not have replicated,
     * #1994), 422 {@code WORKORDER_NOT_RETURNABLE} for any other status.
     */
    private void requireReturnableWorkorder(UUID workorderId) {
        ExtWorkorderReplica workorder = extWorkorderReplicaRepository
                .findById(workorderId)
                .orElseThrow(() -> new ReplicationPendingException(
                        ReplicationPendingCodes.WORKORDER_REPLICATION_PENDING,
                        "The workorder has not replicated from Workorder yet; retry shortly",
                        workorderId));
        String status = workorder.getStatus() == null
                ? ""
                : workorder.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!RETURNABLE_WORKORDER_STATUSES.contains(status)) {
            throw new WorkorderNotReturnableException(workorderId, workorder.getStatus());
        }
    }

    /** Consumed (WORKORDER_CONSUMPTION) quantity for a work order, summed per work order line. */
    private Map<UUID, BigDecimal> sumConsumedByLine(UUID workorderId, Collection<UUID> lineIds) {
        return consumptionLedger(workorderId, lineIds).quantityByLine();
    }

    /**
     * The work order's consumption ledger, read once: the quantity consumed per work order line
     * and the product each line consumed (the entry's {@code stockItemId}), which is what lets a
     * return name the product without depending on the {@code ext_workorder_part} replica.
     */
    private ConsumptionLedger consumptionLedger(UUID workorderId, Collection<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return new ConsumptionLedger(Map.of(), Map.of());
        }
        Map<UUID, BigDecimal> quantityByLine = new HashMap<>();
        Map<UUID, UUID> productByLine = new HashMap<>();
        for (InventoryLedgerEntry entry : inventoryLedgerEntryRepository.findByWorkorderIdAndEventType(
                workorderId, InventoryLedgerEventType.WORKORDER_CONSUMPTION)) {
            UUID lineId = entry.getWorkorderLineId();
            if (lineId == null) {
                continue;
            }
            quantityByLine.merge(
                    lineId, Quantities.nz(entry.getChangeInQuantity()).abs(), BigDecimal::add);
            UUID productId = parseProductId(entry.getStockItemId());
            if (productId != null) {
                productByLine.putIfAbsent(lineId, productId);
            }
        }
        return new ConsumptionLedger(quantityByLine, productByLine);
    }

    /** The replica's product for the line when it has one, else the one the consumption ledger recorded. */
    private static @Nullable UUID productFor(
            @Nullable ExtWorkorderPartReplica partLine, ConsumptionLedger consumption, UUID workorderLineId) {
        UUID replicated = partLine == null ? null : partLine.getProductEntityId();
        return replicated != null ? replicated : consumption.productByLine().get(workorderLineId);
    }

    private static @Nullable UUID parseProductId(@Nullable String stockItemId) {
        if (stockItemId == null || stockItemId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(stockItemId.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Consumption per work order line and the product each line consumed. */
    private record ConsumptionLedger(Map<UUID, BigDecimal> quantityByLine, Map<UUID, UUID> productByLine) {}

    /** Quantity already returned against each named work order line. */
    private Map<UUID, BigDecimal> sumReturnedByLine(Collection<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (InventoryReturnLineEntity line : inventoryReturnLineRepository.findByWorkorderLineIdIn(lineIds)) {
            UUID lineId = line.getWorkorderLineId();
            if (lineId == null) {
                continue;
            }
            result.merge(lineId, Quantities.nz(line.getQuantityReturned()), BigDecimal::add);
        }
        return result;
    }

    /** Consumed minus already returned for one line, floored at zero. */
    private static BigDecimal returnableQuantity(
            Map<UUID, BigDecimal> consumedByLine, Map<UUID, BigDecimal> returnedByLine, UUID lineId) {
        BigDecimal returnable =
                Quantities.nz(consumedByLine.get(lineId)).subtract(Quantities.nz(returnedByLine.get(lineId)));
        return returnable.signum() < 0 ? BigDecimal.ZERO : returnable;
    }

    @Transactional
    public @NonNull ReturnResponse returnItemsToStock(@NonNull ReturnItemsRequest request) {
        if (request.getReturnReason() == null || request.getReturnReason().isBlank()) {
            throw new IllegalArgumentException("returnReason must not be blank");
        }

        List<ReturnItemLine> items = request.getItems() == null ? List.of() : request.getItems();
        // odoo-parity B2 (#1034): convert optional document-UoM quantities to base BEFORE the
        // consumed-quantity validation and the ledger posting.
        List<ReturnLineComputation> computedItems = new ArrayList<>(items.size());
        for (ReturnItemLine item : items) {
            computedItems.add(computeReturnLine(item));
        }
        for (ReturnLineComputation computed : computedItems) {
            validateReturnQuantity(request.getWorkorderId(), computed);
        }

        InventoryReturnEntity inventoryReturn = buildReturnEntity(request, computedItems);
        InventoryReturnEntity savedReturn = Objects.requireNonNull(
                inventoryReturnRepository.save(inventoryReturn), "inventoryReturnRepository.save(...) returned null");

        List<InventoryLedgerEntry> ledgerEntries = new ArrayList<>();
        for (ReturnLineComputation computed : computedItems) {
            ledgerEntries.add(buildReturnLedgerEntry(request, computed));
        }

        List<InventoryLedgerEntry> savedLedgerEntries = ledgerPostingService.postAll(ledgerEntries);
        inventoryFactPublisher.markEntries(savedLedgerEntries);
        List<UUID> ledgerEntryIds = savedLedgerEntries.stream()
                .map(InventoryLedgerEntry::getLedgerEntryId)
                .filter(Objects::nonNull)
                .toList();

        UUID returnId = savedReturn.getReturnId() != null ? savedReturn.getReturnId() : UUIDv7Generator.generate();
        Instant createdAt = savedReturn.getCreatedAt() != null ? savedReturn.getCreatedAt() : Instant.now(clock);

        return new ReturnResponse(
                returnId,
                request.getWorkorderId(),
                request.getReturnReason().trim(),
                calculateTotalItemsReturned(computedItems),
                createdAt,
                ledgerEntryIds);
    }

    /**
     * Per-line return derivation (odoo-parity B2, #1034): the optional document-UoM conversion
     * plus the whole base quantity that validates against consumption and posts to the ledger.
     * {@code lotId} (odoo-parity E2, #1042) is the resolved lot for LOT-tracked SKUs — a
     * return must name an EXISTING lot ({@code LOT_NUMBER_REQUIRED}/{@code LOT_UNKNOWN}), but
     * unlike the other outbound-flow validations its status is not gated: returning stock to a
     * CONSUMED lot is the normal way it comes back to life (the funnel's status reconciler
     * flips CONSUMED → ACTIVE once the quantity lands), and returned units of a
     * QUARANTINED/RECALLED lot belong on that lot's balance where the block keeps applying.
     */
    private record ReturnLineComputation(
            ReturnItemLine item,
            DocumentQuantityConverter.DocumentConversion conversion,
            BigDecimal baseQuantity,
            @Nullable UUID lotId) {}

    private ReturnLineComputation computeReturnLine(ReturnItemLine item) {
        DocumentQuantityConverter.DocumentConversion conversion = documentQuantityConverter
                .convertIfPresent(
                        item.getSkuId(),
                        String.valueOf(item.getSkuId()),
                        item.getDocumentUom(),
                        item.getDocumentQuantity())
                .orElse(null);
        // ADR-0055 (#1414): the base quantity may carry decimals only to the scale the product
        // declares. This replaces an intValueExact() that refused every fraction for every
        // product — still fail-closed, but reading the catalog's declaration instead of assuming
        // one, so a product declaring precision_scale > 0 can be returned at its real quantity
        // and one declaring nothing is refused a fraction exactly as before.
        BigDecimal rawBaseQuantity = conversion == null ? item.getQuantityReturned() : conversion.baseQuantity();
        if (rawBaseQuantity == null || rawBaseQuantity.signum() <= 0) {
            throw new IllegalArgumentException("quantityReturned must be positive");
        }
        BigDecimal baseQuantity = quantityScaleGuard.requirePostable(
                item.getSkuId(), String.valueOf(item.getSkuId()), "quantityReturned", rawBaseQuantity);
        UUID lotId = lotOutboundService == null
                ? null
                : lotOutboundService.resolveReturnLot(item.getSkuId().toString(), item.getLotNumber());
        return new ReturnLineComputation(item, conversion, baseQuantity, lotId);
    }

    private BigDecimal calculateTotalItemsReturned(List<ReturnLineComputation> items) {
        return items.stream().map(ReturnLineComputation::baseQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private InventoryReturnEntity buildReturnEntity(ReturnItemsRequest request, List<ReturnLineComputation> items) {
        InventoryReturnEntity inventoryReturn = InventoryReturnEntity.builder()
                .workorderId(request.getWorkorderId())
                .returnReason(request.getReturnReason().trim())
                .totalItemsReturned(calculateTotalItemsReturned(items))
                .build();

        List<InventoryReturnLineEntity> lines = new ArrayList<>();
        for (ReturnLineComputation computed : items) {
            DocumentQuantityConverter.DocumentConversion conversion = computed.conversion();
            InventoryReturnLineEntity line = InventoryReturnLineEntity.builder()
                    .inventoryReturn(inventoryReturn)
                    .skuId(computed.item().getSkuId())
                    .quantityReturned(computed.baseQuantity())
                    .documentUom(conversion == null ? null : conversion.documentUom())
                    .documentQuantity(conversion == null ? null : conversion.documentQuantity())
                    .conversionFactor(conversion == null ? null : conversion.conversionFactor())
                    .build();
            lines.add(line);
        }
        inventoryReturn.setLines(lines);
        return inventoryReturn;
    }

    private InventoryLedgerEntry buildReturnLedgerEntry(ReturnItemsRequest request, ReturnLineComputation computed) {
        return InventoryLedgerEntry.builder()
                .stockItemId(computed.item().getSkuId().toString())
                .eventType(InventoryLedgerEventType.RETURN_TO_STOCK)
                .changeInQuantity(computed.baseQuantity().abs())
                .quantityAfter(BigDecimal.ZERO)
                .lotId(computed.lotId())
                // #2206: the work order this return is against; no workorderLineId here — this
                // legacy path returns by SKU/quantity against consumption history, not a named
                // work order line (see submitToStock for the line-keyed path).
                .workorderId(request.getWorkorderId())
                .transactionUserId(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .notes("Returned to stock from workorder "
                        + request.getWorkorderId()
                        + ": "
                        + request.getReturnReason())
                .build();
    }

    private void validateReturnQuantity(UUID workorderId, ReturnLineComputation computed) {
        List<InventoryLedgerEntry> consumptionEntries =
                inventoryLedgerEntryRepository.findByStockItemIdAndEventTypeAndNotesContainingIgnoreCase(
                        computed.item().getSkuId().toString(),
                        InventoryLedgerEventType.WORKORDER_CONSUMPTION,
                        workorderId.toString());

        BigDecimal totalConsumed = consumptionEntries.stream()
                .map(InventoryLedgerEntry::getChangeInQuantity)
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (Quantities.lt(totalConsumed, computed.baseQuantity())) {
            throw new ReturnQuantityExceededException(
                    computed.item().getSkuId(), computed.baseQuantity(), totalConsumed);
        }
    }
}
