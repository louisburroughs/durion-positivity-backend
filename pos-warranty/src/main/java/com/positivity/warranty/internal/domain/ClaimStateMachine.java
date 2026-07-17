package com.positivity.warranty.internal.domain;

import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/**
 * The claim state machine (PRD §5) — deliberately small, every state explainable to a customer
 * at the counter. Reimbursement (§3.7) and part return (§3.8) run child lifecycles and do not
 * appear here; open children keep a claim out of {@code CLOSED} but never out of
 * {@code SETTLED} (settle-customer-first).
 *
 * <pre>
 * DRAFT       → SUBMITTED | CANCELLED
 * SUBMITTED   → IN_REVIEW | CANCELLED
 * IN_REVIEW   → APPROVED | DENIED | INFO_NEEDED
 * INFO_NEEDED → IN_REVIEW | CANCELLED
 * APPROVED    → SETTLED
 * DENIED      → IN_REVIEW (appeal, reason required) | CLOSED
 * SETTLED     → CLOSED
 * CLOSED / CANCELLED → terminal
 * </pre>
 */
public final class ClaimStateMachine {

    static final String ILLEGAL_TRANSITION_CODE = "WARRANTY_CLAIM_ILLEGAL_TRANSITION";

    private static final Map<ClaimStatus, Set<ClaimStatus>> LEGAL_TRANSITIONS = new EnumMap<>(Map.of(
            ClaimStatus.DRAFT, EnumSet.of(ClaimStatus.SUBMITTED, ClaimStatus.CANCELLED),
            ClaimStatus.SUBMITTED, EnumSet.of(ClaimStatus.IN_REVIEW, ClaimStatus.CANCELLED),
            ClaimStatus.IN_REVIEW, EnumSet.of(ClaimStatus.APPROVED, ClaimStatus.DENIED, ClaimStatus.INFO_NEEDED),
            ClaimStatus.INFO_NEEDED, EnumSet.of(ClaimStatus.IN_REVIEW, ClaimStatus.CANCELLED),
            ClaimStatus.APPROVED, EnumSet.of(ClaimStatus.SETTLED),
            ClaimStatus.DENIED, EnumSet.of(ClaimStatus.IN_REVIEW, ClaimStatus.CLOSED),
            ClaimStatus.SETTLED, EnumSet.of(ClaimStatus.CLOSED),
            ClaimStatus.CLOSED, EnumSet.noneOf(ClaimStatus.class),
            ClaimStatus.CANCELLED, EnumSet.noneOf(ClaimStatus.class)));

    /** States in which intake edits (PUT, line add/remove, photo add/remove) are allowed (PRD §8). */
    private static final Set<ClaimStatus> EDITABLE = EnumSet.of(ClaimStatus.DRAFT, ClaimStatus.INFO_NEEDED);

    private ClaimStateMachine() {}

    /** The legal target states from {@code from} (empty for terminal states). */
    @NonNull
    public static Set<ClaimStatus> legalMoves(@NonNull ClaimStatus from) {
        return Set.copyOf(LEGAL_TRANSITIONS.get(from));
    }

    public static boolean canTransition(@NonNull ClaimStatus from, @NonNull ClaimStatus to) {
        return LEGAL_TRANSITIONS.get(from).contains(to);
    }

    public static boolean isTerminal(@NonNull ClaimStatus status) {
        return LEGAL_TRANSITIONS.get(status).isEmpty();
    }

    public static boolean isEditable(@NonNull ClaimStatus status) {
        return EDITABLE.contains(status);
    }

    /**
     * @throws IllegalClaimStateException carrying the legal next moves in {@code nextAction}
     *     when {@code from → to} is not a legal transition
     */
    public static void assertTransition(@NonNull ClaimStatus from, @NonNull ClaimStatus to) {
        if (!canTransition(from, to)) {
            throw illegalTransition(from, "transition to " + to);
        }
    }

    /**
     * @throws IllegalClaimStateException when the claim is not in an intake-editable state
     *     ({@code DRAFT} or {@code INFO_NEEDED})
     */
    public static void assertEditable(@NonNull ClaimStatus status) {
        if (!isEditable(status)) {
            throw new IllegalClaimStateException(
                    "WARRANTY_CLAIM_NOT_EDITABLE",
                    "Claim in status " + status + " cannot be edited; edits are allowed only in " + EDITABLE,
                    "Edit the claim while it is DRAFT or INFO_NEEDED. " + legalMovesHint(status));
        }
    }

    /** Builds the standard illegal-move exception with {@code nextAction} listing the legal moves. */
    @NonNull
    public static IllegalClaimStateException illegalTransition(@NonNull ClaimStatus from, @NonNull String attempted) {
        return new IllegalClaimStateException(
                ILLEGAL_TRANSITION_CODE,
                "Claim in status " + from + " does not allow: " + attempted,
                legalMovesHint(from));
    }

    @NonNull
    private static String legalMovesHint(@NonNull ClaimStatus from) {
        Set<ClaimStatus> moves = LEGAL_TRANSITIONS.get(from);
        if (moves.isEmpty()) {
            return "Claim is in terminal status " + from + "; no further transitions are possible";
        }
        return "Legal next statuses from " + from + ": "
                + moves.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
    }
}
