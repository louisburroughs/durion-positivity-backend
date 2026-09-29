package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.BankStatementListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Bank statements: manual entry through the intake port and the statement reads (SPEC §4.3, §6.1; #2301). */
public interface BankStatementService {

    /**
     * Commits a statement entered by hand through the intake port ({@code sourceKind = MANUAL_ENTRY}).
     * A replay of the same {@code requestId} and payload returns the original with {@code replayed =
     * true}; a different payload answers {@code IDEMPOTENCY_CONFLICT}.
     */
    @NonNull
    BankStatementResponse createManualStatement(@NonNull BankStatementCreateRequest request);

    /** Statements whose window meets {@code [from, to]}, sorted by {@code startDate}. */
    @NonNull
    BankStatementListResponse listStatements(
            @Nullable UUID glAccountId, @Nullable LocalDate from, @Nullable LocalDate to, int page, int size);

    /** One statement with its counts and reconciliation links. */
    @NonNull
    BankStatementResponse getStatement(@NonNull UUID statementId);
}
