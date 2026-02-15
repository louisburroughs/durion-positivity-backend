package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderPartAdjustmentEventResponse;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartAdjustmentEvent;
import com.positivity.workorder.internal.repository.WorkorderPartAdjustmentEventRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
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

/**
 * Service for managing part adjustments (substitutions, returns, corrections).
 * 
 * CAP:005 Story #157 - Handle Part Substitutions and Returns
 * 
 * <p>
 * Provides methods to:
 * </p>
 * <ul>
 * <li>Substitute one part for another (preserves original for history)</li>
 * <li>Return unused quantity</li>
 * <li>Correct part quantity (administrative corrections)</li>
 * </ul>
 * 
 * <p>
 * All operations are idempotent when an idempotency key is provided.
 * </p>
 * <p>
 * All operations append to an immutable audit trail.
 * </p>
 */
@Service
public class WorkorderPartAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(WorkorderPartAdjustmentService.class);

    private final WorkorderPartRepository workorderPartRepository;
    private final WorkorderPartAdjustmentEventRepository adjustmentEventRepository;
    private final IdempotencyService idempotencyService;

    public WorkorderPartAdjustmentService(
            WorkorderPartRepository workorderPartRepository,
            WorkorderPartAdjustmentEventRepository adjustmentEventRepository,
            IdempotencyService idempotencyService) {
        this.workorderPartRepository = workorderPartRepository;
        this.adjustmentEventRepository = adjustmentEventRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Substitute one part for another.
     * 
     * <p>
     * Creates a new WorkorderPart entry for the substitute and records a
     * SUBSTITUTION adjustment event.
     * The original part is preserved for history—it is NOT deleted.
     * </p>
     * 
     * @param workorderId      workorder ID
     * @param originalPartId   ID of the part to substitute
     * @param substitutePartId ID of the new part (must be different from original)
     * @param reason           reason for substitution (required for audit)
     * @param performedBy      user performing substitution
     * @param idempotencyKey   optional idempotency key
     * @param notes            optional additional notes
     * @return the adjustment event as DTO
     * @throws NoSuchElementException   if original part not found
     * @throws IllegalArgumentException if substitute part ID equals original part
     *                                  ID
     * @throws IllegalStateException    if original part already consumed or cannot
     *                                  be substituted
     */
    @Transactional
    @NonNull
    public WorkorderPartAdjustmentEventResponse substitutePartLine(
            @NonNull UUID workorderId,
            @NonNull UUID originalPartId,
            @NonNull UUID substitutePartId,
            @NonNull String reason,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey,
            @Nullable String notes) {

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId = idempotencyService.getExistingPartAdjustmentEventId(idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info("Idempotency key {} already processed, returning existing adjustment event {}",
                        idempotencyKey, existingEventId.get());
                return adjustmentEventRepository.findById(existingEventId.get())
                        .map(this::toResponse)
                        .orElseThrow(() -> new IllegalStateException(
                                "Adjustment event not found: " + existingEventId.get()));
            }
        }

        // Validate original part exists
        WorkorderPart originalPart = workorderPartRepository.findById(originalPartId)
                .orElseThrow(() -> new NoSuchElementException("Original part not found: " + originalPartId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(originalPart))) {
            throw new IllegalStateException("Part " + originalPartId + " does not belong to workorder " + workorderId);
        }

        // Validate substitution is possible (not already consumed)
        if (originalPart.getQuantityConsumed().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Cannot substitute part that has already been consumed");
        }

        // Validate substitute part is different
        if (originalPartId.equals(substitutePartId)) {
            throw new IllegalArgumentException("Substitute part must be different from original part");
        }

        // Create new WorkorderPart for substitute with equivalent quantities
        WorkorderPart substitutePart = WorkorderPart.builder()
                .workorder(originalPart.getWorkorder())
                .workOrderService(originalPart.getWorkOrderService())
                .productEntityId(substitutePartId)
                .description(originalPart.getDescription() + " (Substitute)") // Will be updated by caller with actual
                                                                              // description
                .quantity(originalPart.getQuantity())
                .unitPrice(originalPart.getUnitPrice())
                .lineTotal(originalPart.getLineTotal())
                .taxCode(originalPart.getTaxCode())
                .status(originalPart.getStatus())
                .quantityIssued(BigDecimal.ZERO)
                .quantityConsumed(BigDecimal.ZERO)
                .quantityReturned(BigDecimal.ZERO)
                .build();

        substitutePart = workorderPartRepository.save(substitutePart);
        log.info("Created substitute part {} for original part {}", substitutePart.getId(), originalPartId);

        // Create SUBSTITUTION adjustment event
        WorkorderPartAdjustmentEvent event = WorkorderPartAdjustmentEvent.builder()
                .originalPart(originalPart)
                .workorderId(workorderId)
                .adjustmentType("SUBSTITUTION")
                .substitutedWithPartId(substitutePart.getId())
                .quantityAdjustment(BigDecimal.ZERO) // No quantity change, just substitution
                .reason(reason)
                .performedBy(performedBy)
                .performedAt(Instant.now())
                .notes(notes)
                .build();

        event = adjustmentEventRepository.save(event);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartAdjustment(idempotencyKey, event.getId());
        }

        log.info("Substituted part {} with {} on workorder {}", originalPartId, substitutePart.getId(), workorderId);
        return toResponse(event);
    }

    /**
     * Return unused quantity of a part.
     * 
     * <p>
     * This supplements the normal return flow and records an ADDITIONAL_RETURN
     * adjustment event.
     * </p>
     * 
     * @param workorderId    workorder ID
     * @param partId         part line item ID
     * @param quantity       quantity to return (must be positive and <= available)
     * @param reason         reason for return (required for audit)
     * @param performedBy    user performing return
     * @param idempotencyKey optional idempotency key
     * @param notes          optional additional notes
     * @return the adjustment event as DTO
     * @throws NoSuchElementException   if part not found
     * @throws IllegalArgumentException if quantity exceeds available quantity
     */
    @Transactional
    @NonNull
    public WorkorderPartAdjustmentEventResponse returnUnusedQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partId,
            @NonNull BigDecimal quantity,
            @NonNull String reason,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey,
            @Nullable String notes) {

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId = idempotencyService.getExistingPartAdjustmentEventId(idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info("Idempotency key {} already processed, returning existing adjustment event {}",
                        idempotencyKey, existingEventId.get());
                return adjustmentEventRepository.findById(existingEventId.get())
                        .map(this::toResponse)
                        .orElseThrow(() -> new IllegalStateException(
                                "Adjustment event not found: " + existingEventId.get()));
            }
        }

        // Validate quantity
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Return quantity must be positive");
        }

        // Validate part exists
        WorkorderPart part = workorderPartRepository.findById(partId)
                .orElseThrow(() -> new NoSuchElementException("Part not found: " + partId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partId + " does not belong to workorder " + workorderId);
        }

        // Calculate available quantity to return (issued - consumed - already returned)
        BigDecimal availableToReturn = part.getQuantityIssued()
                .subtract(part.getQuantityConsumed())
                .subtract(part.getQuantityReturned());

        if (quantity.compareTo(availableToReturn) > 0) {
            throw new IllegalArgumentException("Return quantity " + quantity +
                    " exceeds available quantity " + availableToReturn);
        }

        // Update part.quantityReturned
        part.setQuantityReturned(part.getQuantityReturned().add(quantity));
        workorderPartRepository.save(part);

        // Create ADDITIONAL_RETURN adjustment event (negative quantity)
        WorkorderPartAdjustmentEvent event = WorkorderPartAdjustmentEvent.builder()
                .originalPart(part)
                .workorderId(workorderId)
                .adjustmentType("ADDITIONAL_RETURN")
                .substitutedWithPartId(null)
                .quantityAdjustment(quantity.negate()) // Negative for return
                .reason(reason)
                .performedBy(performedBy)
                .performedAt(Instant.now())
                .notes(notes)
                .build();

        event = adjustmentEventRepository.save(event);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartAdjustment(idempotencyKey, event.getId());
        }

        log.info("Returned {} of part {} on workorder {}", quantity, partId, workorderId);
        return toResponse(event);
    }

    /**
     * Correct part quantity (administrative correction for data entry errors).
     * 
     * @param workorderId    workorder ID
     * @param partId         part line item ID
     * @param newQuantity    new authorized quantity (must be positive)
     * @param reason         reason for correction (required for audit)
     * @param performedBy    user performing correction
     * @param idempotencyKey optional idempotency key
     * @param notes          optional additional notes
     * @return the adjustment event as DTO
     * @throws NoSuchElementException   if part not found
     * @throws IllegalArgumentException if new quantity is not positive
     */
    @Transactional
    @NonNull
    public WorkorderPartAdjustmentEventResponse correctPartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partId,
            @NonNull BigDecimal newQuantity,
            @NonNull String reason,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey,
            @Nullable String notes) {

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingEventId = idempotencyService.getExistingPartAdjustmentEventId(idempotencyKey);
            if (existingEventId.isPresent()) {
                log.info("Idempotency key {} already processed, returning existing adjustment event {}",
                        idempotencyKey, existingEventId.get());
                return adjustmentEventRepository.findById(existingEventId.get())
                        .map(this::toResponse)
                        .orElseThrow(() -> new IllegalStateException(
                                "Adjustment event not found: " + existingEventId.get()));
            }
        }

        // Validate new quantity
        if (newQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("New quantity must be positive");
        }

        // Validate part exists
        WorkorderPart part = workorderPartRepository.findById(partId)
                .orElseThrow(() -> new NoSuchElementException("Part not found: " + partId));

        // Verify part belongs to workorder
        if (!workorderId.equals(getWorkorderIdForPart(part))) {
            throw new IllegalStateException("Part " + partId + " does not belong to workorder " + workorderId);
        }

        // Calculate adjustment
        BigDecimal oldQuantity = part.getQuantity();
        BigDecimal adjustment = newQuantity.subtract(oldQuantity);

        // Update part.quantity (authorized quantity)
        part.setQuantity(newQuantity);
        // Also update lineTotal if unitPrice exists
        if (part.getUnitPrice() != null) {
            part.setLineTotal(newQuantity.multiply(part.getUnitPrice()));
        }
        workorderPartRepository.save(part);

        // Create CORRECTION adjustment event
        WorkorderPartAdjustmentEvent event = WorkorderPartAdjustmentEvent.builder()
                .originalPart(part)
                .workorderId(workorderId)
                .adjustmentType("CORRECTION")
                .substitutedWithPartId(null)
                .quantityAdjustment(adjustment)
                .reason(reason)
                .performedBy(performedBy)
                .performedAt(Instant.now())
                .notes(notes)
                .build();

        event = adjustmentEventRepository.save(event);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.markKeyProcessedForPartAdjustment(idempotencyKey, event.getId());
        }

        log.info("Corrected quantity of part {} from {} to {} on workorder {}",
                partId, oldQuantity, newQuantity, workorderId);
        return toResponse(event);
    }

    /**
     * Get adjustment history for a workorder.
     * 
     * @param workorderId workorder ID
     * @return list of adjustment events as DTOs (newest first)
     */
    @Transactional(readOnly = true)
    @NonNull
    public List<WorkorderPartAdjustmentEventResponse> getAdjustmentHistory(@NonNull UUID workorderId) {
        return adjustmentEventRepository.findByWorkorderIdOrderByPerformedAtDesc(workorderId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get adjustment history for a specific part.
     * 
     * @param partId part line item ID
     * @return list of adjustment events as DTOs (newest first)
     */
    @Transactional(readOnly = true)
    @NonNull
    public List<WorkorderPartAdjustmentEventResponse> getPartAdjustmentHistory(@NonNull UUID partId) {
        return adjustmentEventRepository.findByOriginalPartIdOrderByPerformedAtDesc(partId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Convert entity to DTO.
     * Package-private for testing purposes.
     */
    @NonNull
    WorkorderPartAdjustmentEventResponse toResponse(@NonNull WorkorderPartAdjustmentEvent event) {
        return WorkorderPartAdjustmentEventResponse.builder()
                .id(event.getId())
                .originalPartId(event.getOriginalPart().getId())
                .originalPartDescription(event.getOriginalPart().getDescription())
                .workorderId(event.getWorkorderId())
                .adjustmentType(event.getAdjustmentType())
                .substitutedWithPartId(event.getSubstitutedWithPartId())
                .substitutedWithPartDescription(null) // Would need additional lookup if needed
                .quantityAdjustment(event.getQuantityAdjustment())
                .reason(event.getReason())
                .performedBy(event.getPerformedBy())
                .performedAt(event.getPerformedAt())
                .notes(event.getNotes())
                .build();
    }

    /**
     * Helper to get workorder ID from a WorkorderPart.
     */
    private UUID getWorkorderIdForPart(WorkorderPart part) {
        if (part.getWorkorder() != null) {
            return part.getWorkorder().getId();
        } else if (part.getWorkOrderService() != null && part.getWorkOrderService().getWorkOrder() != null) {
            return part.getWorkOrderService().getWorkOrder().getId();
        } else {
            throw new IllegalStateException("Part has no workorder reference");
        }
    }
}
