package com.positivity.accounting.internal.bankrec.intake;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What the caller of {@link BankTransactionIntake#accept} knows beyond the batch itself (SPEC §2.1;
 * story S2, #2301).
 *
 * @param glAccountId the reconciled ledger bank account (files and manual entry name it; phase-2
 *     feeds resolve it through {@code bank_account_profile})
 * @param actor who commits — stored as {@code gapAcknowledgedBy} and on audit rows (ADR-0018)
 * @param gapAcknowledgement the justification for a statement that does not continue the previous
 *     one (E2, D17); null when none was given
 * @param sourceRef the provenance reference: the import id for a file, the feed connection id for a
 *     feed, null for manual entry
 * @param requestId the manual-statement command's id, stored for replay (§6.3); else null
 * @param requestHash the SHA-256 of that command's payload; else null
 * @param defaultColumnMapping the column mapping a newly created profile takes when the import asked
 *     to save it as the account default (story S3); else null
 * @param confirmedDistinctRows the {@code sourceRowNumber}s a human confirmed distinct before the
 *     commit (the file adapter's duplicate decisions, §4.4; story S3): such a row enters {@code
 *     UNMATCHED} even when its fingerprint collides (R1 asks a human, and the human has answered);
 *     empty otherwise
 * @param supersedesStatementId the COMMITTED statement of the same account this corrected statement supersedes
 *     (§4.9 path 3; story S5); else null
 * @param supersessionJustification why it supersedes it (at least 10 characters, D15); else null
 */
public record IntakeContext(
        @NonNull UUID glAccountId,
        @NonNull String actor,
        @Nullable String gapAcknowledgement,
        @Nullable UUID sourceRef,
        @Nullable UUID requestId,
        @Nullable String requestHash,
        @Nullable Map<String, Object> defaultColumnMapping,
        @NonNull Set<Integer> confirmedDistinctRows,
        @Nullable UUID supersedesStatementId,
        @Nullable String supersessionJustification) {

    public IntakeContext {
        if (glAccountId == null) {
            throw new IllegalArgumentException("glAccountId must not be null");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        defaultColumnMapping = defaultColumnMapping == null ? null : Map.copyOf(defaultColumnMapping);
        confirmedDistinctRows = confirmedDistinctRows == null ? Set.of() : Set.copyOf(confirmedDistinctRows);
    }

    /** A context that supersedes no statement. */
    public IntakeContext(
            @NonNull UUID glAccountId,
            @NonNull String actor,
            @Nullable String gapAcknowledgement,
            @Nullable UUID sourceRef,
            @Nullable UUID requestId,
            @Nullable String requestHash,
            @Nullable Map<String, Object> defaultColumnMapping,
            @NonNull Set<Integer> confirmedDistinctRows) {
        this(
                glAccountId,
                actor,
                gapAcknowledgement,
                sourceRef,
                requestId,
                requestHash,
                defaultColumnMapping,
                confirmedDistinctRows,
                null,
                null);
    }

    /** A context without confirmed-distinct rows (manual entry, feeds, a file without decisions). */
    public IntakeContext(
            @NonNull UUID glAccountId,
            @NonNull String actor,
            @Nullable String gapAcknowledgement,
            @Nullable UUID sourceRef,
            @Nullable UUID requestId,
            @Nullable String requestHash,
            @Nullable Map<String, Object> defaultColumnMapping) {
        this(glAccountId, actor, gapAcknowledgement, sourceRef, requestId, requestHash, defaultColumnMapping, Set.of());
    }

    /** A context for a manual statement or a file without saved mapping. */
    public static IntakeContext of(
            @NonNull UUID glAccountId,
            @NonNull String actor,
            @Nullable String gapAcknowledgement,
            @Nullable UUID sourceRef) {
        return new IntakeContext(glAccountId, actor, gapAcknowledgement, sourceRef, null, null, null);
    }
}
