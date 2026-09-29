package com.positivity.accounting.internal.bankrec.intake;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Starts a reconciliation of a statement a commit has just produced (SPEC §4.4, §6.1 {@code
 * startReconciliation}; story S4, #2303). It is part of the intake port so the file adapter, which may reach
 * the core only through {@code ..bankrec.intake..} and {@code ..bankrec.dto..}, can start one in its commit
 * transaction; the core implements it with the ordinary create-from-statement rules.
 */
public interface ReconciliationStarter {

    /**
     * Starts an IN_PROGRESS reconciliation of {@code statementId} in the caller's transaction.
     *
     * @param requestId the create command's id; a caller that may repeat itself derives it deterministically
     *     so a repeat returns the same reconciliation
     * @return the reconciliation id
     */
    @NonNull
    UUID start(@NonNull UUID glAccountId, @NonNull UUID statementId, @NonNull UUID requestId);
}
