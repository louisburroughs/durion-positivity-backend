package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.ProductValueChangedV1;
import com.positivity.inventory.internal.dto.revaluation.CreateRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RejectRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RevaluationResponse;
import com.positivity.inventory.internal.entity.RevaluationRecord;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.RevaluationStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.RevaluationRecordRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;
import com.positivity.inventory.service.RevaluationService;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual cost-revaluation workflow (odoo-parity J4, issue #1054; Odoo {@code product.value} analog).
 *
 * <p>Approval model mirrors the D1 scrap workflow ({@link ScrapServiceImpl}): the absolute inventory
 * value delta (|Δunit-cost| × on-hand) is evaluated against the {@code REVALUATION} rows of
 * {@code approval_threshold_config}. Below all thresholds the revaluation auto-applies in the create
 * transaction; at or above a threshold it enters {@code PENDING_APPROVAL} and no cost change happens
 * until an approver acts. Following the D1 precedent, the approver need not differ from the submitter.
 *
 * <p>Applying a revaluation restates the SKU's running cost state — {@code standardCost} under
 * STANDARD, {@code avgCost} under AVERAGE — and emits a {@code ProductValueChangedV1} fact carrying
 * old/new unit cost, on-hand, and the signed value delta so accounting can post the revaluation JE.
 * The {@link RevaluationRecord} row is the who/when/old/new audit log (ADR-0024).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevaluationServiceImpl implements RevaluationService {

    /** Money scale for the value delta. */
    private static final int VALUE_SCALE = 4;

    /** Cost scale for the SKU cost state columns. */
    private static final int COST_SCALE = 6;

    private final RevaluationRecordRepository revaluationRepository;
    private final SkuCostStateRepository costStateRepository;
    private final SkuCostStateInitializer costStateInitializer;
    private final CostingMethodResolver methodResolver;
    private final ApprovalThresholdEvaluator thresholdEvaluator;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull RevaluationResponse createRevaluation(@NonNull CreateRevaluationRequest request) {
        String actor = currentActor();
        String stockItemId = request.getStockItemId();

        CostingMethod method = methodResolver.resolve(stockItemId);
        SkuCostState state = loadOrSeedState(stockItemId);

        BigDecimal previousUnitCost = currentCost(state, method);
        BigDecimal newUnitCost = resolveNewUnitCost(request, previousUnitCost);
        BigDecimal onHand = Quantities.nz(state.getOnHandQty());

        BigDecimal base = previousUnitCost != null ? previousUnitCost : BigDecimal.ZERO;
        BigDecimal valueDelta = newUnitCost.subtract(base).multiply(onHand).setScale(VALUE_SCALE, RoundingMode.HALF_UP);

        RevaluationRecord record = RevaluationRecord.builder()
                .stockItemId(stockItemId)
                .costingMethod(method)
                .previousUnitCost(previousUnitCost)
                .newUnitCost(newUnitCost.setScale(COST_SCALE, RoundingMode.HALF_UP))
                .onHandQuantity(onHand)
                .valueDelta(valueDelta)
                .reason(request.getReason())
                .createdBy(actor)
                .status(RevaluationStatus.PENDING_APPROVAL)
                .build();

        Optional<ApprovalTier> requiredTier = thresholdEvaluator.evaluateRevaluationApprovalTier(valueDelta.abs());
        if (requiredTier.isPresent()) {
            record.setRequiredApprovalTier(requiredTier.get());
            record = revaluationRepository.save(record);
            log.info(
                    "Revaluation {} for {} created PENDING_APPROVAL (tier {}, value delta {})",
                    record.getRevaluationId(),
                    stockItemId,
                    requiredTier.get(),
                    valueDelta);
            return toResponse(record);
        }

        record.setStatus(RevaluationStatus.AUTO_APPLIED);
        record = revaluationRepository.save(record);
        applyRevaluation(record, state, actor);
        log.info(
                "Revaluation {} for {} auto-applied (value delta {} below all thresholds)",
                record.getRevaluationId(),
                stockItemId,
                valueDelta);
        return toResponse(record);
    }

    @Override
    @Transactional
    public @NonNull RevaluationResponse approveRevaluation(@NonNull UUID revaluationId) {
        String actor = currentActor();
        RevaluationRecord record = revaluationRepository
                .findById(revaluationId)
                .orElseThrow(() -> new ResourceNotFoundException("Revaluation", revaluationId.toString()));

        if (record.getStatus() != RevaluationStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot approve revaluation in status: " + record.getStatus());
        }

        record.setApprovedBy(actor);
        record.setStatus(RevaluationStatus.APPLIED);
        SkuCostState state = loadOrSeedState(record.getStockItemId());
        applyRevaluation(record, state, actor);
        log.info("Revaluation {} approved and applied by {}", revaluationId, actor);
        return toResponse(record);
    }

    @Override
    @Transactional
    public @NonNull RevaluationResponse rejectRevaluation(
            @NonNull UUID revaluationId, @NonNull RejectRevaluationRequest request) {
        String actor = currentActor();
        RevaluationRecord record = revaluationRepository
                .findById(revaluationId)
                .orElseThrow(() -> new ResourceNotFoundException("Revaluation", revaluationId.toString()));

        if (record.getStatus() != RevaluationStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot reject revaluation in status: " + record.getStatus());
        }

        record.setStatus(RevaluationStatus.REJECTED);
        record.setRejectedBy(actor);
        record.setRejectionReason(request.getRejectionReason());
        record.setRejectedAt(Instant.now(clock));
        record = revaluationRepository.save(record);
        log.info("Revaluation {} rejected by {}", revaluationId, actor);
        return toResponse(record);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull RevaluationResponse getRevaluation(@NonNull UUID revaluationId) {
        return toResponse(revaluationRepository
                .findById(revaluationId)
                .orElseThrow(() -> new ResourceNotFoundException("Revaluation", revaluationId.toString())));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<RevaluationResponse> listRevaluations(
            @Nullable String stockItemId, @Nullable RevaluationStatus status) {
        Specification<RevaluationRecord> spec = (root, query, cb) -> cb.conjunction();
        if (stockItemId != null && !stockItemId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("stockItemId"), stockItemId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return revaluationRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Restates the SKU cost state to the record's new unit cost and emits the
     * {@code ProductValueChangedV1} fact. Shared by the auto-apply and approve paths.
     */
    private void applyRevaluation(RevaluationRecord record, SkuCostState state, String applyingActor) {
        BigDecimal newCost = record.getNewUnitCost();
        if (record.getCostingMethod() == CostingMethod.STANDARD) {
            state.setStandardCost(newCost);
        } else {
            state.setAvgCost(newCost);
        }
        costStateRepository.save(state);

        Instant appliedAt = Instant.now(clock);
        record.setAppliedAt(appliedAt);
        revaluationRepository.save(record);

        inventoryFactPublisher.recordProductValueChanged(new ProductValueChangedV1(
                record.getRevaluationId(),
                record.getStockItemId(),
                record.getCostingMethod().name(),
                record.getPreviousUnitCost(),
                newCost,
                record.getOnHandQuantity(),
                record.getValueDelta(),
                record.getReason(),
                applyingActor,
                appliedAt));
    }

    /** Current cost of the resolved method: standard price under STANDARD, running average under AVERAGE. */
    private @Nullable BigDecimal currentCost(SkuCostState state, CostingMethod method) {
        return method == CostingMethod.STANDARD ? state.getStandardCost() : state.getAvgCost();
    }

    /**
     * Resolves the corrected unit cost from the request. Exactly one of {@code newUnitCost} or
     * {@code costDelta} must be supplied; the result must be zero or positive.
     */
    private BigDecimal resolveNewUnitCost(CreateRevaluationRequest request, @Nullable BigDecimal previousUnitCost) {
        BigDecimal absolute = request.getNewUnitCost();
        BigDecimal delta = request.getCostDelta();
        if ((absolute == null) == (delta == null)) {
            throw new IllegalArgumentException("Supply exactly one of newUnitCost or costDelta");
        }
        BigDecimal result;
        if (absolute != null) {
            result = absolute;
        } else {
            BigDecimal base = previousUnitCost != null ? previousUnitCost : BigDecimal.ZERO;
            result = base.add(delta);
        }
        if (result.signum() < 0) {
            throw new IllegalArgumentException("Resulting unit cost must not be negative: " + result);
        }
        return result;
    }

    private SkuCostState loadOrSeedState(String stockItemId) {
        return costStateRepository.findByStockItemId(stockItemId).orElseGet(() -> {
            costStateInitializer.createRowIfAbsent(stockItemId);
            return costStateRepository
                    .findByStockItemId(stockItemId)
                    .orElseThrow(() -> new IllegalStateException(
                            "sku_cost_state row missing after initialization for " + stockItemId));
        });
    }

    private String currentActor() {
        return SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
    }

    private RevaluationResponse toResponse(RevaluationRecord record) {
        return RevaluationResponse.builder()
                .revaluationId(record.getRevaluationId())
                .stockItemId(record.getStockItemId())
                .costingMethod(record.getCostingMethod())
                .previousUnitCost(record.getPreviousUnitCost())
                .newUnitCost(record.getNewUnitCost())
                .onHandQuantity(record.getOnHandQuantity())
                .valueDelta(record.getValueDelta())
                .reason(record.getReason())
                .status(record.getStatus())
                .requiredApprovalTier(record.getRequiredApprovalTier())
                .createdBy(record.getCreatedBy())
                .approvedBy(record.getApprovedBy())
                .rejectedBy(record.getRejectedBy())
                .rejectionReason(record.getRejectionReason())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .appliedAt(record.getAppliedAt())
                .rejectedAt(record.getRejectedAt())
                .build();
    }
}
