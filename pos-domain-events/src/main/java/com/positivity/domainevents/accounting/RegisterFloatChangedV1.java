package com.positivity.domainevents.accounting;

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
 * Amounts are in the tenant's functional currency (ADR-0067).
 *
 * <p>Schema version 2 (#2571) added {@link Kind#RELOCATION} and {@code previousLocationId}, and a
 * relocation of a register whose float is zero posts nothing, so its {@code journalEntryId} is
 * null — additive only (ADR-0044 §3): a version-1 consumer that copies {@code locationId} and
 * {@code amount} handles a relocation as it is; a version-2 consumer must treat the new field as
 * absent on old events.
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
 */
public record RegisterFloatChangedV1(
        @NonNull String registerId,
        @NonNull UUID locationId,
        @NonNull BigDecimal amount,
        @NonNull BigDecimal previousAmount,
        @NonNull Kind kind,
        @NonNull LocalDate effectiveDate,
        @Nullable UUID journalEntryId,
        @Nullable UUID previousLocationId) {

    public static final String EVENT_TYPE = "accounting.float.changed";
    public static final int SCHEMA_VERSION = 2;

    /** What changed the float. */
    public enum Kind {
        /** The once-only go-live float, against opening balance equity (AW17). */
        GO_LIVE,
        /** A Change float against a bank account (AW16). */
        CHANGE,
        /** The reversal of a go-live or change entry. */
        REVERSAL,
        /** The register moved to another location; the float moves with it, unchanged (AW32). */
        RELOCATION
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
        if (journalEntryId == null && kind != Kind.RELOCATION) {
            throw new IllegalArgumentException("journalEntryId must not be null for " + kind);
        }
    }
}
