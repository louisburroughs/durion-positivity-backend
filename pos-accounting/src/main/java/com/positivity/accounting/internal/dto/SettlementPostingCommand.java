package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command describing the batched settlement journal entry to post (story F1c, issue #963, decision
 * D-13). One JE per settlement, idempotent on {@code sourceEventId}:
 *
 * <pre>
 *   Dr Cash             = netAmount        (real bank payout)
 *   Dr Processor Fees   = feeAmount
 *   Cr Undeposited Funds= matchedGross     (clears the C1 receipts that reconciled)
 *   Cr Settlement Suspense = unmatchedGross(parks unattributed money until reviewed)
 * </pre>
 *
 * The entry always balances because {@code netAmount + feeAmount == grossAmount == matchedGross +
 * unmatchedGross} (the header invariant plus the line partition). Zero-amount legs are omitted.
 *
 * @param sourceEventId deterministic journal-entry source id derived from the settlement id
 * @param cashAccountId GL account for the net bank payout (debit)
 * @param feesAccountId GL account for processor fees (debit)
 * @param undepositedFundsAccountId GL account cleared for matched receipts (credit)
 * @param suspenseAccountId GL account parking unmatched gross (credit)
 * @param netAmount total net paid to the bank
 * @param feeAmount total processor fees
 * @param matchedGross sum of gross over matched lines
 * @param unmatchedGross sum of gross over unmatched lines ({@code gross - matchedGross})
 * @param transactionDate journal entry date (the provider settlement date)
 * @param description entry description
 * @param overrideJustification optional CLOSED-period override justification (story B2)
 */
public record SettlementPostingCommand(
        @NonNull UUID sourceEventId,
        @NonNull UUID cashAccountId,
        @NonNull UUID feesAccountId,
        @NonNull UUID undepositedFundsAccountId,
        @NonNull UUID suspenseAccountId,
        @NonNull BigDecimal netAmount,
        @NonNull BigDecimal feeAmount,
        @NonNull BigDecimal matchedGross,
        @NonNull BigDecimal unmatchedGross,
        @NonNull LocalDateTime transactionDate,
        @NonNull String description,
        @Nullable String overrideJustification) {}
