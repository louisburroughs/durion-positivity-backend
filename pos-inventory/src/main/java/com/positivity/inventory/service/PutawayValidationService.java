package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;

/**
 * Service for validating putaway operations according to business rules.
 * 
 * <p>
 * Implements the business rules defined in clarification #229 for issue #31:
 * <ul>
 * <li>Q1: Destination location validation (compatibility and capacity)</li>
 * <li>Q2: Source location on-hand validation</li>
 * </ul>
 */
public interface PutawayValidationService {

    /**
     * Validates that the destination location is compatible with the SKU.
     * 
     * <p>
     * Checks:
     * <ul>
     * <li>SKU is allowed in the zone</li>
     * <li>Temperature class matches</li>
     * <li>Hazardous/non-hazardous rules</li>
     * <li>Location is an authorized bin</li>
     * </ul>
     * 
     * @param destinationLocationId the destination location to validate
     * @param skuId                 the SKU being put away
     * @return validation result
     * @throws LocationNotValidForSkuException if location is invalid and no
     *                                         override
     */
    ValidationResult validateLocationCompatibility(String destinationLocationId, String skuId);

    /**
     * Validates that the destination location has sufficient capacity.
     * 
     * <p>
     * Default behavior: Block if at or exceeding capacity.
     * <p>
     * Override allowed only if:
     * <ul>
     * <li>Permission OVERRIDE_LOCATION_CAPACITY exists</li>
     * <li>Overfill is within tolerance (≤ 5-10%)</li>
     * <li>Justification is provided</li>
     * </ul>
     * 
     * @param destinationLocationId the destination location
     * @param quantity              the quantity being added
     * @return validation result
     * @throws LocationAtCapacityException if location is full and no override
     */
    ValidationResult validateLocationCapacity(String destinationLocationId, int quantity);

    /**
     * Validates that the source location has on-hand inventory for the SKU.
     * 
     * <p>
     * Default behavior: Always block if zero on-hand.
     * System must NOT silently create inventory.
     * 
     * <p>
     * Requires reconciliation via:
     * <ul>
     * <li>Cycle count (INITIATE_CYCLE_COUNT permission)</li>
     * <li>Inventory adjustment (ADJUST_INVENTORY permission)</li>
     * </ul>
     * 
     * @param sourceLocationId the source location
     * @param skuId            the SKU being moved
     * @param quantity         the quantity being moved
     * @return validation result
     * @throws NoOnHandAtSourceLocationException if no on-hand inventory found
     */
    ValidationResult validateSourceOnHand(String sourceLocationId, String skuId, int quantity);

    /**
     * Performs comprehensive validation for a putaway execution request.
     * 
     * @param request the putaway execution request
     * @return comprehensive validation result
     */
    ValidationResult validatePutawayExecution(PutawayExecutionRequest request);
}
