package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.client.StorageLocationValidationClient;
import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.security.PutawayPermissions;
import com.positivity.inventory.service.PutawayValidationService;
import com.positivity.security.common.SecurityContextHelper;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
    private static final List<InventoryLedgerEventType> ON_HAND_EVENT_TYPES = Arrays.stream(
            InventoryLedgerEventType.values())
            .filter(InventoryLedgerEventType::affectsOnHand)
            .toList();

    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final PutawayRuleRepository putawayRuleRepository;
    private final ReplenishmentPolicyRepository replenishmentPolicyRepository;
    private final StorageLocationValidationClient storageLocationValidationClient;

    @Autowired
    public PutawayValidationServiceImpl(
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            PutawayRuleRepository putawayRuleRepository,
            ReplenishmentPolicyRepository replenishmentPolicyRepository,
            StorageLocationValidationClient storageLocationValidationClient) {
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.putawayRuleRepository = putawayRuleRepository;
        this.replenishmentPolicyRepository = replenishmentPolicyRepository;
        this.storageLocationValidationClient = storageLocationValidationClient;
    }

    public PutawayValidationServiceImpl() {
        this.inventoryLedgerEntryRepository = null;
        this.putawayRuleRepository = null;
        this.replenishmentPolicyRepository = null;
        this.storageLocationValidationClient = null;
    }

    @Override
    public ValidationResult validateLocationCompatibility(UUID destinationLocationId, String skuId) {
        if (log.isDebugEnabled()) {
            log.debug("Validating location compatibility: location(mask)={}, sku(mask)={}",
                    maskForLog(destinationLocationId), maskForLog(skuId));
        }

        if (putawayRuleRepository == null || replenishmentPolicyRepository == null) {
            return ValidationResult.success();
        }

        if (!putawayRuleRepository.existsByDestinationLocationIdAndIsEnabledTrue(destinationLocationId)) {
            throw new LocationNotValidForSkuException(
                    destinationLocationId,
                    skuId,
                    "Destination location is not enabled for putaway");
        }

        if (!replenishmentPolicyRepository.existsByItemSKU(skuId)) {
            throw new LocationNotValidForSkuException(
                    destinationLocationId,
                    skuId,
                    "SKU is not configured in replenishment policies");
        }

        if (!replenishmentPolicyRepository.existsByItemSKUAndLocationId(skuId, destinationLocationId)) {
            throw new LocationNotValidForSkuException(
                    destinationLocationId,
                    skuId,
                    "SKU is not allowed at destination location");
        }

        return ValidationResult.success();
    }

    @Override
    public ValidationResult validateLocationCapacity(UUID destinationLocationId, int quantity) {
        if (log.isDebugEnabled()) {
            log.debug("Validating location capacity: location(mask)={}, quantity={}",
                    maskForLog(destinationLocationId), quantity);
        }

        ValidationResult result = ValidationResult.success();
        if (inventoryLedgerEntryRepository == null) {
            return result;
        }

        StorageLocationValidationClient.StorageLocationValidation locationValidation = null;
        if (storageLocationValidationClient != null) {
            locationValidation = storageLocationValidationClient
                    .getStorageLocationValidation(destinationLocationId.toString());

            if (!locationValidation.isExists()) {
                throw new IllegalArgumentException("Destination storage location does not exist");
            }
            if (!locationValidation.isActive()) {
                throw new IllegalArgumentException("Destination storage location is inactive");
            }
        }

        int currentCapacity = safeInt(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                destinationLocationId,
                ON_HAND_EVENT_TYPES));
        int maxCapacity = locationValidation != null && locationValidation.getMaxUnitCapacity() != null
                ? locationValidation.getMaxUnitCapacity()
                : 0;
        if (maxCapacity <= 0 && replenishmentPolicyRepository != null) {
            maxCapacity = safeInt(replenishmentPolicyRepository.sumMaximumQuantityByLocationId(destinationLocationId));
        }

        if (maxCapacity <= 0) {
            throw new LocationAtCapacityException(destinationLocationId, currentCapacity, maxCapacity);
        }

        int projectedCapacity = currentCapacity + quantity;
        if (projectedCapacity >= maxCapacity) {
            if (projectedCapacity == maxCapacity) {
                throw new LocationAtCapacityException(destinationLocationId, currentCapacity, maxCapacity);
            }

            int overfillUnits = projectedCapacity - maxCapacity;
            double overfillPercent = (double) overfillUnits / maxCapacity;
            if (overfillPercent > CAPACITY_TOLERANCE_PERCENT) {
                throw new LocationAtCapacityException(destinationLocationId, currentCapacity, maxCapacity);
            }

            result.addWarning(
                    "CAPACITY_NEAR_LIMIT",
                    String.format(
                            "Projected capacity exceeds configured limit by %d units (%.2f%%). Override may be required.",
                            overfillUnits,
                            overfillPercent * 100.0));
            return result;
        }

        double utilizationPercent = (double) projectedCapacity / maxCapacity;
        if (utilizationPercent >= 1.0 - CAPACITY_TOLERANCE_PERCENT) {
            result.addWarning(
                    "CAPACITY_NEAR_LIMIT",
                    String.format(
                            "Projected capacity is near limit (%.2f%% utilized).",
                            utilizationPercent * 100.0));
        }

        return result;
    }

    @Override
    public ValidationResult validateSourceOnHand(UUID sourceLocationId, String skuId, int quantity) {
        log.debug("Validating source on-hand: location={}, sku={}, quantity={}",
                sourceLocationId, skuId, quantity);

        ValidationResult result = ValidationResult.success();
        if (inventoryLedgerEntryRepository == null) {
            return result;
        }

        int onHandQuantity = inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(
                skuId,
                sourceLocationId,
                ON_HAND_EVENT_TYPES);

        if (onHandQuantity <= 0) {
            throw new NoOnHandAtSourceLocationException(sourceLocationId, skuId);
        }

        if (onHandQuantity < quantity) {
            result.addError("INSUFFICIENT_QUANTITY",
                    String.format("Insufficient on-hand quantity. Available: %d, Required: %d",
                            onHandQuantity,
                            quantity));
        }

        return result;
    }

    @Override
    public ValidationResult validatePutawayExecution(PutawayExecutionRequest request) {

        log.info("Validating putaway execution: sku(mask)={}, from(mask)={}, to(mask)={}, qty={}",
                maskForLog(request.getSkuId()),
                maskForLog(request.getSourceLocationId()),
                maskForLog(request.getDestinationLocationId()),
                request.getQuantity());

        ValidationResult result = ValidationResult.success();

        // Validate source has inventory
        try {
            ValidationResult sourceValidation = validateSourceOnHand(
                    request.getSourceLocationId(),
                    request.getSkuId(),
                    request.getQuantity());

            if (!sourceValidation.isValid()) {
                sourceValidation.getErrors().forEach(err -> result.addError(err.getErrorCode(), err.getMessage()));
            }
        } catch (NoOnHandAtSourceLocationException e) {
            if (!request.isOverrideLocationCompatibility()) {
                throw e; // Re-throw if no override requested
            }
            result.addWarning("SOURCE_RECONCILIATION_NEEDED",
                    "Source location has data consistency issue. Reconciliation required.");
        }

        // Validate destination compatibility
        if (!request.isOverrideLocationCompatibility()) {
            try {
                ValidationResult compatValidation = validateLocationCompatibility(
                        request.getDestinationLocationId(),
                        request.getSkuId());

                if (!compatValidation.isValid()) {
                    compatValidation.getErrors().forEach(err -> result.addError(err.getErrorCode(), err.getMessage()));
                }
            } catch (LocationNotValidForSkuException e) {
                throw e; // Re-throw - no override
            }
        } else {
            log.warn("Location compatibility override requested for location(mask)={}, sku(mask)={}, reason={}",
                    maskForLog(request.getDestinationLocationId()),
                    maskForLog(request.getSkuId()),
                    request.getOverrideReasonCode());

            enforceOverridePermission(PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY);
            validateOverrideAuditFields(result, request, "COMPATIBILITY");
            String actorId = SecurityContextHelper.getCurrentUserIdOrDefault("system");
            log.info(
                    "Audit compatibility override: location={}, sku={}, reason={}, approvedBy={}, actor={}, permission={}",
                    maskForLog(request.getDestinationLocationId()),
                    maskForLog(request.getSkuId()),
                    request.getOverrideReasonCode(),
                    maskForLog(request.getApprovedBy()),
                    maskForLog(actorId),
                    PutawayPermissions.OVERRIDE_LOCATION_COMPATIBILITY);
            result.addWarning("COMPATIBILITY_OVERRIDDEN",
                    "Location compatibility check was overridden");
        }

        // Validate destination capacity
        if (!request.isOverrideCapacity()) {
            try {
                ValidationResult capacityValidation = validateLocationCapacity(
                        request.getDestinationLocationId(),
                        request.getQuantity());

                capacityValidation.getWarnings().forEach(warn -> result.addWarning(warn.getCode(), warn.getMessage()));

                if (!capacityValidation.isValid()) {
                    capacityValidation.getErrors()
                            .forEach(err -> result.addError(err.getErrorCode(), err.getMessage()));
                }
            } catch (LocationAtCapacityException e) {
                throw e; // Re-throw - no override
            }
        } else {
            if (log.isWarnEnabled()) {
                log.warn("Location capacity override requested for location(mask)={}, reason={}",
                        maskForLog(request.getDestinationLocationId()),
                        request.getOverrideReasonCode());
            }

            enforceOverridePermission(PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);
            validateOverrideAuditFields(result, request, "CAPACITY");

            if (request.getApprovedBy() == null || request.getApprovedBy().isBlank()) {
                result.addError(
                        "CAPACITY_OVERRIDE_APPROVAL_REQUIRED",
                        "Capacity override requires approvedBy");
            }

            try {
                ValidationResult toleranceValidation = validateLocationCapacity(
                        request.getDestinationLocationId(),
                        request.getQuantity());
                toleranceValidation.getWarnings().forEach(warn -> result.addWarning(warn.getCode(), warn.getMessage()));
                if (!toleranceValidation.isValid()) {
                    toleranceValidation.getErrors()
                            .forEach(err -> result.addError(err.getErrorCode(), err.getMessage()));
                }
            } catch (LocationAtCapacityException e) {
                if (e.getMaxCapacity() <= 0) {
                    result.addError(
                            "CAPACITY_OVERRIDE_TOLERANCE_UNCHECKABLE",
                            "Cannot evaluate capacity override tolerance because max capacity is not configured");
                } else {
                    int projectedCapacity = e.getCurrentCapacity() + request.getQuantity();
                    int overfillUnits = projectedCapacity - e.getMaxCapacity();
                    double overfillPercent = overfillUnits <= 0
                            ? 0.0
                            : (double) overfillUnits / e.getMaxCapacity();
                    if (overfillPercent > CAPACITY_TOLERANCE_PERCENT) {
                        result.addError(
                                "CAPACITY_OVERRIDE_EXCEEDS_TOLERANCE",
                                String.format(
                                        "Capacity override exceeds tolerance (%.2f%% > %.2f%%)",
                                        overfillPercent * 100.0,
                                        CAPACITY_TOLERANCE_PERCENT * 100.0));
                    }
                }
            }

            log.info(
                    "Audit capacity override: location={}, qty={}, reason={}, approvedBy={}, actor={}, permission={}",
                    maskForLog(request.getDestinationLocationId()),
                    request.getQuantity(),
                    request.getOverrideReasonCode(),
                    maskForLog(request.getApprovedBy()),
                    maskForLog(SecurityContextHelper.getCurrentUserIdOrDefault("system")),
                    PutawayPermissions.OVERRIDE_LOCATION_CAPACITY);
            result.addWarning("CAPACITY_OVERRIDDEN",
                    "Location capacity check was overridden");
        }

        return result;
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized = value.toString()
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private void enforceOverridePermission(String requiredPermission) {
        if (SecurityContextHelper.hasAuthority(requiredPermission)) {
            return;
        }
        throw new InsufficientPermissionException(
                SecurityContextHelper.getCurrentUserIdOrDefault("unknown"),
                requiredPermission);
    }

    private void validateOverrideAuditFields(ValidationResult result, PutawayExecutionRequest request, String scope) {
        if (request.getOverrideReasonCode() == null) {
            result.addError(scope + "_OVERRIDE_REASON_REQUIRED",
                    scope + " override requires overrideReasonCode");
        }
        if (request.getOverrideJustification() == null || request.getOverrideJustification().isBlank()) {
            result.addError(scope + "_OVERRIDE_JUSTIFICATION_REQUIRED",
                    scope + " override requires a non-empty overrideJustification");
        }
    }
}
