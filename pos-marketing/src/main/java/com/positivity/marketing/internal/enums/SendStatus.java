package com.positivity.marketing.internal.enums;

/** Lifecycle of one recipient's send record (Story #1149). */
public enum SendStatus {
    PENDING,
    SENT,
    DELIVERED,
    BOUNCED,
    COMPLAINED,
    /** Consent, account gate, or suppression denied the send at dispatch time. */
    SUPPRESSED,
    FAILED;

    /** Terminal states are never retried by the send worker. */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
