package com.positivity.customer.internal.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Inquiry lifecycle (Story #1154).
 *
 * <p>CONVERTED is terminal and reachable only by creating or linking a party — it is the one
 * transition that changes CRM state beyond the inquiry itself, so it cannot be set directly.
 */
public enum InquiryStatus {
    NEW,
    CONTACTED,
    CONVERTED,
    CLOSED;

    public boolean canTransitionTo(InquiryStatus target) {
        return switch (this) {
            case NEW -> EnumSet.of(CONTACTED, CONVERTED, CLOSED).contains(target);
            case CONTACTED -> EnumSet.of(CONVERTED, CLOSED).contains(target);
            case CONVERTED, CLOSED -> false;
        };
    }

    public Set<InquiryStatus> openStates() {
        return EnumSet.of(NEW, CONTACTED);
    }
}
