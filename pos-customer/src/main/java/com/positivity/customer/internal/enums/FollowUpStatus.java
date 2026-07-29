package com.positivity.customer.internal.enums;

/**
 * Lifecycle of a follow-up task (Story #1153).
 *
 * <p>{@link #DISMISSED} is deliberately distinct from {@link #DONE}: "we decided not to chase
 * this" and "we chased it" are different outcomes, and collapsing them would make the queue's
 * completion rate meaningless.
 */
public enum FollowUpStatus {
    OPEN,
    DONE,
    DISMISSED;

    public boolean isClosed() {
        return this != OPEN;
    }
}
