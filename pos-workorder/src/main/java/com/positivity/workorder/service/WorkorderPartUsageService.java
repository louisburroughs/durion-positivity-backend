package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderPartUsageEventResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderPartUsageEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.security.common.SecurityContextHelper;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
public class WorkorderPartUsageService {
    private static final Logger log = LoggerFactory.getLogger(WorkorderPartUsageService.class);

    private final WorkorderRepository workorderRepository;
    private final WorkorderPartRepository workorderPartRepository;
    private final WorkorderPartUsageEventRepository usageEventRepository;
    private final IdempotencyService idempotencyService;

    public WorkorderPartUsageService(
            WorkorderRepository workorderRepository,
            WorkorderPartRepository workorderPartRepository,
            WorkorderPartUsageEventRepository usageEventRepository,
            IdempotencyService idempotencyService) {
        this.workorderRepository = workorderRepository;
        this.workorderPartRepository = workorderPartRepository;
        this.usageEventRepository = usageEventRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Issue parts to a workorder, reserving them for consumption.
     * 
     * @param workorderId    workorder ID
     * @param partLineId     part line item ID
     * @param quantity       quantity to issue (must be positive)
     * @param performedBy    user issuing parts
     * @param idempotencyKey optional idempotency key
     * @return the created usage event
     * @throws NoSuchElementException   if workorder or part not found
     * @throws IllegalArgumentException if quantity is not positive
     * @throws IllegalStateException    if part line does not belong to workorder
     */
    @Transactional
    @NonNull
    public WorkorderPartUsageEvent issuePartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey) {
        String actorId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId = idempotencyService.getExistingPartUsageEventId(idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info("Idempotency key {} already processed, returning existing event {}",
                        idempotencyKey, existingEventId.get());
                return usageEventRepository.findById(existingEventId.get())
                        .orElseThrow(() -> new IllegalStateException("Event not found: " + existingEventId.get()));
            }
        }

        // Validate quantity
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // Validate workorder and part exist
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new NoSuchElementException("Workorder not found: " + workorderId));

        WorkorderPart part = workorderPartRepository.findById(partLineId)
                .orElseThrow(() -> new IllegalArgumentException("Part not found: " + partLineId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partLineId + " does not belong to workorder " + workorderId);
        }

        // Create usage event
        WorkorderPartUsageEvent event = WorkorderPartUsageEvent.builder()
                .workorderPart(part)
                .workorderId(workorderId)
                .eventType("ISSUE")
                .quantity(quantity)
                .performedBy(actorId)
                .performedAt(Instant.now())
                .notes(null)
                .build();

        event = usageEventRepository.save(event);

        // Update part totals
        part.setQuantityIssued(part.getQuantityIssued().add(quantity));
        workorderPartRepository.save(part);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartUsage(idempotencyKey, event.getId());
        }

        log.info("Issued {} of part {} for workorder {}", quantity, partLineId, workorderId);
        return event;
    }

    /**
     * Consume parts on a workorder.
     * 
     * @param workorderId    workorder ID
     * @param partLineId     part line item ID
     * @param quantity       quantity to consume (must be positive)
     * @param performedBy    user consuming parts
     * @param idempotencyKey optional idempotency key
     * @return the created usage event
     * @throws NoSuchElementException   if workorder or part not found
     * @throws IllegalArgumentException if quantity exceeds issued quantity
     */
    @Transactional
    @NonNull
    public WorkorderPartUsageEvent consumePartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey) {
        String actorId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId = idempotencyService.getExistingPartUsageEventId(idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info("Idempotency key {} already processed, returning existing event {}",
                        idempotencyKey, existingEventId.get());
                return usageEventRepository.findById(existingEventId.get())
                        .orElseThrow(() -> new IllegalStateException("Event not found: " + existingEventId.get()));
            }
        }

        // Validate quantity
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // Validate workorder and part exist
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new NoSuchElementException("Workorder not found: " + workorderId));

        WorkorderPart part = workorderPartRepository.findById(partLineId)
                .orElseThrow(() -> new IllegalArgumentException("Part not found: " + partLineId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partLineId + " does not belong to workorder " + workorderId);
        }

        // Validate consumption does not exceed issued
        BigDecimal newConsumed = part.getQuantityConsumed().add(quantity);
        if (newConsumed.compareTo(part.getQuantityIssued()) > 0) {
            throw new IllegalArgumentException(
                    String.format(
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
                .performedAt(Instant.now())
                .notes(null)
                .build();

        event = usageEventRepository.save(event);

        // Update part totals
        part.setQuantityConsumed(newConsumed);
        workorderPartRepository.save(part);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartUsage(idempotencyKey, event.getId());
        }

        log.info("Consumed {} of part {} for workorder {}", quantity, partLineId, workorderId);
        return event;
    }

    /**
     * Return unused parts to inventory.
     * 
     * @param workorderId    workorder ID
     * @param partLineId     part line item ID
     * @param quantity       quantity to return (must be positive)
     * @param performedBy    user returning parts
     * @param idempotencyKey optional idempotency key
     * @return the created usage event
     * @throws NoSuchElementException   if workorder or part not found
     * @throws IllegalArgumentException if quantity exceeds available (issued -
     *                                  consumed)
     */
    @Transactional
    @NonNull
    public WorkorderPartUsageEvent returnPartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey) {
        String actorId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId = idempotencyService.getExistingPartUsageEventId(idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info("Idempotency key {} already processed, returning existing event {}",
                        idempotencyKey, existingEventId.get());
                return usageEventRepository.findById(existingEventId.get())
                        .orElseThrow(() -> new IllegalStateException("Event not found: " + existingEventId.get()));
            }
        }

        // Validate quantity
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // Validate workorder and part exist
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new NoSuchElementException("Workorder not found: " + workorderId));

        WorkorderPart part = workorderPartRepository.findById(partLineId)
                .orElseThrow(() -> new IllegalArgumentException("Part not found: " + partLineId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partLineId + " does not belong to workorder " + workorderId);
        }

        // Validate return does not exceed available (issued - consumed - already
        // returned)
        BigDecimal available = part.getQuantityIssued()
                .subtract(part.getQuantityConsumed())
                .subtract(part.getQuantityReturned());

        if (quantity.compareTo(available) > 0) {
            throw new IllegalArgumentException(
                    String.format(
                            "Return quantity exceeds available. Issued: %s, Consumed: %s, Already returned: %s, Available: %s, Attempting to return: %s",
                            part.getQuantityIssued(), part.getQuantityConsumed(), part.getQuantityReturned(), available,
                            quantity));
        }

        // Create usage event
        WorkorderPartUsageEvent event = WorkorderPartUsageEvent.builder()
                .workorderPart(part)
                .workorderId(workorderId)
                .eventType("RETURN")
                .quantity(quantity)
                .performedBy(actorId)
                .performedAt(Instant.now())
                .notes(null)
                .build();

        event = usageEventRepository.save(event);

        // Update part totals
        part.setQuantityReturned(part.getQuantityReturned().add(quantity));
        workorderPartRepository.save(part);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartUsage(idempotencyKey, event.getId());
        }

        log.info("Returned {} of part {} for workorder {}", quantity, partLineId, workorderId);
        return event;
    }

    /**
     * Get usage history for a specific part (newest first).
     */
    @Transactional(readOnly = true)
    @NonNull
    public List<WorkorderPartUsageEventResponse> getUsageHistory(@NonNull UUID workorderId, @NonNull UUID partLineId) {
        // Validate workorder exists
        workorderRepository.findById(workorderId)
                .orElseThrow(() -> new NoSuchElementException("Workorder not found: " + workorderId));

        // Validate part exists
        WorkorderPart part = workorderPartRepository.findById(partLineId)
                .orElseThrow(() -> new IllegalArgumentException("Part not found: " + partLineId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partLineId + " does not belong to workorder " + workorderId);
        }

        return usageEventRepository.findByWorkorderPartIdOrderByPerformedAtDesc(partLineId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get usage history for all parts on a workorder (newest first).
     */
    @Transactional(readOnly = true)
    @NonNull
    public List<WorkorderPartUsageEventResponse> getAllUsageHistory(@NonNull UUID workorderId) {
        // Validate workorder exists
        workorderRepository.findById(workorderId)
                .orElseThrow(() -> new NoSuchElementException("Workorder not found: " + workorderId));

        return usageEventRepository.findByWorkorderIdOrderByPerformedAtDesc(workorderId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
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
        } else if (part.getWorkOrderService() != null && part.getWorkOrderService().getWorkOrder() != null) {
            return part.getWorkOrderService().getWorkOrder().getId();
        }
        throw new IllegalStateException("Part has no associated workorder");
    }
}
