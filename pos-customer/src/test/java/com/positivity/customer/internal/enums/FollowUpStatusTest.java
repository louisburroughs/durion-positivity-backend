package com.positivity.customer.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Story #1153: only OPEN tasks are workable. */
class FollowUpStatusTest {

    @Test
    @DisplayName("DONE and DISMISSED are both closed, and are distinct from each other")
    void closedStates() {
        assertThat(FollowUpStatus.OPEN.isClosed()).isFalse();
        assertThat(FollowUpStatus.DONE.isClosed()).isTrue();
        assertThat(FollowUpStatus.DISMISSED.isClosed()).isTrue();
        assertThat(FollowUpStatus.DONE).isNotEqualTo(FollowUpStatus.DISMISSED);
    }
}
