package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.dto.transfer.CreateTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderLineRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderLineResponse;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.TransferOrder;
import com.positivity.inventory.internal.entity.TransferOrderLine;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.TransferLocationNotEligibleException;
import com.positivity.inventory.internal.exception.TransferOrderNotFoundException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.TransferOrderRepository;
import com.positivity.inventory.service.TransferOrderService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
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
    private final InventoryFactPublisher inventoryFactPublisher;
    private final Clock clock;
    private final boolean approvalRequired;

    public TransferOrderServiceImpl(
            TransferOrderRepository transferOrderRepository,
            LocationRefRepository locationRefRepository,
            ExtStorageLocationReplicaRepository storageLocationRepository,
            InventoryFactPublisher inventoryFactPublisher,
            Clock clock,
            @Value("${pos.inventory.transfer.approval-required:false}") boolean approvalRequired) {
        this.transferOrderRepository = transferOrderRepository;
        this.locationRefRepository = locationRefRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.inventoryFactPublisher = inventoryFactPublisher;
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
                Instant.now(clock)));
    }

    String currentActor() {
        return SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
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
                .createdAt(line.getCreatedAt())
                .updatedAt(line.getUpdatedAt())
                .build();
    }
}
