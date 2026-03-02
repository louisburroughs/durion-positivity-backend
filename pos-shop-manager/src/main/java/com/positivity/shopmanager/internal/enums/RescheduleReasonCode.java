package com.positivity.shopmanager.internal.enums;

/**
 * Mandatory reason code for rescheduling an appointment.
 *
 * <p>
 * Per CAP-249 Story #11 (louisburroughs/durion-positivity-backend#11):
 * reason is mandatory on every reschedule; free-text
 * {@code rescheduleReasonNotes}
 * is required when reason is {@link #OTHER} or when overriding a scheduling
 * conflict.
 */
public enum RescheduleReasonCode {
    /** Customer requested the time change. */
    CUSTOMER_REQUEST,
    /** Shop capacity or bay availability requires a change. */
    SHOP_CAPACITY,
    /** Equipment issue prevents the original appointment time. */
    EQUIPMENT_ISSUE,
    /** Assigned mechanic is unavailable at the original time. */
    MECHANIC_UNAVAILABLE,
    /** Parts required for the job are delayed. */
    PARTS_DELAY,
    /** Weather conditions prevent service (e.g., mobile unit). */
    WEATHER,
    /** Emergency situation requires immediate rescheduling. */
    EMERGENCY,
    /**
     * Manager discretion — free-text notes strongly recommended;
     * notes-required enforcement pending follow-up story.
     */
    MANAGER_DISCRETION,
    /** Other reason — free-text notes required. */
    OTHER
}
