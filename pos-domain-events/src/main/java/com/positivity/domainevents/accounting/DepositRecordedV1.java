package com.positivity.domainevents.accounting;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code accounting.deposit.recorded} v1 on {@code accounting.events.v1} (SPEC-accounting-workspace §4.5,
 * §7.1 "Events"; AW10; story S18, #2514).
 *
 * <p>Published by pos-accounting through its transactional outbox when a bank deposit of drawer cash is recorded, and
 * again, with {@code status = REVERSED}, when its entry is reversed (by Reverse deposit or as a journal entry,
 * ADR-0047). The envelope's aggregate is the deposit, so its version orders the two facts of one deposit. A consumer
 * is state-based: it applies the latest status of each deposit.
 *
 * @param depositId the deposit
 * @param bankGlAccountId the bank account the drawer cash went to (a {@code BANK_CASH} GL account)
 * @param depositDate the day the cash reached the bank: the entry's date
 * @param amount the bank debit: the total of the sessions' bank drops; always positive
 * @param currencyCode the ISO 4217 code of {@code amount} (ADR-0067 R-1): the tenant's functional currency
 * @param sessionIds the register sessions the deposit took whole, oldest close first; never empty as published
 * @param status {@code RECORDED}, or {@code REVERSED} once the entry is reversed
 * @param journalEntryId the deposit's own entry (the reversal's entry is a separate one)
 */
public record DepositRecordedV1(
        @NonNull UUID depositId,
        @NonNull UUID bankGlAccountId,
        @NonNull LocalDate depositDate,
        @NonNull BigDecimal amount,
        @NonNull String currencyCode,
        @NonNull List<UUID> sessionIds,
        @NonNull Status status,
        @NonNull UUID journalEntryId) {

    public static final String EVENT_TYPE = "accounting.deposit.recorded";
    public static final int SCHEMA_VERSION = 1;

    /**
     * Where the deposit stands. A value this build does not know reads as {@link #UNKNOWN}, so a status added later
     * never fails a consumer.
     */
    public enum Status {
        /** The deposit's entry stands. */
        RECORDED,
        /** The deposit's entry was reversed; its sessions wait to be deposited again. */
        REVERSED,
        /** A status published by a newer producer than this build knows; never published itself. */
        UNKNOWN;

        /** Reads a wire value, mapping one this build does not know to {@link #UNKNOWN}. */
        @JsonCreator
        public static Status fromWire(@Nullable String value) {
            if (value == null) {
                return UNKNOWN;
            }
            for (Status status : values()) {
                if (status.name().equals(value)) {
                    return status;
                }
            }
            return UNKNOWN;
        }
    }

    public DepositRecordedV1 {
        if (depositId == null || bankGlAccountId == null || journalEntryId == null) {
            throw new IllegalArgumentException("depositId, bankGlAccountId and journalEntryId must not be null");
        }
        if (depositDate == null || status == null) {
            throw new IllegalArgumentException("depositDate and status must not be null");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currencyCode must be three upper-case letters, was " + currencyCode);
        }
        // pos-accounting always names at least one session; the record itself only refuses a missing list, so a
        // tolerant reader (and DomainEventContractTest's neutral sample) never throws on an empty one.
        if (sessionIds == null) {
            throw new IllegalArgumentException("sessionIds must not be null");
        }
        sessionIds = List.copyOf(sessionIds);
    }
}
