package com.positivity.accounting.internal.bankrec.intake;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * What close readiness asks the file adapter (SPEC-manual-bank-reconciliation §5.3 {@code INCOMPLETE_IMPORTS};
 * story S6, #2305): the staging imports of an account still {@code UPLOADED} or {@code VALIDATED} that hold rows
 * dated on/before a day. The core may not reach the adapter (§2.1), so the adapter implements this port, as it
 * calls {@link BankTransactionIntake}.
 */
public interface IncompleteImportLookup {

    /** Ids of the account's uncommitted, undiscarded imports with a row dated on/before {@code day}. */
    @NonNull
    List<UUID> incompleteImportIds(@NonNull UUID glAccountId, @NonNull LocalDate day);
}
