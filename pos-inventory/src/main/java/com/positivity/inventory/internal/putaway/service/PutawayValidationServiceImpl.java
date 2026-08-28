package com.positivity.inventory.internal.putaway.service;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.security.PutawayPermissions;
import com.positivity.inventory.internal.service.Quantities;
import com.positivity.inventory.internal.service.StorageCompatibilityEvaluator;
import com.positivity.inventory.internal.service.StorageLocationValidationService;
import com.positivity.inventory.internal.service.StorageLocationValidationService.StorageLocationValidation;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Default implementation of PutawayValidationService.
 */
@Service
public class PutawayValidationServiceImpl implements PutawayValidationService {

    private static final Logger log = LoggerFactory.getLogger(PutawayValidationServiceImpl.class);
    private static final double CAPACITY_TOLERANCE_PERCENT = 0.10; // 10% tolerance
    /** Scale for the capacity utilisation ratio; BigDecimal division needs an explicit one. */
    private static final int CAPACITY_RATIO_SCALE = 6;

    private static final List<InventoryLedgerEventType> ON_HAND_EVENT_TYPES = Arrays.stream(
                    InventoryLedgerEventType.values())
            .filter(InventoryLedgerEventType::affectsOnHand)
            .toList();

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final PutawayRuleRepository putawayRuleRepository;
    private final StorageLocationValidationService storageLocationValidationService;
    private final StorageCompatibilityEvaluator storageCompatibilityEvaluator;

    @Autowired
    public PutawayValidationServiceImpl(
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            PutawayRuleRepository putawayRuleRepository,
            StorageLocationValidationService storageLocationValidationService,
            StorageCompatibilityEvaluator storageCompatibilityEvaluator) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.putawayRuleRepository = putawayRuleRepository;
        this.storageLocationValidationService = storageLocationValidationService;
        this.storageCompatibilityEvaluator = storageCompatibilityEvaluator;
    }

    public PutawayValidationServiceImpl() {
        this.inventoryLedgerEntryRepository = null;
        this.putawayRuleRepository = null;
        this.storageLocationValidationService = null;
        this.storageCompatibilityEvaluator = null;
    }

    /**
     * Two checks, both about the destination rather than about paperwork (#1514).
     *
     * <p>The destination must be the target of an enabled putaway rule, and it must be physically fit
     * to hold the item per {@link StorageCompatibilityEvaluator}.
     *
     * <p>What used to be here and is deliberately gone: two gates requiring an
     * {@code (itemSKU, locationId)} replenishment-policy row to exist. Those made putaway eligibility
     * a function of restock configuration, so a brand-new SKU could never be put away anywhere — the
     * bug in #1514 — while a tire could be put away into oil storage as long as a policy row happened
     * to name the pair. {@link com.positivity.inventory.internal.entity.ReplenishmentPolicy} is
     * untouched and keeps doing its documented job for the replenishment scan.
     *
     * <p>The {@code LOCATION_NOT_VALID_FOR_SKU} error code is unchanged, as is the
     * {@code OVERRIDE_LOCATION_COMPATIBILITY} override flow that bypasses this method; only the
     * reason text is new, and it now names the class/capability mismatch.
     */
    @Override
    public @NonNull ValidationResult validateLocationCompatibility(
            @NonNull UUID destinationLocationId, @NonNull String skuId) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "Validating location compatibility: location(mask)={}, sku(mask)={}",
                    maskForLog(destinationLocationId),
                    maskForLog(skuId));
        }

        if (putawayRuleRepository == null
                || storageCompatibilityEvaluator == null
                || storageLocationValidationService == null) {
            return ValidationResult.success();
        }

        if (!putawayRuleRepository.existsByDestinationLocationIdAndIsEnabledTrue(destinationLocationId)) {
            throw new LocationNotValidForSkuException(
                    destinationLocationId, skuId, "Destination location is not enabled for putaway");
        }

        // Deliberately not gated on destination.isExists(). A location missing from the replica
        // yields exists=false with null capabilities, which the evaluator resolves the same
        // permissive way as a pre-#1514 fact — and it has to, because V41 ships the capability
        // columns empty with no backfill, so on an upgraded environment every destination looks
        // like that until pos-location's facts are replayed. Refusing here would dead-end every
        // receipt in exactly the window this change exists to fix. The dangerous half is still
        // closed: an item whose catalog class demands containment is refused by a destination that
        // does not declare it, and an unknown destination declares nothing. Existence is enforced
        // at execution by validateLocationCapacity.
        StorageLocationValidation destination =
                storageLocationValidationService.getStorageLocationValidation(destinationLocationId.toString());

        StorageCompatibilityEvaluator.Verdict verdict = storageCompatibilityEvaluator.evaluate(destination, skuId);
        if (!verdict.accepted()) {
            throw new LocationNotValidForSkuException(destinationLocationId, skuId, String.valueOf(verdict.reason()));
        }

        return ValidationResult.success();
    }

    private @Nullable StorageLocationValidation getStorageLocationValidation(@NonNull UUID destinationLocationId) {
        if (storageLocationValidationService == null) {
            return null;
        }
        return storageLocationValidationService.getStorageLocationValidation(destinationLocationId.toString());
    }

    private void validateStorageLocation(@Nullable StorageLocationValidation locationValidation) {
        if (locationValidation == null) {
            return;
        }
        if (!locationValidation.isExists()) {
            throw new IllegalArgumentException("Destination storage location does not exist");
        }
        if (!locationValidation.isActive()) {
            throw new IllegalArgumentException("Destination storage location is inactive");
        }
    }

    /**
     * The bin's own declared unit limit, or null when it has <em>not declared one at all</em> (#1514).
     *
     * <p>Absent means uncapped. The pre-#1514 code fell back to
     * {@code SUM(replenishment_policy.maximum_quantity)} for the location and then treated a
     * still-zero result as "full", so a bin that had simply never declared a capacity computed
     * max = 0 and hard-failed every putaway into it — which is why the seeded bins were unusable.
     * Replenishment maximums are slotting targets for the restock scan, not bin physics, so they are
     * no longer consulted here; {@code sumMaximumQuantityByLocationId} had no other caller and was
     * removed with them.
     *
     * <p>A declared <em>zero</em> is not the same statement and is deliberately not folded into
     * "uncapped": pos-location can publish {@code maxUnitCapacity = 0} from a capacity descriptor,
     * and that is an operator saying "hold nothing here". Mapping it to uncapped would turn the
     * strongest possible refusal into unlimited acceptance, so only a null passes through as
     * uncapped and a zero flows on to the at-capacity check below.
     */
    private @Nullable Integer declaredCapacity(@Nullable StorageLocationValidation locationValidation) {
        if (locationValidation == null) {
            return null;
        }
        return locationValidation.getMaxUnitCapacity();
    }

    @Override
    public @NonNull ValidationResult validateLocationCapacity(@NonNull UUID destinationLocationId, int quantity) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "Validating location capacity: location(mask)={}, quantity={}",
                    maskForLog(destinationLocationId),
                    quantity);
        }

        ValidationResult result = ValidationResult.success();
        if (inventoryLedgerEntryRepository == null) {
            return result;
        }

        StorageLocationValidation locationValidation = getStorageLocationValidation(destinationLocationId);
        validateStorageLocation(locationValidation);

        // An undeclared capacity is uncapped (#1514): nothing to compare against, so there is
        // nothing to refuse and no near-limit warning to raise. The near-limit warnings below apply
        // only where a limit is actually declared. A declared zero is not undeclared — it flows on
        // and is refused by the at-capacity check.
        Integer declaredCapacity = declaredCapacity(locationValidation);
        if (declaredCapacity == null) {
            return result;
        }

        // Capacity is a bin's declared unit limit and stays an int; the on-hand it is compared
        // against comes from the ledger and is decimal (ADR-0055, #1414). Comparing them widens
        // the limit rather than narrowing the measurement, so a bin holding 10.5 units is not
        // reported as holding 10.
        BigDecimal currentCapacity = Quantities.nz(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                destinationLocationId, ON_HAND_EVENT_TYPES));
        BigDecimal maxCapacity = BigDecimal.valueOf(declaredCapacity);

        // Direct signum/compareTo rather than the Quantities helpers: every value here is
        // provably non-null (nz above, BigDecimal.valueOf, add), and mixing the null-tolerant
        // helpers with the direct dereferences below reads as inconsistent null handling — to
        // SonarCloud's dataflow engine and to a human alike.
        if (maxCapacity.signum() <= 0) {
            // A bin that declares it holds nothing refuses everything.
            throw new LocationAtCapacityException(destinationLocationId, currentCapacity, maxCapacity);
        }

        BigDecimal projectedCapacity = currentCapacity.add(BigDecimal.valueOf(quantity));
        if (projectedCapacity.compareTo(maxCapacity) < 0) {
            addCapacityNearLimitWarning(result, projectedCapacity, maxCapacity);
            return result;
        }

        if (projectedCapacity.compareTo(maxCapacity) == 0) {
            throw new LocationAtCapacityException(destinationLocationId, currentCapacity, maxCapacity);
        }

        BigDecimal overfillUnits = projectedCapacity.subtract(maxCapacity);
        double overfillPercent = overfillUnits
                .divide(maxCapacity, CAPACITY_RATIO_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
        if (overfillPercent > CAPACITY_TOLERANCE_PERCENT) {
            throw new LocationAtCapacityException(destinationLocationId, currentCapacity, maxCapacity);
        }

        result.addWarning(
                "CAPACITY_NEAR_LIMIT",
                String.format(
                        "Projected capacity exceeds configured limit by %s units (%.2f%%). Override may be required.",
                        overfillUnits.toPlainString(), overfillPercent * 100.0));
        return result;
    }

    private void addCapacityNearLimitWarning(
            ValidationResult result, BigDecimal projectedCapacity, BigDecimal maxCapacity) {
        double utilizationPercent = projectedCapacity
                .divide(maxCapacity, CAPACITY_RATIO_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
        if (utilizationPercent >= 1.0 - CAPACITY_TOLERANCE_PERCENT) {
            result.addWarning(
                    "CAPACITY_NEAR_LIMIT",
                    String.format("Projected capacity is near limit (%.2f%% utilized).", utilizationPercent * 100.0));
        }
    }

    @Override
    public @NonNull ValidationResult validateSourceOnHand(
            @NonNull UUID sourceLocationId, @NonNull String skuId, int quantity) {
        log.debug("Validating source on-hand: location={}, sku={}, quantity={}", sourceLocationId, skuId, quantity);

        ValidationResult result = ValidationResult.success();
        if (inventoryLedgerEntryRepository == null) {
            return result;
        }

        BigDecimal onHandQuantity = Quantities.nz(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                skuId, sourceLocationId, ON_HAND_EVENT_TYPES));

        if (!Quantities.isPositive(onHandQuantity)) {
            throw new NoOnHandAtSourceLocationException(sourceLocationId, skuId);
        }

        if (Quantities.lt(onHandQuantity, BigDecimal.valueOf(quantity))) {
            result.addError(
                    "INSUFFICIENT_QUANTITY",
                    String.format(
                            "Insufficient on-hand quantity. Available: %s, Required: %d",
                            onHandQuantity.toPlainString(), quantity));
        }

        return result;
    }

    @Override
    public @NonNull ValidationResult validatePutawayExecution(@NonNull PutawayExecutionRequest request) {

        if (log.isInfoEnabled()) {
            log.info(
                    "Validating putaway execution: sku(mask)={}, from(mask)={}, to(mask)={}, qty={}",
                    maskForLog(request.getSkuId()),
                    maskForLog(request.getSourceLocationId()),
                    maskForLog(request.getDestinationLocationId()),
                    request.getQuantity());
        }

        ValidationResult result = ValidationResult.success();

        validateSource(request, result);
        validateDestinationCompatibility(request, result);
        validateDestinationCapacity(request, result);

        return result;
    }

    private void validateSource(PutawayExecutionRequest request, ValidationResult result) {
        try {
            ValidationResult sourceValidation =
                    validateSourceOnHand(request.getSourceLocationId(), request.getSkuId(), request.getQuantity());

            if (!sourceValidation.isValid()) {
                sourceValidation.getErrors().forEach(err -> result.addError(err.getErrorCode(), err.getMessage()));
            }
        } catch (NoOnHandAtSourceLocationException e) {
            if (!request.isOverrideLocationCompatibility()) {
                throw e; // Re-throw if no override requested
            }
            result.addWarning(
                    "SOURCE_RECONCILIATION_NEEDED",
                    "Source location has data consistency issue. Reconciliation required.");
        }
    }

    private void validateDestinationCompatibility(PutawayExecutionRequest request, ValidationResult result) {
        if (!request.isOverrideLocationCompatibility()) {
            ValidationResult compatValidation =
                    validateLocationCompatibility(request.getDestinationLocationId(), request.getSkuId());

            if (!compatValidation.isValid()) {
                compatValidation.getErrors().forEach(err -> result.addError(err.getErrorCode(), err.getMessage()));
            }
        } else {
            handleCompatibilityOverride(request, result);
        }
    }

    private void handleCompatibilityOverride(PutawayExecutionRequest request, ValidationResult result) {
        if (log.isWarnEnabled()) {
            log.warn(
                    "Location compatibility override requested for location(mask)={}, sku(mask)={}, reason={}",
                    maskForLog(request.getDestinationLocationId()),
                    maskForLog(request.getSkuId()),
                    request.getOverrideReasonCode());
        }

        enforceOverridePermission(PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY);
        validateOverrideAuditFields(result, request, "COMPATIBILITY");
        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("system");

        if (log.isInfoEnabled()) {
            log.info(
                    "Audit compatibility override: location={}, sku={}, reason={}, approvedBy={}, actor={}, permission={}",
                    maskForLog(request.getDestinationLocationId()),
                    maskForLog(request.getSkuId()),
                    request.getOverrideReasonCode(),
                    maskForLog(request.getApprovedBy()),
                    maskForLog(actorId),
                    PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY);
        }
        result.addWarning("COMPATIBILITY_OVERRIDDEN", "Location compatibility check was overridden");
    }

    private void validateDestinationCapacity(PutawayExecutionRequest request, ValidationResult result) {
        if (!request.isOverrideCapacity()) {
            ValidationResult capacityValidation =
                    validateLocationCapacity(request.getDestinationLocationId(), request.getQuantity());

            capacityValidation.getWarnings().forEach(warn -> result.addWarning(warn.getCode(), warn.getMessage()));

            if (!capacityValidation.isValid()) {
                capacityValidation.getErrors().forEach(err -> result.addError(err.getErrorCode(), err.getMessage()));
            }
        } else {
            handleCapacityOverride(request, result);
        }
    }

    private void handleCapacityOverride(PutawayExecutionRequest request, ValidationResult result) {
        if (log.isWarnEnabled()) {
            log.warn(
                    "Location capacity override requested for location(mask)={}, reason={}",
                    maskForLog(request.getDestinationLocationId()),
                    request.getOverrideReasonCode());
        }

        enforceOverridePermission(PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);
        validateOverrideAuditFields(result, request, "CAPACITY");

        if (request.getApprovedBy() == null || request.getApprovedBy().isBlank()) {
            result.addError("CAPACITY_OVERRIDE_APPROVAL_REQUIRED", "Capacity override requires approvedBy");
        }

        checkToleranceDuringCapacityOverride(request, result);

        if (log.isInfoEnabled()) {
            String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
            log.info(
                    "Audit capacity override requested: location(mask)={}, qty={}, reason={}, approvedBy(mask={}), actor(mask={}), permission={}",
                    maskForLog(request.getDestinationLocationId()),
                    request.getQuantity(),
                    request.getOverrideReasonCode(),
                    maskForLog(request.getApprovedBy()),
                    maskForLog(actorId),
                    PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);

            log.info(
                    "Audit capacity override: location={}, qty={}, reason={}, approvedBy={}, actor={}, permission={}",
                    maskForLog(request.getDestinationLocationId()),
                    request.getQuantity(),
                    request.getOverrideReasonCode(),
                    maskForLog(request.getApprovedBy()),
                    maskForLog(actorId),
                    PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);
        }
        result.addWarning("CAPACITY_OVERRIDDEN", "Location capacity check was overridden");
    }

    private void checkToleranceDuringCapacityOverride(PutawayExecutionRequest request, ValidationResult result) {
        try {
            ValidationResult toleranceValidation =
                    validateLocationCapacity(request.getDestinationLocationId(), request.getQuantity());
            toleranceValidation.getWarnings().forEach(warn -> result.addWarning(warn.getCode(), warn.getMessage()));
            if (!toleranceValidation.isValid()) {
                toleranceValidation.getErrors().forEach(err -> result.addError(err.getErrorCode(), err.getMessage()));
            }
        } catch (LocationAtCapacityException e) {
            if (!Quantities.isPositive(e.getMaxCapacity())) {
                // Reachable only for a bin that declares a capacity of zero. Since #1514 an
                // *undeclared* capacity is uncapped and never throws at all, so this no longer
                // fires for the "nobody configured a limit" case it used to dominate — but the
                // guard has to stay, because the tolerance ratio below divides by this value.
                result.addError(
                        "CAPACITY_OVERRIDE_TOLERANCE_UNCHECKABLE",
                        "Cannot evaluate capacity override tolerance because the destination declares"
                                + " a capacity of zero");
                return;
            }
            BigDecimal projectedCapacity = e.getCurrentCapacity().add(BigDecimal.valueOf(request.getQuantity()));
            BigDecimal overfillUnits = projectedCapacity.subtract(e.getMaxCapacity());
            double overfillPercent = !Quantities.isPositive(overfillUnits)
                    ? 0.0
                    : overfillUnits
                            .divide(e.getMaxCapacity(), CAPACITY_RATIO_SCALE, RoundingMode.HALF_UP)
                            .doubleValue();
            if (overfillPercent > CAPACITY_TOLERANCE_PERCENT) {
                result.addError(
                        "CAPACITY_OVERRIDE_EXCEEDS_TOLERANCE",
                        String.format(
                                "Capacity override exceeds tolerance (%.2f%% > %.2f%%)",
                                overfillPercent * 100.0, CAPACITY_TOLERANCE_PERCENT * 100.0));
            }
        }
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }

    private void enforceOverridePermission(String requiredPermission) {
        if (SecurityContextHelper.hasAuthority(requiredPermission)) {
            return;
        }
        throw new InsufficientPermissionException(
                SecurityContextHelper.getCurrentUsernameOrDefault("unknown"), requiredPermission);
    }

    private void validateOverrideAuditFields(ValidationResult result, PutawayExecutionRequest request, String scope) {
        if (request.getOverrideReasonCode() == null) {
            result.addError(scope + "_OVERRIDE_REASON_REQUIRED", scope + " override requires overrideReasonCode");
        }
        if (request.getOverrideJustification() == null
                || request.getOverrideJustification().isBlank()) {
            result.addError(
                    scope + "_OVERRIDE_JUSTIFICATION_REQUIRED",
                    scope + " override requires a non-empty overrideJustification");
        }
    }
}
