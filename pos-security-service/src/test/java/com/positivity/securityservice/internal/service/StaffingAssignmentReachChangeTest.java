package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Narrowing rules for staffing-assignment facts (ADR-0061 §4, #1874). */
class StaffingAssignmentReachChangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-7000-8000-0000000000e1");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-7000-8000-0000000000f1");
    private static final UUID SHOP_ID = UUID.fromString("00000000-0000-7000-8000-00000000001a");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000c1");

    private static ExtStaffingAssignmentReplica existing(UUID locationId, String status, LocalDate from, LocalDate to) {
        return ExtStaffingAssignmentReplica.builder()
                .assignmentId(ASSIGNMENT_ID)
                .personId(PERSON_ID)
                .locationId(locationId)
                .primary(true)
                .status(status)
                .effectiveFrom(from)
                .effectiveTo(to)
                .aggregateVersion(1L)
                .updatedAt(Instant.EPOCH)
                .build();
    }

    private static ExtStaffingAssignmentReplica activeOpenEnded() {
        return existing(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), null);
    }

    private static StaffingAssignmentUpdatedV1 incoming(UUID locationId, String status, LocalDate from, LocalDate to) {
        return new StaffingAssignmentUpdatedV1(
                ASSIGNMENT_ID, EMPLOYEE_ID, PERSON_ID, locationId, "TECHNICIAN", true, status, from, to);
    }

    @Test
    @DisplayName("ACTIVE -> ENDED narrows")
    void endedNarrows() {
        assertThat(StaffingAssignmentReachChange.narrows(
                        activeOpenEnded(),
                        incoming(SHOP_ID, "ENDED", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 6)),
                        TODAY))
                .isTrue();
    }

    @Test
    @DisplayName("locationId change narrows (the old node is no longer covered)")
    void locationChangeNarrows() {
        assertThat(StaffingAssignmentReachChange.narrows(
                        activeOpenEnded(), incoming(REGION_ID, "ACTIVE", LocalDate.of(2026, 1, 1), null), TODAY))
                .isTrue();
    }

    @Test
    @DisplayName("effectiveTo set where it was open-ended narrows")
    void effectiveToSetNarrows() {
        assertThat(StaffingAssignmentReachChange.narrows(
                        activeOpenEnded(),
                        incoming(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 3, 31)),
                        TODAY))
                .isTrue();
    }

    @Test
    @DisplayName("effectiveTo moved earlier narrows")
    void effectiveToEarlierNarrows() {
        assertThat(StaffingAssignmentReachChange.narrows(
                        existing(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                        incoming(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30)),
                        TODAY))
                .isTrue();
    }

    @Test
    @DisplayName("effectiveFrom moved after today narrows")
    void effectiveFromLaterThanTodayNarrows() {
        assertThat(StaffingAssignmentReachChange.narrows(
                        activeOpenEnded(), incoming(SHOP_ID, "ACTIVE", TODAY.plusDays(1), null), TODAY))
                .isTrue();
    }

    @Test
    @DisplayName("effectiveFrom dropped narrows (the projection query never matches a null effectiveFrom)")
    void effectiveFromDroppedNarrows() {
        assertThat(StaffingAssignmentReachChange.narrows(
                        activeOpenEnded(), incoming(SHOP_ID, "ACTIVE", null, null), TODAY))
                .isTrue();
    }

    @Test
    @DisplayName("Brand-new assignment (no prior row) never narrows, even when it arrives ENDED")
    void noPriorRowNeverNarrows() {
        assertThat(StaffingAssignmentReachChange.narrows(
                        null, incoming(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), null), TODAY))
                .isFalse();
        assertThat(StaffingAssignmentReachChange.narrows(
                        null, incoming(SHOP_ID, "ENDED", LocalDate.of(2026, 1, 1), TODAY), TODAY))
                .isFalse();
    }

    @Test
    @DisplayName("Later or removed effectiveTo widens: no revocation")
    void laterOrRemovedEffectiveToDoesNotNarrow() {
        ExtStaffingAssignmentReplica endingSoon =
                existing(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30));
        assertThat(StaffingAssignmentReachChange.narrows(
                        endingSoon,
                        incoming(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                        TODAY))
                .isFalse();
        assertThat(StaffingAssignmentReachChange.narrows(
                        endingSoon, incoming(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), null), TODAY))
                .isFalse();
    }

    @Test
    @DisplayName("Same effectiveTo, same node, still ACTIVE (e.g. primary flip) does not narrow")
    void unchangedReachDoesNotNarrow() {
        ExtStaffingAssignmentReplica row =
                existing(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(StaffingAssignmentReachChange.narrows(
                        row, incoming(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), TODAY))
                .isFalse();
        assertThat(StaffingAssignmentReachChange.narrows(
                        row, incoming(SHOP_ID, "ACTIVE", TODAY, LocalDate.of(2026, 12, 31)), TODAY))
                .isFalse();
    }

    @Test
    @DisplayName("An assignment that was not contributing today (ENDED, future-dated, or lapsed) cannot narrow")
    void nonContributingExistingNeverNarrows() {
        StaffingAssignmentUpdatedV1 ended = incoming(SHOP_ID, "ENDED", LocalDate.of(2026, 1, 1), TODAY);
        assertThat(StaffingAssignmentReachChange.narrows(
                        existing(SHOP_ID, "ENDED", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 1)), ended, TODAY))
                .isFalse();
        assertThat(StaffingAssignmentReachChange.narrows(
                        existing(SHOP_ID, "ACTIVE", TODAY.plusDays(1), null), ended, TODAY))
                .isFalse();
        assertThat(StaffingAssignmentReachChange.narrows(
                        existing(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), TODAY.minusDays(1)), ended, TODAY))
                .isFalse();
        assertThat(StaffingAssignmentReachChange.narrows(existing(SHOP_ID, "ACTIVE", null, null), ended, TODAY))
                .isFalse();
    }

    @Test
    @DisplayName("Reactivation (ENDED -> ACTIVE) widens: no revocation")
    void reactivationDoesNotNarrow() {
        assertThat(StaffingAssignmentReachChange.narrows(
                        existing(SHOP_ID, "ENDED", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 1)),
                        incoming(SHOP_ID, "ACTIVE", LocalDate.of(2026, 1, 1), null),
                        TODAY))
                .isFalse();
    }
}
