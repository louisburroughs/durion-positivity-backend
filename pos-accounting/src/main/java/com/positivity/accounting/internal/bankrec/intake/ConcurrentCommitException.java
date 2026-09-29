package com.positivity.accounting.internal.bankrec.intake;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * A commit that passed every service check but lost a race to a concurrent one on a database
 * constraint (U1, U2 or the request-id unique index; #2301). The failed transaction is rollback-only,
 * so a caller that wants the winner's outcome (an idempotent replay, or the refusal the service checks
 * now give) retries in a fresh transaction; answered unretried, it carries the constraint's code.
 */
public class ConcurrentCommitException extends BankRecException {

    private static final long serialVersionUID = 1L;

    public ConcurrentCommitException(@NonNull BankRecErrorCode code, @NonNull String message) {
        super(code, message);
    }

    public ConcurrentCommitException(
            @NonNull BankRecErrorCode code, @NonNull String message, @NonNull Map<String, String> fieldErrors) {
        super(code, message, fieldErrors);
    }
}
