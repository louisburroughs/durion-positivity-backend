package com.positivity.warranty.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Claim state machine (PRD §5): every legal transition of the diagram, representative illegal
 * moves (exception must carry the legal next moves in {@code nextAction}), cancel only from
 * DRAFT/SUBMITTED/INFO_NEEDED, terminal and intake-editable state sets.
 */
class ClaimStateMachineTest {

    @Nested
    class LegalTransitions {

        @ParameterizedTest
        @CsvSource({
            "DRAFT,       SUBMITTED",
            "DRAFT,       CANCELLED",
            "SUBMITTED,   IN_REVIEW",
            "SUBMITTED,   CANCELLED",
            "IN_REVIEW,   APPROVED",
            "IN_REVIEW,   DENIED",
            "IN_REVIEW,   INFO_NEEDED",
            "INFO_NEEDED, IN_REVIEW",
            "INFO_NEEDED, CANCELLED",
            "APPROVED,    SETTLED",
            "DENIED,      IN_REVIEW",
            "DENIED,      CLOSED",
            "SETTLED,     CLOSED"
        })
        void everyPrdTransitionIsLegal(ClaimStatus from, ClaimStatus to) {
            assertThat(ClaimStateMachine.canTransition(from, to)).isTrue();
            // and assertTransition does not throw
            ClaimStateMachine.assertTransition(from, to);
        }

        @Test
        void legalMovesMatchThePrdDiagramExactly() {
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.DRAFT))
                    .containsExactlyInAnyOrder(ClaimStatus.SUBMITTED, ClaimStatus.CANCELLED);
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.SUBMITTED))
                    .containsExactlyInAnyOrder(ClaimStatus.IN_REVIEW, ClaimStatus.CANCELLED);
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.IN_REVIEW))
                    .containsExactlyInAnyOrder(ClaimStatus.APPROVED, ClaimStatus.DENIED, ClaimStatus.INFO_NEEDED);
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.INFO_NEEDED))
                    .containsExactlyInAnyOrder(ClaimStatus.IN_REVIEW, ClaimStatus.CANCELLED);
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.APPROVED)).containsExactly(ClaimStatus.SETTLED);
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.DENIED))
                    .containsExactlyInAnyOrder(ClaimStatus.IN_REVIEW, ClaimStatus.CLOSED);
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.SETTLED)).containsExactly(ClaimStatus.CLOSED);
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.CLOSED)).isEmpty();
            assertThat(ClaimStateMachine.legalMoves(ClaimStatus.CANCELLED)).isEmpty();
        }
    }

    @Nested
    class IllegalTransitions {

        @ParameterizedTest
        @CsvSource({
            "DRAFT,       IN_REVIEW",
            "DRAFT,       APPROVED",
            "DRAFT,       CLOSED",
            "SUBMITTED,   APPROVED",
            "SUBMITTED,   SETTLED",
            "IN_REVIEW,   CANCELLED",
            "IN_REVIEW,   SETTLED",
            "IN_REVIEW,   CLOSED",
            "INFO_NEEDED, APPROVED",
            "APPROVED,    CLOSED",
            "APPROVED,    CANCELLED",
            "APPROVED,    DENIED",
            "DENIED,      SETTLED",
            "DENIED,      CANCELLED",
            "SETTLED,     CANCELLED",
            "SETTLED,     IN_REVIEW"
        })
        void representativeIllegalMovesAreRejected(ClaimStatus from, ClaimStatus to) {
            assertThat(ClaimStateMachine.canTransition(from, to)).isFalse();
            assertThatThrownBy(() -> ClaimStateMachine.assertTransition(from, to))
                    .isInstanceOf(IllegalClaimStateException.class);
        }

        @Test
        void illegalMoveExceptionCarriesCodeAndLegalNextMoves() {
            assertThatThrownBy(() -> ClaimStateMachine.assertTransition(ClaimStatus.DRAFT, ClaimStatus.APPROVED))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo(ClaimStateMachine.ILLEGAL_TRANSITION_CODE);
                        assertThat(e.getMessage()).contains("DRAFT").contains("APPROVED");
                        assertThat(e.getNextAction()).contains("CANCELLED").contains("SUBMITTED");
                        assertThat(e.getNextAction()).doesNotContain("APPROVED");
                    });
        }

        @Test
        void appealShapedIllegalMoveListsInReviewAndClosedFromDenied() {
            assertThatThrownBy(() -> ClaimStateMachine.assertTransition(ClaimStatus.DENIED, ClaimStatus.SETTLED))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getNextAction())
                            .contains("CLOSED")
                            .contains("IN_REVIEW"));
        }

        @ParameterizedTest
        @EnumSource(
                value = ClaimStatus.class,
                names = {"CLOSED", "CANCELLED"})
        void terminalStatesAllowNothingAndSaySoInNextAction(ClaimStatus terminal) {
            for (ClaimStatus to : ClaimStatus.values()) {
                assertThat(ClaimStateMachine.canTransition(terminal, to)).isFalse();
            }
            assertThatThrownBy(() -> ClaimStateMachine.assertTransition(terminal, ClaimStatus.IN_REVIEW))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> assertThat(((IllegalClaimStateException) ex).getNextAction())
                            .contains("terminal")
                            .contains(terminal.name()));
        }
    }

    @Nested
    class CancelRule {

        @Test
        void cancelIsLegalOnlyFromDraftSubmittedAndInfoNeeded() {
            Set<ClaimStatus> cancellable = EnumSet.noneOf(ClaimStatus.class);
            for (ClaimStatus from : ClaimStatus.values()) {
                if (ClaimStateMachine.canTransition(from, ClaimStatus.CANCELLED)) {
                    cancellable.add(from);
                }
            }
            assertThat(cancellable)
                    .containsExactlyInAnyOrder(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.INFO_NEEDED);
        }
    }

    @Nested
    class StatePredicates {

        @Test
        void onlyClosedAndCancelledAreTerminal() {
            for (ClaimStatus status : ClaimStatus.values()) {
                boolean expected = status == ClaimStatus.CLOSED || status == ClaimStatus.CANCELLED;
                assertThat(ClaimStateMachine.isTerminal(status))
                        .as("isTerminal(%s)", status)
                        .isEqualTo(expected);
            }
        }

        @Test
        void onlyDraftAndInfoNeededAreEditable() {
            for (ClaimStatus status : ClaimStatus.values()) {
                boolean expected = status == ClaimStatus.DRAFT || status == ClaimStatus.INFO_NEEDED;
                assertThat(ClaimStateMachine.isEditable(status))
                        .as("isEditable(%s)", status)
                        .isEqualTo(expected);
            }
        }

        @Test
        void assertEditablePassesInEditableStatesAndThrowsElsewhere() {
            ClaimStateMachine.assertEditable(ClaimStatus.DRAFT);
            ClaimStateMachine.assertEditable(ClaimStatus.INFO_NEEDED);

            assertThatThrownBy(() -> ClaimStateMachine.assertEditable(ClaimStatus.SUBMITTED))
                    .isInstanceOf(IllegalClaimStateException.class)
                    .satisfies(ex -> {
                        IllegalClaimStateException e = (IllegalClaimStateException) ex;
                        assertThat(e.getCode()).isEqualTo("WARRANTY_CLAIM_NOT_EDITABLE");
                        assertThat(e.getNextAction()).contains("DRAFT").contains("INFO_NEEDED");
                    });
        }
    }
}
