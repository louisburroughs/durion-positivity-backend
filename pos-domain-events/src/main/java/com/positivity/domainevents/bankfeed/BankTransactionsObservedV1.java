package com.positivity.domainevents.bankfeed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A batch of normalized bank transactions for one bank account, each {@code ADDED}, {@code
 * MODIFIED} or {@code REMOVED}, with an optional statement header
 * (SPEC-manual-bank-reconciliation §2.2, §6.5; story S2, #2301).
 *
 * <p>This is the provider-neutral contract every source speaks: the phase-1 file adapter and the
 * manual-statement endpoint build it in-process and hand it to pos-accounting's intake port; a
 * phase-2 connector publishes it on {@code bankfeed.events.v1} with {@code eventType =
 * "bankfeed.transactions.observed"}. No type here names a provider, a file format or a credential;
 * {@link #connectorCode} is a provenance label the core never branches on — {@link #sourceKind} is
 * the only thing it does branch on (§2.2).
 *
 * <p>The envelope aggregate is {@link #feedAccountId} for a feed; for a file or a manual entry,
 * whose aggregate is the import or the statement, it is null. Signs follow the core convention:
 * <b>positive = money into the account</b>; a connector whose provider uses the opposite sign inverts
 * inside the connector.
 *
 * <p>Evolution is additive-only within schema version 1 (ADR-0044 §3).
 *
 * @param sourceKind provenance class; the only field the core branches on
 * @param connectorCode provenance label (for example {@code csv-v1}); never behavioural
 * @param feedConnectionId the connector's connection aggregate (phase 2); null otherwise
 * @param feedAccountId the connector's feed-account aggregate id (phase 2); null otherwise
 * @param feedAccountRef the provider's own account reference, payload only; null for files
 * @param currency ISO 4217 code of every amount in the batch
 * @param observedAt when the source observed the batch
 * @param cursorRef opaque connector cursor, for traceability only
 * @param statement the statement header, present for files and manual entry, null for feeds
 * @param transactions the observed transactions; never empty
 */
public record BankTransactionsObservedV1(
        @NonNull SourceKind sourceKind,
        @Nullable String connectorCode,
        @Nullable UUID feedConnectionId,
        @Nullable UUID feedAccountId,
        @Nullable String feedAccountRef,
        @NonNull String currency,
        @NonNull Instant observedAt,
        @Nullable String cursorRef,
        @Nullable StatementHeader statement,
        @NonNull List<BankTransactionObserved> transactions) {

    public static final String EVENT_TYPE = "bankfeed.transactions.observed";
    public static final int SCHEMA_VERSION = 1;

    public BankTransactionsObservedV1 {
        if (sourceKind == null) {
            throw new IllegalArgumentException("sourceKind must not be null");
        }
        IsoCurrency.require("currency", currency);
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }
        if (transactions == null || transactions.isEmpty()) {
            throw new IllegalArgumentException("transactions must not be empty");
        }
        for (BankTransactionObserved transaction : transactions) {
            if (transaction == null) {
                throw new IllegalArgumentException("transactions must not contain null elements");
            }
        }
        transactions = List.copyOf(transactions);
    }

    /** Provenance class of a batch (§2.2). */
    public enum SourceKind {
        /** A statement file uploaded to accounting (phase 1). */
        FILE_IMPORT,
        /** A statement keyed by hand (phase 1, §4.3). */
        MANUAL_ENTRY,
        /** A connector-delivered feed (phase 2). */
        BANK_FEED
    }

    /** The change a source reports for one transaction (§2.2). */
    public enum Change {
        ADDED,
        MODIFIED,
        REMOVED
    }

    /** Settlement state of a transaction: a {@code PENDING} one is visible, never matchable (D19). */
    public enum SettlementState {
        PENDING,
        POSTED
    }

    /**
     * The statement header a file or a manual entry carries: what the bank says the account held at
     * the start and end of a window (§3.1).
     *
     * @param statementRef the bank's own statement number, when the source carries one
     * @param startDate first day of the window
     * @param endDate last day of the window ({@code >= startDate})
     * @param openingBalance balance as printed at the start of the window
     * @param closingBalance balance as printed at the end of the window
     */
    public record StatementHeader(
            @Nullable String statementRef,
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull BigDecimal openingBalance,
            @NonNull BigDecimal closingBalance) {

        public StatementHeader {
            if (startDate == null || endDate == null) {
                throw new IllegalArgumentException("startDate and endDate must not be null");
            }
            if (startDate.isAfter(endDate)) {
                throw new IllegalArgumentException("startDate " + startDate + " must not be after endDate " + endDate);
            }
            if (openingBalance == null || closingBalance == null) {
                throw new IllegalArgumentException("openingBalance and closingBalance must not be null");
            }
        }
    }

    /**
     * One observed bank transaction (§2.2). A {@link Change#REMOVED} element carries only {@code
     * sourceTransactionId} and {@code change} — a provider's removal notice has no other data, and
     * nothing is fabricated. {@code ADDED} and {@code MODIFIED} elements carry the settlement state,
     * the posting date, a non-zero signed amount and a description.
     *
     * @param sourceTransactionId the source's id for the transaction; required unless {@code ADDED}
     * @param sourceRowNumber 1-based position in the source (a file row, a manual list position)
     * @param change what the source reports
     * @param settlementState {@code PENDING} or {@code POSTED}
     * @param transactionDate the bank's posting date
     * @param authorizedDate the authorization date, when the source knows it
     * @param signedAmount positive = money into the account; never zero
     * @param currency ISO 4217 code, when the source states one per transaction
     * @param description the description as delivered
     * @param originalDescription the source's raw description, when it keeps one apart
     * @param reference the bank's reference, when present
     * @param checkNumber the check number, when present
     * @param counterpartyName the counterparty, when the source names one
     * @param categoryHint the source's category, informational only
     * @param supersedesSourceTransactionId the pending transaction a posted one replaces
     */
    public record BankTransactionObserved(
            @Nullable String sourceTransactionId,
            @Nullable Integer sourceRowNumber,
            @NonNull Change change,
            @Nullable SettlementState settlementState,
            @Nullable LocalDate transactionDate,
            @Nullable LocalDate authorizedDate,
            @Nullable BigDecimal signedAmount,
            @Nullable String currency,
            @Nullable String description,
            @Nullable String originalDescription,
            @Nullable String reference,
            @Nullable String checkNumber,
            @Nullable String counterpartyName,
            @Nullable String categoryHint,
            @Nullable String supersedesSourceTransactionId) {

        public BankTransactionObserved {
            if (change == null) {
                throw new IllegalArgumentException("change must not be null");
            }
            if (change != Change.ADDED && (sourceTransactionId == null || sourceTransactionId.isBlank())) {
                throw new IllegalArgumentException("sourceTransactionId is required for change " + change);
            }
            if (currency != null) {
                IsoCurrency.require("currency", currency);
            }
            if (change == Change.REMOVED) {
                if (settlementState != null
                        || transactionDate != null
                        || authorizedDate != null
                        || signedAmount != null
                        || description != null
                        || originalDescription != null
                        || reference != null
                        || checkNumber != null
                        || counterpartyName != null
                        || categoryHint != null
                        || supersedesSourceTransactionId != null) {
                    throw new IllegalArgumentException("a REMOVED element carries only sourceTransactionId and change");
                }
            } else {
                if (settlementState == null) {
                    throw new IllegalArgumentException("settlementState is required for change " + change);
                }
                if (transactionDate == null) {
                    throw new IllegalArgumentException("transactionDate is required for change " + change);
                }
                if (signedAmount == null || signedAmount.signum() == 0) {
                    throw new IllegalArgumentException("signedAmount must be non-zero for change " + change);
                }
                if (description == null || description.isBlank()) {
                    throw new IllegalArgumentException("description is required for change " + change);
                }
            }
        }
    }
}
