package com.positivity.customer.internal.enums;

/**
 * Why a party withdrew marketing consent (Story #1138).
 *
 * <p>{@link #LEGAL_DNC} is the one reason with teeth beyond reporting: it signals a
 * do-not-contact obligation that must never be reversed by a bulk import or a
 * campaign-driven re-opt-in.
 */
public enum OptOutReason {
    NOT_INTERESTED,
    TOO_FREQUENT,
    NEVER_SIGNED_UP,
    CONTENT_NOT_RELEVANT,
    LEGAL_DNC,
    OTHER
}
