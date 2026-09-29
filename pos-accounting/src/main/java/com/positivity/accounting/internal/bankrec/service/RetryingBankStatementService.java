package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.BankStatementListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import com.positivity.accounting.internal.bankrec.intake.ConcurrentCommitException;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Concurrency decorator for {@link BankStatementService} (#2301), after {@code
 * RetryingPaymentApplicationService}. Two submissions of one {@code requestId} can both pass the
 * request-id lookup before either commits; the loser's insert then fails on a database constraint
 * inside a transaction that is already rollback-only. This bean is deliberately <strong>not</strong>
 * transactional: it retries the whole command once through the delegate's proxy, in a fresh
 * transaction, where the lookup now sees the winner — an identical payload replays it ({@code replayed
 * = true}), a different one answers {@code IDEMPOTENCY_CONFLICT}, and a different request racing the
 * same window gets the service check's own refusal.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class RetryingBankStatementService implements BankStatementService {

    private final BankStatementServiceImpl delegate;

    @Override
    public @NonNull BankStatementResponse createManualStatement(@NonNull BankStatementCreateRequest request) {
        try {
            return delegate.createManualStatement(request);
        } catch (ConcurrentCommitException raced) {
            log.warn(
                    "Manual bank statement request {} lost a commit race ({}); retrying once",
                    request.getRequestId(),
                    raced.code());
            return delegate.createManualStatement(request);
        }
    }

    @Override
    public @NonNull BankStatementListResponse listStatements(
            @Nullable UUID glAccountId, @Nullable LocalDate from, @Nullable LocalDate to, int page, int size) {
        return delegate.listStatements(glAccountId, from, to, page, size);
    }

    @Override
    public @NonNull BankStatementResponse getStatement(@NonNull UUID statementId) {
        return delegate.getStatement(statementId);
    }
}
