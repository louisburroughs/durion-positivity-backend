package com.positivity.accounting.internal.bankrec.intake;

import java.util.Map;
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
 */
public record IntakeContext(
        @NonNull UUID glAccountId,
        @NonNull String actor,
        @Nullable String gapAcknowledgement,
        @Nullable UUID sourceRef,
        @Nullable UUID requestId,
        @Nullable String requestHash,
        @Nullable Map<String, Object> defaultColumnMapping) {

    public IntakeContext {
        if (glAccountId == null) {
            throw new IllegalArgumentException("glAccountId must not be null");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        defaultColumnMapping = defaultColumnMapping == null ? null : Map.copyOf(defaultColumnMapping);
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
