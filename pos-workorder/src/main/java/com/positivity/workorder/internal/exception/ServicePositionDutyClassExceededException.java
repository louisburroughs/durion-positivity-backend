package com.positivity.workorder.internal.exception;

import com.positivity.workorder.internal.enums.ResourceType;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A bay or mobile unit's duty-class ceiling is below the vehicle's GVWR class (DECISION-SHOPMGMT-021
 * rule 3, #2269).
 *
 * <p>Placement never validates specialty capability — a vehicle legitimately moves between bays
 * within one workorder, an oil change in a general bay followed by an alignment on the rack — but a
 * lift's rated capacity is a physical limit at every point, so duty class is checked wherever a
 * workorder takes a position. The check runs only when both the vehicle's {@code gvwr_class} and the
 * position's {@code max_duty_class} are known; an unknown class on either side skips it rather than
 * refusing (spec D11).
 *
 * <p>Same code and status as pos-shop-manager's own submit-time check (DECISION-SHOPMGMT-021 rule 2):
 * one condition, one name, one status across modules. 422, not 409: the position is real, at the
 * right site and free — what fails is a physical mismatch between the vehicle and the equipment, a
 * cross-entity rule ADR-0017 §2 places at 422. {@code fieldErrors} marks {@link #FIELD} so a
 * dispatch form can highlight the position picker rather than leaving the refusal to prose alone.
 */
public class ServicePositionDutyClassExceededException extends RuntimeException {

    public static final String ERROR_CODE = "SERVICE_POSITION_DUTY_CLASS_EXCEEDED";

    /** The {@link com.positivity.workorder.internal.dto.AssignServicePositionRequest} field named. */
    public static final String FIELD = "resourceId";

    private final transient ResourceType resourceType;
    private final transient UUID resourceId;
    private final transient int vehicleGvwrClass;
    private final transient int maxDutyClass;

    public ServicePositionDutyClassExceededException(
            ResourceType resourceType, UUID resourceId, @Nullable String name, int vehicleGvwrClass, int maxDutyClass) {
        super(resourceType + " " + resourceId + (name == null || name.isBlank() ? "" : " (" + name + ")")
                + " has duty-class ceiling " + maxDutyClass + ", below the vehicle's GVWR class "
                + vehicleGvwrClass);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.vehicleGvwrClass = vehicleGvwrClass;
        this.maxDutyClass = maxDutyClass;
    }

    public ServicePositionDutyClassExceededException(
            ResourceType resourceType, UUID resourceId, int vehicleGvwrClass, int maxDutyClass) {
        this(resourceType, resourceId, null, vehicleGvwrClass, maxDutyClass);
    }

    /** The bay or mobile unit whose ceiling the vehicle exceeds, carried as {@code referenceId}. */
    public UUID getResourceId() {
        return resourceId;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public int getVehicleGvwrClass() {
        return vehicleGvwrClass;
    }

    public int getMaxDutyClass() {
        return maxDutyClass;
    }
}
