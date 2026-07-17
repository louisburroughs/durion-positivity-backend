package com.positivity.accounting.internal.enums;

/**
 * Accounting period lifecycle states.
 *
 * Two-state lifecycle per decision D-7 (plan-odoo-parity-pos-accounting, story B1):
 * OPEN -> CLOSED, reopenable back to OPEN with mandatory justification.
 * No soft-close CLOSING state in v1.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B1</a>
 */
public enum AccountingPeriodStatus {
    /**
     * Period accepts postings. Missing period rows are treated as OPEN
     * (auto-provisioned on first posting).
     */
    OPEN,

    /**
     * Period is closed for posting (AD-012). Reopening requires elevated
     * permission and a mandatory justification.
     */
    CLOSED
}
