package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankTransactionBatchResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionResponse;
import com.positivity.accounting.internal.bankrec.dto.DuplicateReviewRequest;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Bank transactions: reads, duplicate review, exclude and restore (SPEC §3.8, §4.5, §6.1; #2301). */
public interface BankTransactionService {

    /** Rows of one account, filtered, sorted by {@code transactionDate, bankTransactionId}. */
    @NonNull
    BankTransactionListResponse listTransactions(
            @Nullable UUID glAccountId,
            @Nullable BankTransactionStatus status,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable SourceKind sourceKind,
            boolean unexplainedOnly,
            int page,
            int size);

    @NonNull
    BankTransactionResponse getTransaction(@NonNull UUID bankTransactionId);

    /** {@code POSSIBLE_DUPLICATE → UNMATCHED} (DISTINCT) or {@code → EXCLUDED} (DUPLICATE). */
    @NonNull
    BankTransactionResponse reviewDuplicate(@NonNull UUID bankTransactionId, @NonNull DuplicateReviewRequest request);

    /** The same review applied to every id of the request, all or nothing. */
    @NonNull
    BankTransactionBatchResponse reviewDuplicates(@NonNull DuplicateReviewRequest request);

    /** {@code UNMATCHED → EXCLUDED}, justified. */
    @NonNull
    BankTransactionResponse exclude(
            @NonNull UUID bankTransactionId, @NonNull BankTransactionJustificationRequest request);

    /** {@code EXCLUDED → UNMATCHED}, justified, unless a FINALIZED reconciliation covers the row. */
    @NonNull
    BankTransactionResponse restore(
            @NonNull UUID bankTransactionId, @NonNull BankTransactionJustificationRequest request);
}
