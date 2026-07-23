package com.positivity.inventory.internal.service;

import com.positivity.domainevents.inventory.ScrapPostedV1;
import com.positivity.inventory.internal.dto.scrap.ApproveScrapRequest;
import com.positivity.inventory.internal.dto.scrap.CreateScrapRequest;
import com.positivity.inventory.internal.dto.scrap.RejectScrapRequest;
import com.positivity.inventory.internal.dto.scrap.ScrapResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ScrapRecord;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ScrapCostSource;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.NegativeStockPolicyViolationException;
import com.positivity.inventory.internal.exception.ScrapInsufficientStockException;
import com.positivity.inventory.internal.exception.ScrapLedgerPostingException;
import com.positivity.inventory.internal.exception.ScrapNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.ScrapRecordRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;
import com.positivity.inventory.service.ReplenishmentService;
import com.positivity.inventory.service.ScrapService;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of the scrap/write-off workflow (odoo-parity D1, issue #1030).
 *
 * <p>Approval model (mirrors {@link CycleCountAdjustmentServiceImpl}): scrap thresholds are
 * value-based — quantity × unit-cost snapshot — evaluated against the {@code SCRAP} rows of
 * {@code approval_threshold_config}. A scrap whose cost snapshot is unknown ({@code costSource
 * = NONE}) cannot be proven below any threshold, so it always requires approval at
 * {@code TIER_1_MANAGER} (the lowest tier: value is unknown, not provably large).
 *
 * <p>Cost snapshot (interim rule, ADR-0048 / plan D-6): latest {@code GOODS_RECEIPT} unit cost
 * for the SKU, falling back to the latest non-null unit cost of any ledger entry, else null.
 * odoo-parity J1 replaces this with the authoritative cost source.
 *
 * <p>Posting: {@code SCRAP_OUT} through the {@link LedgerPostingService} funnel with
 * {@code sourceTransactionId = scrapId}. The funnel's negative-stock matrix marks SCRAP_OUT
 * {@code BLOCKED_OVERRIDABLE}; this service performs the {@code inventory:adjustment:override}
 * permission check and passes the explicit flag, per the funnel contract. An insufficient-stock
 * rejection surfaces as a guided-reconciliation 422 ({@code SCRAP_INSUFFICIENT_STOCK}),
 * mirroring the putaway source-on-hand rule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScrapServiceImpl implements ScrapService {

    /** Permission required to post a scrap that drives on-hand negative. */
    static final String NEGATIVE_STOCK_OVERRIDE_PERMISSION = "inventory:adjustment:override";

    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final ScrapRecordRepository scrapRepository;
    private final InventoryLedgerEntryRepository ledgerRepository;
    private final LedgerPostingService ledgerPostingService;
    private final ApprovalThresholdEvaluator thresholdEvaluator;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final ReplenishmentService replenishmentService;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull ScrapResponse createScrap(@NonNull CreateScrapRequest request) {
        if (request.getReasonCode() == ScrapReasonCode.OTHER
                && (request.getNotes() == null || request.getNotes().isBlank())) {
            throw new IllegalArgumentException("Notes are required when reason code is OTHER");
        }

        String actor = currentActor();
        CostSnapshot costSnapshot = snapshotCost(request.getStockItemId());

        ScrapRecord scrap = ScrapRecord.builder()
                .stockItemId(request.getStockItemId())
                .quantity(request.getQuantity())
                .locationId(request.getLocationId())
                .storageLocationId(request.getStorageLocationId())
                .reasonCode(request.getReasonCode())
                .notes(request.getNotes())
                .workorderId(request.getWorkorderId())
                .attachmentReference(request.getAttachmentReference())
                .unitCostSnapshot(costSnapshot.unitCost())
                .costSource(costSnapshot.source())
                .shouldReplenish(Boolean.TRUE.equals(request.getShouldReplenish()))
                .status(ScrapStatus.PENDING_APPROVAL)
                .createdBy(actor)
                .build();

        Optional<ApprovalTier> requiredTier = evaluateRequiredTier(scrap);
        if (requiredTier.isPresent()) {
            scrap.setRequiredApprovalTier(requiredTier.get());
            scrap = scrapRepository.save(scrap);
            log.info(
                    "Scrap {} created with status PENDING_APPROVAL (tier: {}, value: {})",
                    scrap.getScrapId(),
                    requiredTier.get(),
                    scrap.getScrapValue());
        } else {
            scrap.setStatus(ScrapStatus.AUTO_APPROVED);
            scrap.setApprovedBy(SYSTEM_ACTOR);
            scrap.setApprovedAt(Instant.now(clock));
            scrap = scrapRepository.save(scrap);
            log.info(
                    "Scrap {} auto-approved (value {} below all thresholds)",
                    scrap.getScrapId(),
                    scrap.getScrapValue());
            postApprovedScrap(scrap, Boolean.TRUE.equals(request.getNegativeStockOverride()));
        }

        return toResponse(scrap);
    }

    @Override
    @Transactional
    public @NonNull ScrapResponse approveScrap(@NonNull UUID scrapId, @NonNull ApproveScrapRequest request) {
        String actor = currentActor();
        ScrapRecord scrap = scrapRepository.findById(scrapId).orElseThrow(() -> new ScrapNotFoundException(scrapId));

        if (scrap.getStatus() != ScrapStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot approve scrap in status: " + scrap.getStatus());
        }

        scrap.setStatus(ScrapStatus.APPROVED);
        scrap.setApprovedBy(actor);
        scrap.setApprovedAt(Instant.now(clock));
        scrap = scrapRepository.save(scrap);

        log.info("Scrap {} approved by {}", scrapId, actor);
        postApprovedScrap(scrap, Boolean.TRUE.equals(request.getNegativeStockOverride()));
        return toResponse(scrap);
    }

    @Override
    @Transactional
    public @NonNull ScrapResponse rejectScrap(@NonNull UUID scrapId, @NonNull RejectScrapRequest request) {
        String actor = currentActor();
        ScrapRecord scrap = scrapRepository.findById(scrapId).orElseThrow(() -> new ScrapNotFoundException(scrapId));

        if (scrap.getStatus() != ScrapStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot reject scrap in status: " + scrap.getStatus());
        }

        scrap.setStatus(ScrapStatus.REJECTED);
        scrap.setRejectedBy(actor);
        scrap.setRejectionReason(request.getRejectionReason());
        scrap.setRejectedAt(Instant.now(clock));
        scrap = scrapRepository.save(scrap);

        log.info("Scrap {} rejected by {}", scrapId, actor);
        return toResponse(scrap);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ScrapResponse getScrap(@NonNull UUID scrapId) {
        return toResponse(scrapRepository.findById(scrapId).orElseThrow(() -> new ScrapNotFoundException(scrapId)));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ScrapResponse> listScraps(
            @Nullable ScrapReasonCode reasonCode,
            @Nullable ScrapStatus status,
            @Nullable UUID locationId,
            @Nullable Instant createdFrom,
            @Nullable Instant createdTo) {
        Specification<ScrapRecord> spec = (root, query, cb) -> cb.conjunction();
        if (reasonCode != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("reasonCode"), reasonCode));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (locationId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("locationId"), locationId));
        }
        if (createdFrom != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
        }
        if (createdTo != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdTo));
        }
        return scrapRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Value-threshold evaluation. An unknown cost snapshot ({@code costSource = NONE}) means the
     * scrap value cannot be compared against any threshold; treating unknown as "free" would let
     * arbitrarily large write-offs auto-post, so unknown value always requires approval at the
     * lowest tier ({@code TIER_1_MANAGER} — the value is unknown, not provably large).
     */
    private Optional<ApprovalTier> evaluateRequiredTier(ScrapRecord scrap) {
        BigDecimal scrapValue = scrap.getScrapValue();
        if (scrapValue == null) {
            log.info(
                    "Scrap for stock item {} has no derivable cost; requiring {} approval",
                    scrap.getStockItemId(),
                    ApprovalTier.TIER_1_MANAGER);
            return Optional.of(ApprovalTier.TIER_1_MANAGER);
        }
        return thresholdEvaluator.evaluateScrapApprovalTier(scrapValue);
    }

    /**
     * Interim cost snapshot (ADR-0048 / plan D-6): most recent {@code GOODS_RECEIPT} unit cost
     * for the SKU, falling back to the latest non-null unit cost of any ledger entry. Both are
     * labeled {@code LATEST_RECEIPT}; no cost history at all yields {@code NONE} with a null
     * snapshot. odoo-parity J1 replaces this with the authoritative cost source.
     */
    private CostSnapshot snapshotCost(String stockItemId) {
        List<BigDecimal> receiptCosts = ledgerRepository.findLatestUnitCostByStockItemAndEventType(
                stockItemId, InventoryLedgerEventType.GOODS_RECEIPT, PageRequest.of(0, 1));
        if (!receiptCosts.isEmpty()) {
            return new CostSnapshot(receiptCosts.getFirst(), ScrapCostSource.LATEST_RECEIPT);
        }
        List<BigDecimal> anyCosts = ledgerRepository.findLatestUnitCostByStockItem(stockItemId, PageRequest.of(0, 1));
        if (!anyCosts.isEmpty()) {
            return new CostSnapshot(anyCosts.getFirst(), ScrapCostSource.LATEST_RECEIPT);
        }
        return new CostSnapshot(null, ScrapCostSource.NONE);
    }

    /**
     * Posts an approved/auto-approved scrap: {@code SCRAP_OUT} through the funnel, then the
     * {@code ScrapPostedV1} occurrence fact and the optional replenishment evaluation.
     *
     * <p>Failure semantics: an insufficient-stock rejection (funnel's
     * {@code NEGATIVE_STOCK_OVERRIDE_REQUIRED}) rolls back the whole transaction as the guided
     * 422 — on approve, the scrap stays {@code PENDING_APPROVAL}. Unexpected posting failures
     * follow the cycle-count FAILED pattern ({@link CycleCountAdjustmentServiceImpl}) and
     * surface as {@link ScrapLedgerPostingException}.
     */
    private void postApprovedScrap(ScrapRecord scrap, boolean overrideRequested) {
        boolean negativeStockOverride = resolveNegativeStockOverride(overrideRequested);
        UUID postingLocationId =
                scrap.getStorageLocationId() != null ? scrap.getStorageLocationId() : scrap.getLocationId();

        Integer currentOnHand =
                ledgerRepository.calculateOnHandQuantityAtLocation(scrap.getStockItemId(), postingLocationId);
        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId(scrap.getStockItemId())
                .eventType(InventoryLedgerEventType.SCRAP_OUT)
                .changeInQuantity(-scrap.getQuantity())
                .quantityAfter(currentOnHand - scrap.getQuantity())
                .unitCost(scrap.getUnitCostSnapshot())
                .locationId(postingLocationId)
                .reasonCode(scrap.getReasonCode().name())
                .sourceTransactionId(scrap.getScrapId().toString())
                .transactionUserId(scrap.getApprovedBy() != null ? scrap.getApprovedBy() : scrap.getCreatedBy())
                .notes(scrap.getNotes())
                .build();

        InventoryLedgerEntry saved;
        try {
            saved = ledgerPostingService.post(entry, negativeStockOverride);
        } catch (NegativeStockPolicyViolationException e) {
            if (NegativeStockPolicyViolationException.OVERRIDE_REQUIRED.equals(e.getErrorCode())) {
                // Guided-reconciliation 422 mirroring the putaway source-on-hand
                // rule (docs/putaway-validation-rules.md).
                throw new ScrapInsufficientStockException(
                        scrap.getStockItemId(), postingLocationId, scrap.getQuantity());
            }
            throw e;
        } catch (RuntimeException e) {
            log.error("Failed to post scrap {} to ledger", scrap.getScrapId(), e);
            scrap.setStatus(ScrapStatus.FAILED);
            scrap.setErrorMessage(e.getMessage());
            scrapRepository.save(scrap);
            throw new ScrapLedgerPostingException(scrap.getScrapId(), "Failed to post scrap to ledger", e);
        }

        inventoryFactPublisher.markEntry(saved);
        Instant postedAt = Instant.now(clock);
        scrap.setStatus(ScrapStatus.POSTED);
        scrap.setLedgerEntryId(saved.getLedgerEntryId());
        scrap.setPostedAt(postedAt);
        scrapRepository.save(scrap);

        inventoryFactPublisher.recordScrapPosted(new ScrapPostedV1(
                scrap.getScrapId(),
                scrap.getStockItemId(),
                scrap.getLocationId(),
                scrap.getStorageLocationId(),
                scrap.getQuantity(),
                scrap.getReasonCode().name(),
                scrap.getUnitCostSnapshot(),
                scrap.getCostSource().name(),
                scrap.getWorkorderId(),
                postedAt));

        log.info(
                "Scrap {} posted to ledger as entry {} (SCRAP_OUT {} of {} at {})",
                scrap.getScrapId(),
                saved.getLedgerEntryId(),
                scrap.getQuantity(),
                scrap.getStockItemId(),
                postingLocationId);

        if (Boolean.TRUE.equals(scrap.getShouldReplenish())) {
            evaluateReplenishment(scrap, postingLocationId);
        }
    }

    /**
     * The funnel never consults the security context; the caller owns the permission check
     * (see {@link LedgerPostingService}). An override request without the permission is a hard
     * 403 rather than a silent downgrade to the 422 path.
     */
    private boolean resolveNegativeStockOverride(boolean overrideRequested) {
        if (!overrideRequested) {
            return false;
        }
        if (!SecurityContextHelper.hasAuthority(NEGATIVE_STOCK_OVERRIDE_PERMISSION)) {
            throw new InsufficientPermissionException(
                    SecurityContextHelper.getCurrentUsernameOrDefault("unknown"), NEGATIVE_STOCK_OVERRIDE_PERMISSION);
        }
        return true;
    }

    /** Odoo do_replenish analog (spec D3): best-effort — a failed evaluation never rolls back the posting. */
    private void evaluateReplenishment(ScrapRecord scrap, UUID postingLocationId) {
        try {
            var result =
                    replenishmentService.evaluatePickFaceForReplenishment(scrap.getStockItemId(), postingLocationId);
            log.info(
                    "Replenishment evaluation for scrapped SKU {} at {}: {}",
                    scrap.getStockItemId(),
                    postingLocationId,
                    result.getStatus());
        } catch (RuntimeException e) {
            log.warn(
                    "Replenishment evaluation failed for scrapped SKU {} at {}: {}",
                    scrap.getStockItemId(),
                    postingLocationId,
                    e.getMessage());
        }
    }

    private String currentActor() {
        return SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
    }

    private ScrapResponse toResponse(ScrapRecord scrap) {
        return ScrapResponse.builder()
                .scrapId(scrap.getScrapId())
                .stockItemId(scrap.getStockItemId())
                .quantity(scrap.getQuantity())
                .locationId(scrap.getLocationId())
                .storageLocationId(scrap.getStorageLocationId())
                .reasonCode(scrap.getReasonCode())
                .notes(scrap.getNotes())
                .workorderId(scrap.getWorkorderId())
                .attachmentReference(scrap.getAttachmentReference())
                .unitCostSnapshot(scrap.getUnitCostSnapshot())
                .costSource(scrap.getCostSource())
                .scrapValue(scrap.getScrapValue())
                .shouldReplenish(scrap.getShouldReplenish())
                .status(scrap.getStatus())
                .requiredApprovalTier(scrap.getRequiredApprovalTier())
                .createdBy(scrap.getCreatedBy())
                .approvedBy(scrap.getApprovedBy())
                .rejectedBy(scrap.getRejectedBy())
                .rejectionReason(scrap.getRejectionReason())
                .ledgerEntryId(scrap.getLedgerEntryId())
                .errorMessage(scrap.getErrorMessage())
                .createdAt(scrap.getCreatedAt())
                .updatedAt(scrap.getUpdatedAt())
                .approvedAt(scrap.getApprovedAt())
                .rejectedAt(scrap.getRejectedAt())
                .postedAt(scrap.getPostedAt())
                .build();
    }

    private record CostSnapshot(
            @Nullable BigDecimal unitCost, @NonNull ScrapCostSource source) {}
}
