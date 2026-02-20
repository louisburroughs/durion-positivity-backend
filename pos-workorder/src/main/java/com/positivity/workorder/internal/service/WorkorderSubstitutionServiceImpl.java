package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderPartAdjustmentEventResponse;
import com.positivity.workorder.internal.enums.PriceLockStatus;
import com.positivity.workorder.internal.entity.SubstitutionStatus;
import com.positivity.workorder.internal.entity.WorkOrderPartSubstitution;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartAdjustmentEvent;
import com.positivity.workorder.internal.repository.WorkOrderPartSubstitutionRepository;
import com.positivity.workorder.internal.repository.WorkorderPartAdjustmentEventRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.service.WorkorderSubstitutionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal implementation of workorder substitution service.
 *
 * Issue: #49
 */
@Service
public class WorkorderSubstitutionServiceImpl implements WorkorderSubstitutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkorderSubstitutionServiceImpl.class);

    private final WorkorderPartRepository workorderPartRepository;
    private final WorkOrderPartSubstitutionRepository substitutionRepository;
    private final WorkorderPartAdjustmentEventRepository adjustmentEventRepository;
    private final ObjectMapper objectMapper;

    public WorkorderSubstitutionServiceImpl(
            WorkorderPartRepository workorderPartRepository,
            WorkOrderPartSubstitutionRepository substitutionRepository,
            WorkorderPartAdjustmentEventRepository adjustmentEventRepository,
            ObjectMapper objectMapper) {
        this.workorderPartRepository = workorderPartRepository;
        this.substitutionRepository = substitutionRepository;
        this.adjustmentEventRepository = adjustmentEventRepository;
        this.objectMapper = objectMapper;
    }

    @Override
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

        WorkorderPart originalPart = workorderPartRepository.findById(originalPartId)
                .orElseThrow(() -> new NoSuchElementException("Original part not found: " + originalPartId));

        UUID partWorkorderId = originalPart.getWorkorder() != null
                ? originalPart.getWorkorder().getId()
                : originalPart.getWorkOrderService().getWorkOrder().getId();

        if (!workorderId.equals(partWorkorderId)) {
            throw new IllegalStateException("Part " + originalPartId + " does not belong to workorder " + workorderId);
        }

        if (originalPart.getQuantityConsumed().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Cannot substitute part that has already been consumed");
        }

        if (originalPartId.equals(substitutePartId)) {
            throw new IllegalArgumentException("Substitute part must be different from original part");
        }

        UUID originalProductReference = resolveOriginalProductReference(originalPart, originalPartId);

        // Issue #49: lock original part pricing and mark substitution metadata.
        originalPart.setIsSubstituted(true);
        originalPart.setPriceLockStatus(PriceLockStatus.LOCKED);
        if (originalPart.getOriginalProductId() == null) {
            originalPart.setOriginalProductId(originalProductReference);
        }
        workorderPartRepository.save(originalPart);

        WorkorderPart substitutePart = WorkorderPart.builder()
                .workorder(originalPart.getWorkorder())
                .workOrderService(originalPart.getWorkOrderService())
                .productEntityId(substitutePartId)
                .description(originalPart.getDescription() + " (Substitute)")
                .quantity(originalPart.getQuantity())
                .unitPrice(originalPart.getUnitPrice())
                .lineTotal(originalPart.getLineTotal())
                .taxCode(originalPart.getTaxCode())
                .status(originalPart.getStatus())
                .quantityIssued(BigDecimal.ZERO)
                .quantityConsumed(BigDecimal.ZERO)
                .quantityReturned(BigDecimal.ZERO)
                .isSubstituted(true)
                .priceLockStatus(PriceLockStatus.LOCKED)
                .originalProductId(originalProductReference)
                .build();

        substitutePart = workorderPartRepository.save(substitutePart);

        // Issue #49: persist immutable substitution record with pricing snapshot.
        WorkOrderPartSubstitution substitutionRecord = WorkOrderPartSubstitution.builder()
                .workorderId(workorderId)
                .workorderLineItemId(originalPartId)
                .originalProductId(originalProductReference)
                .originalPartNumberSnapshot(originalPart.getDescription())
                .substituteProductId(substitutePartId)
                .substitutePartNumberSnapshot(substitutePart.getDescription())
                .selectedBy(performedBy.toString())
                .selectedAt(Instant.now())
                .reasonCode(reason)
                .pricingSnapshot(buildPricingSnapshot(originalPart))
                .status(SubstitutionStatus.APPLIED)
                .build();
        substitutionRepository.save(substitutionRecord);

        WorkorderPartAdjustmentEvent event = WorkorderPartAdjustmentEvent.builder()
                .originalPart(originalPart)
                .workorderId(workorderId)
                .adjustmentType("SUBSTITUTION")
                .substitutedWithPartId(substitutePart.getId())
                .quantityAdjustment(BigDecimal.ZERO)
                .reason(reason)
                .performedBy(performedBy)
                .performedAt(Instant.now())
                .notes(notes)
                .build();

        event = adjustmentEventRepository.save(event);

        log.info("Substituted part {} with {} on workorder {}", originalPartId, substitutePart.getId(), workorderId);
        return toResponse(event);
    }

    private UUID resolveOriginalProductReference(WorkorderPart originalPart, UUID originalPartId) {
        if (originalPart.getProductEntityId() != null) {
            return originalPart.getProductEntityId();
        }
        if (originalPart.getNonInventoryProductEntityId() != null) {
            return originalPart.getNonInventoryProductEntityId();
        }
        return originalPartId;
    }

    private String buildPricingSnapshot(WorkorderPart part) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "unitPrice", part.getUnitPrice(),
                    "lineTotal", part.getLineTotal()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to serialize pricing snapshot", exception);
        }
    }

    private WorkorderPartAdjustmentEventResponse toResponse(WorkorderPartAdjustmentEvent event) {
        String substituteDescription = null;
        UUID substitutedPartId = event.getSubstitutedWithPartId();
        if (substitutedPartId != null) {
            substituteDescription = workorderPartRepository.findById(substitutedPartId)
                    .map(WorkorderPart::getDescription)
                    .orElse(null);
        }

        return WorkorderPartAdjustmentEventResponse.builder()
                .id(event.getId())
                .originalPartId(event.getOriginalPart().getId())
                .originalPartDescription(event.getOriginalPart().getDescription())
                .workorderId(event.getWorkorderId())
                .adjustmentType(event.getAdjustmentType())
                .substitutedWithPartId(event.getSubstitutedWithPartId())
                .substitutedWithPartDescription(substituteDescription)
                .quantityAdjustment(event.getQuantityAdjustment())
                .reason(event.getReason())
                .performedBy(event.getPerformedBy())
                .performedAt(event.getPerformedAt())
                .notes(event.getNotes())
                .build();
    }
}
