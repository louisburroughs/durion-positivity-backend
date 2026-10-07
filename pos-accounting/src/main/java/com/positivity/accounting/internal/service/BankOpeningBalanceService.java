package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.BankOpeningBalanceRequest;
import com.positivity.accounting.internal.dto.BankOpeningBalanceResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A bank account's opening balance at cutover (#2572; SPEC-accounting-workspace OI-10; Accounting Domain ruling
 * 2026-10-07): a once-per-account command following the go-live float pattern.
 */
public interface BankOpeningBalanceService {

    /** The outcome of a command: the response, and whether it answers a replayed requestId. */
    record Outcome(@NonNull BankOpeningBalanceResponse response, boolean replayed) {}

    /**
     * Posts the opening, dated {@code asOfDate}: one bank line for the statement balance, one bank line per
     * outstanding item (a check credits the bank, a deposit in transit debits it) and one 3900 line for the net,
     * so the book balance is statement + deposits in transit − outstanding checks. The period must be open, with
     * no override. Idempotent on {@code requestId}.
     *
     * @throws com.positivity.accounting.internal.exception.CashSetupException 422 {@code
     *     BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE}, 409 {@code BANK_OPENING_BALANCE_ALREADY_ESTABLISHED}, 422
     *     {@code BANK_OPENING_BALANCE_NOT_FIRST}, 422 {@code BANK_OPENING_BALANCE_EMPTY}, 409 {@code
     *     IDEMPOTENCY_CONFLICT}
     * @throws com.positivity.accounting.internal.exception.CurrencyNotSupportedException 422 {@code
     *     CURRENCY_NOT_SUPPORTED} when {@code currencyCode} is not the bank account's currency (ADR-0067 PC-9)
     */
    @NonNull
    Outcome establish(@NonNull UUID glAccountId, @NonNull BankOpeningBalanceRequest request);
}
