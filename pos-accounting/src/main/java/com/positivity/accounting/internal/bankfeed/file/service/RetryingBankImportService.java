package com.positivity.accounting.internal.bankfeed.file.service;

import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCommitResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportCreateRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportDiscardRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportListResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportMappingRequest;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowListResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowResponse;
import com.positivity.accounting.internal.bankfeed.file.dto.BankImportRowUpdateRequest;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import com.positivity.accounting.internal.bankfeed.file.enums.BankImportStatus;
import com.positivity.accounting.internal.bankrec.intake.ConcurrentCommitException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Concurrency decorator for {@link BankImportService} (story S3, #2302), the file adapter's
 * counterpart of #2301's {@code RetryingBankStatementService}. It is deliberately <strong>not</strong>
 * transactional: a command that passed every service check but lost a race on a database constraint
 * runs once more through the delegate's proxy in a fresh transaction, where the checks see the winner.
 *
 * <ul>
 *   <li>Upload: two submissions of one {@code requestId} — the retry replays the winner ({@code
 *       replayed = true}) or answers {@code IDEMPOTENCY_CONFLICT}.
 *   <li>Commit: the same import committed twice at once — the retry finds it {@code COMMITTED} and
 *       answers the winner's result (idempotent, as §4.4 asks); two imports racing one window or one
 *       file — the retry answers the service check's own {@code STATEMENT_ALREADY_IMPORTED}, {@code
 *       STATEMENT_PERIOD_OVERLAP} or {@code IMPORT_FILE_ALREADY_COMMITTED} naming the winner. A stale
 *       import version at flush (a concurrent commit of the same import) retries the same way.
 * </ul>
 *
 * Mapping, row and discard changes are not retried: a lost race there is a genuine {@code
 * OPTIMISTIC_LOCK} the preparer resolves by reloading.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class RetryingBankImportService implements BankImportService {

    private final BankImportServiceImpl delegate;

    @Override
    public @NonNull BankImportResponse create(
            @NonNull BankImportCreateRequest request,
            byte @Nullable [] fileBytes,
            @Nullable String fileName,
            @Nullable String contentType) {
        try {
            return delegate.create(request, fileBytes, fileName, contentType);
        } catch (ConcurrentCommitException raced) {
            log.warn("Bank import request {} lost a race ({}); retrying once", request.getRequestId(), raced.code());
            return delegate.create(request, fileBytes, fileName, contentType);
        }
    }

    @Override
    public @NonNull BankImportCommitResponse commit(@NonNull UUID importId, @Nullable BankImportCommitRequest request) {
        try {
            return delegate.commit(importId, request);
        } catch (ConcurrentCommitException | OptimisticLockingFailureException raced) {
            log.warn("Bank import {} commit lost a race ({}); retrying once", importId, raced.getMessage());
            return delegate.commit(importId, request);
        }
    }

    @Override
    public @NonNull BankImportListResponse list(
            @Nullable UUID glAccountId, @Nullable BankImportStatus status, int page, int size) {
        return delegate.list(glAccountId, status, page, size);
    }

    @Override
    public @NonNull BankImportResponse get(@NonNull UUID importId) {
        return delegate.get(importId);
    }

    @Override
    public @NonNull BankImportRowListResponse rows(
            @NonNull UUID importId, @Nullable BankImportRowStatus status, int page, int size) {
        return delegate.rows(importId, status, page, size);
    }

    @Override
    public @NonNull BankImportResponse updateMapping(
            @NonNull UUID importId, @NonNull BankImportMappingRequest request) {
        return delegate.updateMapping(importId, request);
    }

    @Override
    public @NonNull BankImportRowResponse updateRow(
            @NonNull UUID importId, @NonNull UUID rowId, @NonNull BankImportRowUpdateRequest request) {
        return delegate.updateRow(importId, rowId, request);
    }

    @Override
    public @NonNull BankImportResponse discard(@NonNull UUID importId, @NonNull BankImportDiscardRequest request) {
        return delegate.discard(importId, request);
    }

    @Override
    public @NonNull ImportFile download(@NonNull UUID importId) {
        return delegate.download(importId);
    }
}
