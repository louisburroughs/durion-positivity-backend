package com.positivity.customer.internal.enums;

/**
 * Kind of recorded touch between the business and a party (Story #1141).
 *
 * <p>{@link #WORKORDER_NOTE} is the pre-existing one-way projection from workorder events;
 * the rest generalize it so campaign sends, CSR calls, and follow-ups land in the same
 * timeline instead of each growing a private table.
 */
public enum InteractionType {
    CAMPAIGN_SEND,
    EMAIL,
    SMS,
    CALL,
    FOLLOW_UP,
    NOTE,
    WORKORDER_NOTE
}
