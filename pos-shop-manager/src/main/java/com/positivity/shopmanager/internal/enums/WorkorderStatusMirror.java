package com.positivity.shopmanager.internal.enums;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How this module reads pos-workorder's replicated status string (#1658).
 *
 * <p>{@code WorkorderStatus} and its {@code getOpenStatuses()} live in pos-workorder and are its
 * property; ADR-0044 puts a wall between the modules, so the enum cannot be imported here and this
 * module must never re-declare it. What it declares instead is the owner's <em>derivation</em>:
 * upstream, {@code getOpenStatuses()} is literally {@code EnumSet.complementOf(terminalStatuses)},
 * so "open" is defined here the same way — everything that is not terminal — rather than by
 * copying out the seven open names.
 *
 * <p>That distinction is the whole point. A copied list of open names silently drops any status
 * pos-workorder adds later: a new {@code AWAITING_TOW} job would vanish from the shop dashboard
 * with no error anywhere. Complementing the terminal set instead means a new status shows up as
 * open by default, which is the safe failure for an operations board and matches what the owner
 * itself would answer.
 *
 * <p>The two terminal names are the only workexec vocabulary this module hard-codes, and they are
 * the two that cannot change meaning without a breaking event-contract version.
 */
public final class WorkorderStatusMirror {

    /**
     * pos-workorder's {@code WorkorderStatus.getTerminalStatuses()}. A workorder in one of these is
     * finished; its unit is free.
     */
    public static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "CANCELLED");

    private WorkorderStatusMirror() {
        // Constant holder.
    }

    /**
     * True when a replicated status counts as open — the complement of the terminal set.
     *
     * <p>A null status (a replica row written from a fact the owner published without one) counts
     * as open: it is certainly not COMPLETED or CANCELLED, and hiding a workorder from the board
     * because of a missing field would be the worse error.
     *
     * <p>Note what this deliberately does <em>not</em> special-case: {@code READY_FOR_PICKUP} is
     * open, so the unit holding that workorder still reads as occupied. The work is done but the
     * vehicle has not left the bay, and freeing the bay would tell a dispatcher to drive another
     * car into an occupied space.
     */
    public static boolean isOpen(@Nullable String status) {
        return status == null || !TERMINAL_STATUSES.contains(status);
    }
}
