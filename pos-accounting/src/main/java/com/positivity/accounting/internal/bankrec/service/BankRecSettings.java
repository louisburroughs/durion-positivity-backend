package com.positivity.accounting.internal.bankrec.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tunables of the reconciliation core (SPEC-manual-bank-reconciliation §3.6, §4.5, §4.6; story S4,
 * #2303), each a property with the spec's default:
 *
 * <ul>
 *   <li>{@code pos.accounting.bankrec.match.date-window-days} (7) — W, the candidate date window;
 *   <li>{@code pos.accounting.bankrec.duplicate.date-window-days} (3) — the near-duplicate window;
 *   <li>{@code pos.accounting.bankrec.outstanding.aging-warning-days} (90) — when an item is aged.
 * </ul>
 */
@Component
public class BankRecSettings {

    private final int matchDateWindowDays;
    private final int duplicateDateWindowDays;
    private final int agingWarningDays;

    public BankRecSettings(
            @Value("${pos.accounting.bankrec.match.date-window-days:7}") int matchDateWindowDays,
            @Value("${pos.accounting.bankrec.duplicate.date-window-days:3}") int duplicateDateWindowDays,
            @Value("${pos.accounting.bankrec.outstanding.aging-warning-days:90}") int agingWarningDays) {
        if (matchDateWindowDays < 1 || duplicateDateWindowDays < 0 || agingWarningDays < 0) {
            throw new IllegalStateException("pos.accounting.bankrec windows must be positive; match window was "
                    + matchDateWindowDays + ", duplicate window " + duplicateDateWindowDays + ", aging "
                    + agingWarningDays);
        }
        this.matchDateWindowDays = matchDateWindowDays;
        this.duplicateDateWindowDays = duplicateDateWindowDays;
        this.agingWarningDays = agingWarningDays;
    }

    /** The spec defaults (7, 3, 90). */
    public static BankRecSettings defaults() {
        return new BankRecSettings(7, 3, 90);
    }

    /** W: a ledger line within W days of a bank transaction is a candidate (§4.6). */
    public int matchDateWindowDays() {
        return matchDateWindowDays;
    }

    /** A near-duplicate candidate is dated within this many days of the row under review (§4.5). */
    public int duplicateDateWindowDays() {
        return duplicateDateWindowDays;
    }

    /** An item dated more than this many days before a date is aged at that date (§3.6). */
    public int agingWarningDays() {
        return agingWarningDays;
    }
}
