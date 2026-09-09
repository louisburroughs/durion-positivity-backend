package com.positivity.securityservice.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the effective window's boundaries and the meaning of {@code revokedAt} (#1910).
 *
 * <p>Two defects lived here undetected because nothing tested a boundary. The window was
 * end-inclusive while {@code RoleAssignmentDto} published it as "Exclusive end", so an assignment
 * survived the instant it was revoked at; and {@code revokedAt} was stamped by
 * {@code setEffectiveEndDate}, so creating an ordinary bounded assignment marked it revoked at
 * birth.
 */
@DisplayName("RoleAssignment effective window and revocation marker (#1910)")
class RoleAssignmentEffectiveWindowTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 6, 1, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 31, 17, 0);

    private static RoleAssignment assignment(LocalDateTime start, LocalDateTime end) {
        RoleAssignment assignment = new RoleAssignment();
        assignment.setEffectiveStartDate(start);
        assignment.setEffectiveEndDate(end);
        return assignment;
    }

    @Nested
    @DisplayName("isEffectiveAt — the window is half-open, [start, end)")
    class Window {

        @Test
        @DisplayName("effective at the exact start instant")
        void startIsInclusive() {
            assertThat(assignment(START, END).isEffectiveAt(START)).isTrue();
        }

        @Test
        @DisplayName("not effective one nanosecond before the start")
        void beforeStartIsNotEffective() {
            assertThat(assignment(START, END).isEffectiveAt(START.minusNanos(1)))
                    .isFalse();
        }

        @Test
        @DisplayName("not effective at the exact end instant")
        void endIsExclusive() {
            // The one that matters: revocation sets effectiveEndDate to the revocation instant,
            // so an end-inclusive window would keep granting at exactly the moment access was
            // taken away.
            assertThat(assignment(START, END).isEffectiveAt(END)).isFalse();
        }

        @Test
        @DisplayName("effective one nanosecond before the end")
        void justBeforeEndIsEffective() {
            assertThat(assignment(START, END).isEffectiveAt(END.minusNanos(1))).isTrue();
        }

        @Test
        @DisplayName("an open-ended assignment never ends")
        void nullEndNeverExpires() {
            assertThat(assignment(START, null).isEffectiveAt(START.plusYears(50)))
                    .isTrue();
        }

        @Test
        @DisplayName("windows that merely touch do not both cover the handover instant")
        void touchingWindowsHandOverCleanly() {
            RoleAssignment ending = assignment(START, END);
            RoleAssignment starting = assignment(END, null);

            assertThat(ending.isEffectiveAt(END)).isFalse();
            assertThat(starting.isEffectiveAt(END)).isTrue();
        }
    }

    @Nested
    @DisplayName("revokedAt marks a revocation, not the presence of an end date")
    class RevocationMarker {

        @Test
        @DisplayName("bounding a new assignment leaves revokedAt null")
        void endDateAloneIsNotARevocation() {
            RoleAssignment bounded = assignment(START, END);

            assertThat(bounded.getEffectiveEndDate()).isEqualTo(END);
            assertThat(bounded.getRevokedAt())
                    .as("a bounded assignment has not been revoked; it just ends")
                    .isNull();
        }

        @Test
        @DisplayName("revoke records both when it takes effect and when it was entered")
        void revokeRecordsBoth() {
            Instant enteredAt = Instant.parse("2026-06-15T12:00:00Z");
            RoleAssignment open = assignment(START, null);

            open.revoke(END, enteredAt);

            assertThat(open.getEffectiveEndDate()).isEqualTo(END);
            assertThat(open.getRevokedAt()).isEqualTo(enteredAt);
        }

        @Test
        @DisplayName("a scheduled revocation stays effective until its end date arrives")
        void scheduledRevocationStaysEffective() {
            // revokedAt is when the instruction was given; effectiveEndDate is when it bites.
            // They are deliberately independent, which is why revokedAt cannot decide whether an
            // assignment currently grants anything.
            RoleAssignment open = assignment(START, null);

            open.revoke(END, Instant.parse("2026-06-15T12:00:00Z"));

            assertThat(open.getRevokedAt()).isNotNull();
            assertThat(open.isEffectiveAt(END.minusDays(1))).isTrue();
            assertThat(open.isEffectiveAt(END)).isFalse();
        }
    }
}
