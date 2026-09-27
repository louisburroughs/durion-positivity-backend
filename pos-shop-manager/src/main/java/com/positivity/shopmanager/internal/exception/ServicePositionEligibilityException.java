package com.positivity.shopmanager.internal.exception;

/**
 * Appointment submit or reschedule refused the named {@code resourceId} against the shared
 * DECISION-SHOPMGMT-021 eligibility rule. Always 422 (ADR-0017 §2: a property of the referenced
 * resource itself) with no override — distinct from {@link SchedulingConflictException}'s 409
 * tier, which answers a time-window collision (HARD/SOFT conflicts), never a defect in the
 * resource named. The four codes are shared with {@code pos-workorder} (durion-positivity-backend
 * #2001) so one condition carries one name and one status across modules:
 *
 * <ul>
 *   <li>{@code SERVICE_POSITION_INVALID} — the resource is unknown, or belongs to another location
 *   <li>{@code SERVICE_POSITION_INACTIVE} — the resource is not {@code ACTIVE} (out of service or
 *       retired)
 *   <li>{@code SERVICE_POSITION_NOT_EQUIPPED} — a {@code BAY} does not claim a specialty operation
 *       on the appointment, or takes no general work and the appointment has general operations
 *   <li>{@code SERVICE_POSITION_DUTY_CLASS_EXCEEDED} — the vehicle's GVWR class exceeds the bay's
 *       {@code maxDutyClass}
 * </ul>
 */
public class ServicePositionEligibilityException extends RuntimeException {

    /** One of the four shared {@code SERVICE_POSITION_*} refusal codes. */
    public enum Code {
        SERVICE_POSITION_INVALID,
        SERVICE_POSITION_INACTIVE,
        SERVICE_POSITION_NOT_EQUIPPED,
        SERVICE_POSITION_DUTY_CLASS_EXCEEDED
    }

    private final Code code;

    public ServicePositionEligibilityException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
