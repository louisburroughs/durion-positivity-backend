package com.positivity.shopmanager.internal.enums;

import static com.positivity.shopmanager.internal.enums.AssignmentStatusEnum.ASSIGNED;
import static com.positivity.shopmanager.internal.enums.AssignmentStatusEnum.AWAITING_SKILL_FULFILLMENT;
import static com.positivity.shopmanager.internal.enums.AssignmentStatusEnum.CANCELLED;
import static com.positivity.shopmanager.internal.enums.AssignmentStatusEnum.COMPLETED;
import static com.positivity.shopmanager.internal.enums.AssignmentStatusEnum.IN_PROGRESS;
import static com.positivity.shopmanager.internal.enums.AssignmentStatusEnum.UNASSIGNED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link AssignmentStatusEnum} to DECISION-SHOPMGMT-010 (CAP-326, durion#483): the six
 * members and the transition table, written out in full so a future edit to either side of the
 * map fails here rather than in production.
 */
class AssignmentStatusEnumTest {

    /** DECISION-SHOPMGMT-010's ALLOWED_TRANSITIONS, transcribed rather than derived. */
    private static final Map<AssignmentStatusEnum, Set<AssignmentStatusEnum>> RECORD = Map.of(
            UNASSIGNED, EnumSet.of(ASSIGNED, CANCELLED),
            ASSIGNED, EnumSet.of(AWAITING_SKILL_FULFILLMENT, IN_PROGRESS, CANCELLED),
            AWAITING_SKILL_FULFILLMENT, EnumSet.of(ASSIGNED, CANCELLED),
            IN_PROGRESS, EnumSet.of(COMPLETED, CANCELLED),
            COMPLETED, EnumSet.noneOf(AssignmentStatusEnum.class),
            CANCELLED, EnumSet.noneOf(AssignmentStatusEnum.class));

    @Test
    @DisplayName("exactly the record's six members, in its order")
    void sixMembers() {
        assertThat(AssignmentStatusEnum.values())
                .containsExactly(UNASSIGNED, ASSIGNED, AWAITING_SKILL_FULFILLMENT, IN_PROGRESS, COMPLETED, CANCELLED);
    }

    @Test
    @DisplayName("every (from, to) pair answers exactly as the record's table does")
    void transitionTableMatchesTheRecord() {
        for (AssignmentStatusEnum from : AssignmentStatusEnum.values()) {
            for (AssignmentStatusEnum to : AssignmentStatusEnum.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(RECORD.get(from).contains(to));
            }
        }
    }

    @Test
    @DisplayName("a parked assignment never goes straight into work")
    void awaitingSkillFulfillmentResolvesOnlyByReassignmentOrCancellation() {
        assertThat(AWAITING_SKILL_FULFILLMENT.canTransitionTo(IN_PROGRESS)).isFalse();
        assertThat(AWAITING_SKILL_FULFILLMENT.canTransitionTo(COMPLETED)).isFalse();
        assertThat(AWAITING_SKILL_FULFILLMENT.canTransitionTo(ASSIGNED)).isTrue();
        assertThat(AWAITING_SKILL_FULFILLMENT.canTransitionTo(CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("COMPLETED and CANCELLED are the terminal states, and only they are")
    void terminalStates() {
        assertThat(EnumSet.allOf(AssignmentStatusEnum.class).stream().filter(AssignmentStatusEnum::isTerminal))
                .containsExactlyInAnyOrder(COMPLETED, CANCELLED);
    }

    @Test
    @DisplayName("the active set is what holds the appointment's single assignment slot — parked included")
    void activeSetIncludesParked() {
        assertThat(AssignmentStatusEnum.active())
                .containsExactlyInAnyOrder(ASSIGNED, AWAITING_SKILL_FULFILLMENT, IN_PROGRESS);
    }
}
