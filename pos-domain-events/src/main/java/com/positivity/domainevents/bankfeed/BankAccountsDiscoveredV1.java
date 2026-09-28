package com.positivity.domainevents.bankfeed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: the bank accounts available under a feed connection, for the GL-account linking screen
 * (SPEC-manual-bank-reconciliation §2.2; phase 2). Published by a connector on {@code
 * bankfeed.events.v1} with {@code eventType = "bankfeed.accounts.discovered"}, keyed by {@link
 * #feedConnectionId}. Defined in phase 1 (story S2, #2301) so the contract is complete before a
 * connector exists; nothing consumes it yet.
 *
 * @param feedConnectionId the connector's connection aggregate
 * @param connectorCode provenance label; never behavioural
 * @param observedAt when the connector listed the accounts
 * @param accounts the accounts under the connection (may be empty)
 */
public record BankAccountsDiscoveredV1(
        @NonNull UUID feedConnectionId,
        @NonNull String connectorCode,
        @NonNull Instant observedAt,
        @NonNull List<DiscoveredAccount> accounts) {

    public static final String EVENT_TYPE = "bankfeed.accounts.discovered";
    public static final int SCHEMA_VERSION = 1;

    public BankAccountsDiscoveredV1 {
        if (feedConnectionId == null) {
            throw new IllegalArgumentException("feedConnectionId must not be null");
        }
        if (connectorCode == null || connectorCode.isBlank()) {
            throw new IllegalArgumentException("connectorCode must not be blank");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    /**
     * One discovered account.
     *
     * @param feedAccountId the connector's feed-account aggregate id
     * @param feedAccountRef the provider's account reference, display only
     * @param displayName the account's name as the bank shows it
     * @param accountMask last digits of the account number, display only
     * @param currency ISO 4217 code of the account
     */
    public record DiscoveredAccount(
            @NonNull UUID feedAccountId,
            @Nullable String feedAccountRef,
            @Nullable String displayName,
            @Nullable String accountMask,
            @NonNull String currency) {

        public DiscoveredAccount {
            if (feedAccountId == null) {
                throw new IllegalArgumentException("feedAccountId must not be null");
            }
            IsoCurrency.require("currency", currency);
        }
    }
}
