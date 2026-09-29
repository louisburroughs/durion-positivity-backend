package com.positivity.domainevents.bankfeed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a connector observed an account's balance at an instant (SPEC-manual-bank-reconciliation
 * §2.2; phase 2). Published on {@code bankfeed.events.v1} with {@code eventType =
 * "bankfeed.balance.observed"}, keyed by {@link #feedAccountId}. <b>Informational only</b>: it is
 * never a statement closing balance (§7.2). Defined in phase 1 (story S2, #2301); nothing consumes
 * it yet.
 *
 * @param feedAccountId the connector's feed-account aggregate id
 * @param feedConnectionId the connector's connection aggregate
 * @param currency ISO 4217 code of the balances
 * @param currentBalance the current (ledger) balance the bank reports
 * @param availableBalance the available balance, when the bank reports one
 * @param observedAt when the balance was observed
 */
public record BankBalanceObservedV1(
        @NonNull UUID feedAccountId,
        @NonNull UUID feedConnectionId,
        @NonNull String currency,
        @NonNull BigDecimal currentBalance,
        @Nullable BigDecimal availableBalance,
        @NonNull Instant observedAt) {

    public static final String EVENT_TYPE = "bankfeed.balance.observed";
    public static final int SCHEMA_VERSION = 1;

    public BankBalanceObservedV1 {
        if (feedAccountId == null || feedConnectionId == null) {
            throw new IllegalArgumentException("feedAccountId and feedConnectionId must not be null");
        }
        IsoCurrency.require("currency", currency);
        if (currentBalance == null) {
            throw new IllegalArgumentException("currentBalance must not be null");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }
    }
}
