package com.positivity.workorder.internal.service;

import com.positivity.domainevents.AggregateTouch;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.config.InventoryCommandPublisher;
import com.positivity.workorder.internal.dto.WorkorderPartUsageEventResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.InsufficientPartAvailabilityException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderPartUsageEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.IdempotencyService;
import com.positivity.workorder.service.WorkorderPartUsageService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for tracking parts usage on workorders.
 *
 * CAP:005 Story #158 - Parts Usage Tracking
 *
 * Provides methods to issue, consume, and return parts.
 * All operations are idempotent when an idempotency key is provided.
 *
 * NOTE: Inventory service integration is stubbed (501 Not Implemented).
 * This service does NOT check inventory availability or create inventory
 * transactions. These features will be added when inventory service
 * becomes available (see pos-inventory expansion roadmap).
 */
@Service
public class WorkorderPartUsageServiceImpl implements WorkorderPartUsageService {
    private static final String MISSING_AUTHENTICATED_USERNAME = "Missing authenticated username";

    private final Clock clock;

    private static final String PART_NOT_FOUND = "Part not found: ";
    private static final String IDEMPOTENCY_KEY_ALREADY_PROCESSED_RETURNING_EXISTING_EVENT =
            "Idempotency key already processed, returning existing event {}";
    private static final String EVENT_NOT_FOUND = "Event not found: ";
    private static final String IDEMPOTENCY_OPERATION_PART_ISSUE = "part-usage.issue";
    private static final String IDEMPOTENCY_OPERATION_PART_CONSUME = "part-usage.consume";
    private static final String IDEMPOTENCY_OPERATION_PART_RETURN = "part-usage.return";

    private static final Logger log = LoggerFactory.getLogger(WorkorderPartUsageServiceImpl.class);

    private final WorkorderRepository workorderRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final WorkorderPartUsageEventRepository usageEventRepository;
    private final IdempotencyService idempotencyService;
    private final WorkorderFactPublisher workorderFactPublisher;
    private final PartAvailabilityService partAvailabilityService;
    private final PartQuantityDivisibilityService partQuantityDivisibilityService;
    private final WorkorderStateMachine workorderStateMachine;

    /**
     * Absent when this module runs with Kafka disabled (local/dev profiles). The gate still reads
     * the replica in that case; it simply registers no demand with pos-inventory, which is the
     * position every profile was in before CAP #1315.
     */
    private final ObjectProvider<InventoryCommandPublisher> inventoryCommandPublisher;

    public WorkorderPartUsageServiceImpl(
            WorkorderRepository workorderRepository,
            WorkorderPartRepository workorderPartRepository,
            WorkorderPartUsageEventRepository usageEventRepository,
            IdempotencyService idempotencyService,
            WorkorderFactPublisher workorderFactPublisher,
            PartAvailabilityService partAvailabilityService,
            PartQuantityDivisibilityService partQuantityDivisibilityService,
            WorkorderStateMachine workorderStateMachine,
            ObjectProvider<InventoryCommandPublisher> inventoryCommandPublisher,
            Clock clock) {
        this.clock = clock;
        this.workorderRepository = workorderRepository;
        this.workorderPartRepository = workorderPartRepository;
        this.usageEventRepository = usageEventRepository;
        this.idempotencyService = idempotencyService;
        this.workorderFactPublisher = workorderFactPublisher;
        this.partAvailabilityService = partAvailabilityService;
        this.partQuantityDivisibilityService = partQuantityDivisibilityService;
        this.workorderStateMachine = workorderStateMachine;
        this.inventoryCommandPublisher = inventoryCommandPublisher;
    }

    /**
     * Issue parts to a workorder, reserving them for consumption.
     *
     * @param workorderId    workorder ID
     * @param partLineId     part line item ID
     * @param quantity       quantity to issue (must be positive)
     * @param uomCode        unit {@code quantity} is expressed in; {@code null} means the
     *                       product's base unit
     * @param idempotencyKey optional idempotency key
     * @return the created usage event
     * @throws WorkorderNotFoundException if the workorder does not exist; IllegalArgumentException if the part is not found
     * @throws IllegalArgumentException if quantity is not positive
     * @throws IllegalStateException    if part line does not belong to workorder
     */
    @Transactional
    @NonNull
    public WorkorderPartUsageEvent issuePartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @Nullable String uomCode,
            @Nullable String idempotencyKey) {
        String actorId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(MISSING_AUTHENTICATED_USERNAME));

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId =
                    idempotencyService.getExistingPartUsageEventId(IDEMPOTENCY_OPERATION_PART_ISSUE, idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info(IDEMPOTENCY_KEY_ALREADY_PROCESSED_RETURNING_EXISTING_EVENT, existingEventId.get());
                return usageEventRepository
                        .findById(existingEventId.get())
                        .orElseThrow(() -> new IllegalStateException(EVENT_NOT_FOUND + existingEventId.get()));
            }
        }

        // Validate quantity
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // Validate workorder and part exist
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        WorkorderPart part = workorderPartRepository
                .findById(partLineId)
                .orElseThrow(() -> new IllegalArgumentException(PART_NOT_FOUND + partLineId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partLineId + " does not belong to workorder " + workorderId);
        }

        requirePermittedScale(part, quantity, uomCode);
        requireOwnedStock(workorder, part, quantity);

        // Create usage event
        WorkorderPartUsageEvent event = WorkorderPartUsageEvent.builder()
                .workorderPart(part)
                .workorderId(workorderId)
                .eventType("ISSUE")
                .quantity(quantity)
                .performedBy(actorId)
                .performedAt(Instant.now(clock))
                .notes(null)
                .build();

        event = usageEventRepository.save(event);

        // Update part totals
        part.setQuantityIssued(part.getQuantityIssued().add(quantity));
        workorderPartRepository.save(part);
        // This write only touches workorder_part; dirty the workorder row itself so the publisher's
        // flush has a pending @Version increment to pick up (#1486) — a clean row leaves the
        // emitted fact carrying stale part totals under an unchanged aggregateVersion.
        workorder.setUpdatedAt(AggregateTouch.monotonicUpdatedAt(workorder.getUpdatedAt(), clock));
        workorderRepository.save(workorder);
        workorderFactPublisher.markChanged(part.getWorkorder().getId());

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartUsage(
                    IDEMPOTENCY_OPERATION_PART_ISSUE, idempotencyKey, event.getId());
        }

        registerDemand(workorder, part, quantity, uomCode);

        log.info("Issued part quantity for workorder {}", workorderId);
        return event;
    }

    /**
     * The per-part gate (CAP #1315): a part is issued only when owned stock at the servicing site
     * covers it.
     *
     * <h2>Why the gate sits here and not on {@code startWorkorder()}</h2>
     *
     * Starting a job is planning; issuing a part is the moment metal is supposed to leave the
     * shelf. Blocking the start would stop a technician working on the four items whose parts are
     * present because a fifth is not. Blocking the issue stops exactly the item that cannot
     * proceed, which is the smallest true statement the shortfall supports.
     *
     * <h2>Carrying the job to AWAITING_PARTS</h2>
     *
     * When nothing outstanding on the job can be issued, the job really is waiting on parts and is
     * moved into the status that already means that. When something else can still be issued the
     * status is left alone — the job is not blocked, one item is. The transition is attempted only
     * from {@code WORK_IN_PROGRESS} because that is the only source the state table allows into
     * {@code AWAITING_PARTS}; from anywhere else the shortfall still blocks the part, it simply
     * does not move the job.
     */
    private void requireOwnedStock(Workorder workorder, WorkorderPart part, BigDecimal quantity) {
        if (partAvailabilityService.covers(workorder, part, quantity)) {
            return;
        }
        carryToAwaitingPartsIfWhollyBlocked(workorder);
        throw new InsufficientPartAvailabilityException(
                part.getId(),
                part.getDescription() == null ? String.valueOf(part.getProductEntityId()) : part.getDescription(),
                quantity,
                partAvailabilityService.availableFor(workorder, part));
    }

    private void carryToAwaitingPartsIfWhollyBlocked(Workorder workorder) {
        if (workorder.getStatus() != WorkorderStatus.WORK_IN_PROGRESS) {
            return;
        }
        boolean anythingIssuable = workorderPartRepository.findByWorkorderId(workorder.getId()).stream()
                .filter(WorkorderPartUsageServiceImpl::hasOutstandingQuantity)
                .anyMatch(other -> partAvailabilityService.covers(workorder, other, outstandingQuantity(other)));
        if (anythingIssuable) {
            return;
        }
        try {
            workorderStateMachine.transitionWorkorder(
                    workorder.getId(),
                    WorkorderStatus.AWAITING_PARTS,
                    SecurityContextHelper.getCurrentUsername().orElse("system"),
                    "No outstanding part can be issued from owned stock at the servicing site");
        } catch (RuntimeException e) {
            // The shortfall itself is the answer the caller needs. A status transition the state
            // machine refuses must not replace that with a less useful error.
            log.warn("Could not carry workorder {} into AWAITING_PARTS: {}", workorder.getId(), e.getMessage());
        }
    }

    private static boolean hasOutstandingQuantity(WorkorderPart part) {
        return outstandingQuantity(part).compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal outstandingQuantity(WorkorderPart part) {
        BigDecimal ordered = part.getQuantity() == null ? BigDecimal.ZERO : part.getQuantity();
        BigDecimal issued = part.getQuantityIssued() == null ? BigDecimal.ZERO : part.getQuantityIssued();
        return ordered.subtract(issued);
    }

    /**
     * Asks pos-inventory to reserve what was just issued. The gate above decided what to tell the
     * technician from the replica; this asks the owner to decide from its own ledger, and the
     * outcome fact corrects the part if the two disagree.
     *
     * <p>The quantity passes through unchanged since ADR-0055 stage 2 (#1414): the reservation
     * command and its outcome fact carry a {@code BigDecimal}, so there is no longer a seam to
     * convert across. What guarantees the quantity is legitimate is the divisibility gate at
     * estimate-item entry and part issue (#1413), not a conversion here — the product's declared
     * {@code precision_scale} has already decided whether these decimals are allowed at all.
     *
     * <p>{@code uomCode} travels alongside it unconverted (ADR-0055 stage 3, #1415): pos-inventory
     * owns the actual document-to-base conversion for the reservation, using {@code DOWN}
     * rounding so it never promises more than exists. This module's own gate has already checked
     * the converted quantity fits the product's declared scale; it does not repeat the conversion
     * here for anything to be posted.
     */
    private void registerDemand(Workorder workorder, WorkorderPart part, BigDecimal quantity, String uomCode) {
        InventoryCommandPublisher publisher = inventoryCommandPublisher.getIfAvailable();
        if (publisher == null || workorder.getShopId() == null || part.getProductEntityId() == null) {
            return;
        }
        publisher.requestReservation(part.getId(), part.getProductEntityId(), quantity, workorder.getShopId(), uomCode);
    }

    /**
     * The issue, consume and return paths are the backstop, not the gate (ADR-0055, #1413). The
     * real gate is at estimate-item entry and promotion, because a fractional quantity that reaches
     * {@code workorder_part.quantity} can never be issued down to zero and would hold the job in
     * AWAITING_PARTS forever. This catches a quantity keyed directly at the counter, and a line
     * that predates the product's declaration.
     */
    private void requirePermittedScale(WorkorderPart part, BigDecimal quantity, String uomCode) {
        partQuantityDivisibilityService.requirePermittedScale(
                part.getProductEntityId(), part.getDescription(), quantity, uomCode);
    }

    /**
     * Consume parts on a workorder.
     *
     * @param workorderId    workorder ID
     * @param partLineId     part line item ID
     * @param quantity       quantity to consume (must be positive)
     * @param uomCode        unit {@code quantity} is expressed in; {@code null} means the
     *                       product's base unit
     * @param idempotencyKey optional idempotency key
     * @return the created usage event
     * @throws WorkorderNotFoundException if the workorder does not exist; IllegalArgumentException if the part is not found
     * @throws IllegalArgumentException if quantity exceeds issued quantity
     */
    @Transactional
    @NonNull
    public WorkorderPartUsageEvent consumePartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @Nullable String uomCode,
            @Nullable String idempotencyKey) {
        String actorId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(MISSING_AUTHENTICATED_USERNAME));

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId =
                    idempotencyService.getExistingPartUsageEventId(IDEMPOTENCY_OPERATION_PART_CONSUME, idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info(IDEMPOTENCY_KEY_ALREADY_PROCESSED_RETURNING_EXISTING_EVENT, existingEventId.get());
                return usageEventRepository
                        .findById(existingEventId.get())
                        .orElseThrow(() -> new IllegalStateException(EVENT_NOT_FOUND + existingEventId.get()));
            }
        }

        // Validate quantity
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // Validate workorder and part exist
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        WorkorderPart part = workorderPartRepository
                .findById(partLineId)
                .orElseThrow(() -> new IllegalArgumentException(PART_NOT_FOUND + partLineId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partLineId + " does not belong to workorder " + workorderId);
        }

        requirePermittedScale(part, quantity, uomCode);

        // Validate consumption does not exceed issued
        BigDecimal newConsumed = part.getQuantityConsumed().add(quantity);
        if (newConsumed.compareTo(part.getQuantityIssued()) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Consumption exceeds issued quantity. Issued: %s, Already consumed: %s, Attempting to consume: %s",
                    part.getQuantityIssued(), part.getQuantityConsumed(), quantity));
        }

        // Create usage event
        WorkorderPartUsageEvent event = WorkorderPartUsageEvent.builder()
                .workorderPart(part)
                .workorderId(workorderId)
                .eventType("CONSUME")
                .quantity(quantity)
                .performedBy(actorId)
                .performedAt(Instant.now(clock))
                .notes(null)
                .build();

        event = usageEventRepository.save(event);

        // Update part totals
        part.setQuantityConsumed(newConsumed);
        workorderPartRepository.save(part);
        // This write only touches workorder_part; dirty the workorder row itself so the publisher's
        // flush has a pending @Version increment to pick up (#1486) — a clean row leaves the
        // emitted fact carrying stale part totals under an unchanged aggregateVersion.
        workorder.setUpdatedAt(AggregateTouch.monotonicUpdatedAt(workorder.getUpdatedAt(), clock));
        workorderRepository.save(workorder);
        workorderFactPublisher.markChanged(part.getWorkorder().getId());

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartUsage(
                    IDEMPOTENCY_OPERATION_PART_CONSUME, idempotencyKey, event.getId());
        }

        log.info("Consumed part quantity for workorder {}", workorderId);
        return event;
    }

    /**
     * Return unused parts to inventory.
     *
     * @param workorderId    workorder ID
     * @param partLineId     part line item ID
     * @param quantity       quantity to return (must be positive)
     * @param uomCode        unit {@code quantity} is expressed in; {@code null} means the
     *                       product's base unit
     * @param idempotencyKey optional idempotency key
     * @return the created usage event
     * @throws WorkorderNotFoundException if the workorder does not exist; IllegalArgumentException if the part is not found
     * @throws IllegalArgumentException if quantity exceeds available (issued -
     *                                  consumed)
     */
    @Transactional
    @NonNull
    public WorkorderPartUsageEvent returnPartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @Nullable String uomCode,
            @Nullable String idempotencyKey) {
        String actorId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(MISSING_AUTHENTICATED_USERNAME));

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId =
                    idempotencyService.getExistingPartUsageEventId(IDEMPOTENCY_OPERATION_PART_RETURN, idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info(IDEMPOTENCY_KEY_ALREADY_PROCESSED_RETURNING_EXISTING_EVENT, existingEventId.get());
                return usageEventRepository
                        .findById(existingEventId.get())
                        .orElseThrow(() -> new IllegalStateException(EVENT_NOT_FOUND + existingEventId.get()));
            }
        }

        // Validate quantity
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // Validate workorder and part exist
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        WorkorderPart part = workorderPartRepository
                .findById(partLineId)
                .orElseThrow(() -> new IllegalArgumentException(PART_NOT_FOUND + partLineId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partLineId + " does not belong to workorder " + workorderId);
        }

        requirePermittedScale(part, quantity, uomCode);

        // Validate return does not exceed available (issued - consumed - already
        // returned)
        BigDecimal available =
                part.getQuantityIssued().subtract(part.getQuantityConsumed()).subtract(part.getQuantityReturned());

        if (quantity.compareTo(available) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Return quantity exceeds available. Issued: %s, Consumed: %s, Already returned: %s, Available: %s, Attempting to return: %s",
                    part.getQuantityIssued(),
                    part.getQuantityConsumed(),
                    part.getQuantityReturned(),
                    available,
                    quantity));
        }

        // Create usage event
        WorkorderPartUsageEvent event = WorkorderPartUsageEvent.builder()
                .workorderPart(part)
                .workorderId(workorderId)
                .eventType("RETURN")
                .quantity(quantity)
                .performedBy(actorId)
                .performedAt(Instant.now(clock))
                .notes(null)
                .build();

        event = usageEventRepository.save(event);

        // Update part totals
        part.setQuantityReturned(part.getQuantityReturned().add(quantity));
        workorderPartRepository.save(part);
        // This write only touches workorder_part; dirty the workorder row itself so the publisher's
        // flush has a pending @Version increment to pick up (#1486) — a clean row leaves the
        // emitted fact carrying stale part totals under an unchanged aggregateVersion.
        workorder.setUpdatedAt(AggregateTouch.monotonicUpdatedAt(workorder.getUpdatedAt(), clock));
        workorderRepository.save(workorder);
        workorderFactPublisher.markChanged(part.getWorkorder().getId());

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartUsage(
                    IDEMPOTENCY_OPERATION_PART_RETURN, idempotencyKey, event.getId());
        }

        log.info("Returned part quantity for workorder {}", workorderId);
        return event;
    }

    /**
     * Get usage history for a specific part (newest first).
     */
    @Transactional(readOnly = true)
    @NonNull
    public List<WorkorderPartUsageEventResponse> getUsageHistory(@NonNull UUID workorderId, @NonNull UUID partLineId) {
        // Validate workorder exists
        workorderRepository.findById(workorderId).orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        // Validate part exists
        WorkorderPart part = workorderPartRepository
                .findById(partLineId)
                .orElseThrow(() -> new IllegalArgumentException(PART_NOT_FOUND + partLineId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partLineId + " does not belong to workorder " + workorderId);
        }

        return usageEventRepository.findByWorkorderPartIdOrderByPerformedAtDesc(partLineId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get usage history for all parts on a workorder (newest first).
     */
    @Transactional(readOnly = true)
    @NonNull
    public List<WorkorderPartUsageEventResponse> getAllUsageHistory(@NonNull UUID workorderId) {
        // Validate workorder exists
        workorderRepository.findById(workorderId).orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        return usageEventRepository.findByWorkorderIdOrderByPerformedAtDesc(workorderId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Helper to convert entity to response DTO.
     */
    private WorkorderPartUsageEventResponse toResponse(WorkorderPartUsageEvent event) {
        return WorkorderPartUsageEventResponse.builder()
                .id(event.getId())
                .workorderPartId(event.getWorkorderPart().getId())
                .workorderId(event.getWorkorderId())
                .eventType(event.getEventType())
                .quantity(event.getQuantity())
                .performedBy(event.getPerformedBy())
                .performedAt(event.getPerformedAt())
                .notes(event.getNotes())
                .partDescription(event.getWorkorderPart().getDescription())
                .build();
    }

    /**
     * Helper to get workorder ID from a part (handles both direct and
     * service-linked parts).
     */
    private UUID getWorkorderIdForPart(WorkorderPart part) {
        if (part.getWorkorder() != null) {
            return part.getWorkorder().getId();
        } else if (part.getWorkOrderService() != null
                && part.getWorkOrderService().getWorkOrder() != null) {
            return part.getWorkOrderService().getWorkOrder().getId();
        }
        throw new IllegalStateException("Part has no associated workorder");
    }
}
