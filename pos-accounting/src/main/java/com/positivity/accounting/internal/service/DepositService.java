package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.DepositRecordRequest;
import com.positivity.accounting.internal.dto.DepositResponse;
import com.positivity.accounting.internal.dto.DepositReversalRequest;
import com.positivity.accounting.internal.dto.UndepositedSessionsResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Bank deposits of drawer cash (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5, §7.1; AW10): the undeposited
 * sessions, Record bank deposit and Reverse deposit.
 */
public interface DepositService {

    /** A command's result, and whether it answers a replayed requestId with the first result. */
    record Outcome(@NonNull DepositResponse response, boolean replayed) {}

    /**
     * The undeposited sessions the caller may deposit, oldest first; with {@code sessionIds}, also the deposit they
     * make. Nothing posts.
     *
     * @param sessionIds the selection; empty for none
     * @param bankGlAccountId the bank account the preview's bank line names, if any
     */
    @NonNull
    UndepositedSessionsResponse undeposited(@NonNull List<UUID> sessionIds, @Nullable UUID bankGlAccountId);

    /** Record a bank deposit of whole sessions; idempotent on the request's requestId. */
    @NonNull
    Outcome record(@NonNull DepositRecordRequest request);

    /** Reverse a deposit through the journal-entry reversal (ADR-0047); idempotent on the request's requestId. */
    @NonNull
    Outcome reverse(@NonNull UUID depositId, @NonNull DepositReversalRequest request);

    /** A deposit as it stands. */
    @NonNull
    DepositResponse get(@NonNull UUID depositId);
}
