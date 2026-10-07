package com.positivity.domainevents.accounting;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code accounting.float.changed} v1 on {@code accounting.events.v1}
 * (SPEC-accounting-workspace §4.6 "Float", §7.1 "Float"; AW16, AW17; story S15, #2511).
 *
 * <p>Published by pos-accounting through its transactional outbox whenever a register's change
 * float changes: the go-live float, a Change float, or the reversal of either (the amount is then
 * re-derived from the float entries still standing). The envelope's aggregate is the register's
 * float row, so its version orders the facts of one register; it is republished with the current
 * state at every start (ADR-0044 §4). pos-order sets a session's opening float from it (S16).
 * Amounts are in the tenant's functional currency (ADR-0067).
 *
 * @param registerId the register: pos-order's {@code terminalId} (AW31)
 * @param locationId the location the register belongs to
 * @param amount the register's float now; negative only when a reversal removed more than stands (the
 *     ledger shows the same on 1080), never through a command
 * @param previousAmount the float before this change; equal to {@link #amount} on a republish
 * @param kind what changed it
 * @param effectiveDate the date the change was posted on
 * @param journalEntryId the journal entry that changed it (for a reversal, the reversal entry)
 */
public record RegisterFloatChangedV1(
        @NonNull String registerId,
        @NonNull UUID locationId,
        @NonNull BigDecimal amount,
        @NonNull BigDecimal previousAmount,
        @NonNull Kind kind,
        @NonNull LocalDate effectiveDate,
        @NonNull UUID journalEntryId) {

    public static final String EVENT_TYPE = "accounting.float.changed";
    public static final int SCHEMA_VERSION = 1;

    /**
     * What changed the float. Consumers are state-based (they apply {@code amount} and {@code
     * locationId} whatever the kind), so a kind added later must not fail them: a value this build
     * does not know reads as {@link #UNKNOWN} (#2512; a relocation kind is planned by #2571).
     */
    public enum Kind {
        /** The once-only go-live float, against opening balance equity (AW17). */
        GO_LIVE,
        /** A Change float against a bank account (AW16). */
        CHANGE,
        /** The reversal of a go-live or change entry. */
        REVERSAL,
        /** A kind published by a newer producer than this build knows; never published itself. */
        UNKNOWN;

        /** Reads a wire value, mapping one this build does not know to {@link #UNKNOWN}. */
        @JsonCreator
        public static Kind fromWire(@Nullable String value) {
            if (value == null) {
                return UNKNOWN;
            }
            for (Kind kind : values()) {
                if (kind.name().equals(value)) {
                    return kind;
                }
            }
            return UNKNOWN;
        }
    }

    public RegisterFloatChangedV1 {
        if (registerId == null || registerId.isBlank()) {
            throw new IllegalArgumentException("registerId must not be blank");
        }
        if (locationId == null || journalEntryId == null) {
            throw new IllegalArgumentException("locationId and journalEntryId must not be null");
        }
        if (amount == null || previousAmount == null) {
            throw new IllegalArgumentException("amount and previousAmount must not be null");
        }
        if (kind == null || effectiveDate == null) {
            throw new IllegalArgumentException("kind and effectiveDate must not be null");
        }
    }
}
