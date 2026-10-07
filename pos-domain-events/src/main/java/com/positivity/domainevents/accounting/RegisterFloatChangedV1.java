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
 * float changes: the go-live float, a Change float, the reversal of either (the amount is then
 * re-derived from the float entries still standing), or a relocation of the register to another
 * location (AW32, #2571). The envelope's aggregate is the register's float row, so its version
 * orders the facts of one register; it is republished with the current state at every start
 * (ADR-0044 §4). pos-order sets a session's opening float from it (S16).
 * Amounts are in the tenant's functional currency (ADR-0067), stated by {@code currencyCode}.
 *
 * <p>Schema version 2 (#2571) added {@link Kind#RELOCATION} and {@code previousLocationId}, and a
 * relocation of a register whose float is zero posts nothing, so its {@code journalEntryId} is
 * null — additive only (ADR-0044 §3): a version-1 consumer that copies {@code locationId} and
 * {@code amount} handles a relocation as it is; a version-2 consumer must treat the new field as
 * absent on old events.
 *
 * <p>Schema version 3 (#2577; ADR-0067 R-1, PC-8) added {@code currencyCode}, the ISO 4217 code of
 * {@code amount} and {@code previousAmount}, in place and additively (ADR-0044 §3). pos-accounting
 * always states it; a consumer reads it as absent on a version-1 or version-2 event, where an absent
 * currency means the tenant's functional currency (PC-8).
 *
 * @param registerId the register: pos-order's {@code terminalId} (AW31)
 * @param locationId the location the register belongs to; for a relocation, the destination
 * @param amount the register's float now; negative only when a reversal removed more than stands (the
 *     ledger shows the same on 1080), never through a command
 * @param previousAmount the float before this change; equal to {@link #amount} on a republish and
 *     on a relocation, which moves the float without changing it
 * @param kind what changed it
 * @param effectiveDate the date the change was posted on
 * @param journalEntryId the journal entry that changed it (for a reversal, the reversal entry; for a
 *     relocation, the reclass entry); null only for a relocation of a zero float, which posts nothing
 * @param previousLocationId the location the register was held at before a relocation; null on every
 *     other kind and on a republish, so it is non-null exactly when this fact moved the register
 *     (schema version 2)
 * @param currencyCode the ISO 4217 code of {@code amount} and {@code previousAmount}: the currency the
 *     register's float is held in, the tenant's functional currency (schema version 3); null only on an
 *     older event, and three upper-case letters when stated
 */
public record RegisterFloatChangedV1(
        @NonNull String registerId,
        @NonNull UUID locationId,
        @NonNull BigDecimal amount,
        @NonNull BigDecimal previousAmount,
        @NonNull Kind kind,
        @NonNull LocalDate effectiveDate,
        @Nullable UUID journalEntryId,
        @Nullable UUID previousLocationId,
        @Nullable String currencyCode) {

    public static final String EVENT_TYPE = "accounting.float.changed";
    public static final int SCHEMA_VERSION = 3;

    /**
     * What changed the float. Consumers are state-based (they apply {@code amount} and {@code
     * locationId} whatever the kind), so a kind added later must not fail them: a value this build
     * does not know reads as {@link #UNKNOWN} (#2512). {@link #RELOCATION} arrived in schema version 2 (#2571).
     */
    public enum Kind {
        /** The once-only go-live float, against opening balance equity (AW17). */
        GO_LIVE,
        /** A Change float against a bank account (AW16). */
        CHANGE,
        /** The reversal of a go-live or change entry. */
        REVERSAL,
        /** The register moved to another location; the float moves with it, unchanged (AW32, #2571). */
        RELOCATION,
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
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        if (amount == null || previousAmount == null) {
            throw new IllegalArgumentException("amount and previousAmount must not be null");
        }
        if (kind == null || effectiveDate == null) {
            throw new IllegalArgumentException("kind and effectiveDate must not be null");
        }
        // Only the kinds that always post must name their entry; any other kind, a relocation of a zero float or
        // one a newer producer adds, may carry none, so a tolerant reader never throws on it.
        if (journalEntryId == null && (kind == Kind.GO_LIVE || kind == Kind.CHANGE || kind == Kind.REVERSAL)) {
            throw new IllegalArgumentException("journalEntryId must not be null for " + kind);
        }
        // Absent on an older event; when stated, an ISO 4217 code is three upper-case letters (ADR-0067 R-3).
        if (currencyCode != null && !currencyCode.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currencyCode must be three upper-case letters, was " + currencyCode);
        }
    }
}
