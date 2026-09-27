package com.positivity.shopmanager.internal.enums;

/**
 * The exclusive assignment axis for an appointment (DECISION-SHOPMGMT-003): a bay, a mobile unit,
 * or none yet. Carried on {@code AppointmentCreateRequest.resourceType} and persisted verbatim on
 * {@code appointment.resource_type} (V15's {@code CHECK} constrains stored rows to these three
 * values, {@code NULL} kept for appointments created before this column was written).
 *
 * <p>Drives which DECISION-SHOPMGMT-021 checks submit and reschedule run against {@code
 * resourceId}: {@link #BAY} runs the full eligibility function (specialty, general work, duty
 * class); {@link #MOBILE_UNIT} runs existence, location and active checks only, until mobile
 * scheduling lands (DECISION-SHOPMGMT-023); {@link #UNASSIGNED} runs no resource checks at all.
 */
public enum ResourceType {
    BAY,
    MOBILE_UNIT,
    UNASSIGNED
}
