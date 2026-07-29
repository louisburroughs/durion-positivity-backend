package com.positivity.marketing.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Story #1146: the lifecycle table is the single constraint every caller shares. */
class CampaignStatusTest {

    @Test
    @DisplayName("a draft can be scheduled or cancelled, nothing else")
    void draftTransitions() {
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.SCHEDULED))
                .isTrue();
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.CANCELLED))
                .isTrue();
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.SENDING)).isFalse();
        assertThat(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.SENT)).isFalse();
    }

    @Test
    @DisplayName("a campaign that has begun sending can never return to DRAFT")
    void sendingCannotReturnToDraft() {
        assertThat(CampaignStatus.SENDING.canTransitionTo(CampaignStatus.DRAFT)).isFalse();
        assertThat(CampaignStatus.SENT.canTransitionTo(CampaignStatus.DRAFT)).isFalse();
    }

    @Test
    @DisplayName("cancelled and closed are terminal")
    void terminalStates() {
        for (CampaignStatus target : CampaignStatus.values()) {
            assertThat(CampaignStatus.CANCELLED.canTransitionTo(target)).isFalse();
            assertThat(CampaignStatus.CLOSED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("only a draft is editable")
    void onlyDraftIsEditable() {
        assertThat(CampaignStatus.DRAFT.isEditable()).isTrue();
        assertThat(CampaignStatus.SCHEDULED.isEditable()).isFalse();
        assertThat(CampaignStatus.SENDING.isEditable()).isFalse();
        assertThat(CampaignStatus.SENT.isEditable()).isFalse();
    }

    @Test
    @DisplayName("a paused campaign returns to the queue or is cancelled")
    void pausedTransitions() {
        assertThat(CampaignStatus.PAUSED.canTransitionTo(CampaignStatus.SCHEDULED))
                .isTrue();
        assertThat(CampaignStatus.PAUSED.canTransitionTo(CampaignStatus.CANCELLED))
                .isTrue();
        assertThat(CampaignStatus.PAUSED.canTransitionTo(CampaignStatus.SENT)).isFalse();
    }
}
