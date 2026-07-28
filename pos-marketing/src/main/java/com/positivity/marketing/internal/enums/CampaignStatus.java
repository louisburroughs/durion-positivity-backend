package com.positivity.marketing.internal.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Campaign lifecycle (Story #1146).
 *
 * <p>The legal transitions live on the enum rather than in the service, so every caller —
 * command endpoint, send worker, scheduled job — is constrained by the same table. A campaign
 * that has begun dispatching cannot return to DRAFT: some recipients have already been
 * contacted, and editing the definition afterwards would make the send record unexplainable.
 */
public enum CampaignStatus {
    DRAFT,
    SCHEDULED,
    SENDING,
    SENT,
    PAUSED,
    CANCELLED,
    CLOSED;

    public boolean canTransitionTo(CampaignStatus target) {
        return allowedTargets().contains(target);
    }

    /** Whether the definition may still be edited — only before anything has been dispatched. */
    public boolean isEditable() {
        return this == DRAFT;
    }

    private Set<CampaignStatus> allowedTargets() {
        return switch (this) {
            case DRAFT -> EnumSet.of(SCHEDULED, CANCELLED);
            case SCHEDULED -> EnumSet.of(SENDING, PAUSED, CANCELLED, DRAFT);
            case SENDING -> EnumSet.of(SENT, PAUSED, CANCELLED);
            case PAUSED -> EnumSet.of(SCHEDULED, SENDING, CANCELLED);
            case SENT -> EnumSet.of(CLOSED);
            // Terminal: a cancelled or closed campaign is history, not a draft to revive.
            case CANCELLED, CLOSED -> EnumSet.noneOf(CampaignStatus.class);
        };
    }
}
