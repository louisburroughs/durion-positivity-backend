package com.positivity.accounting.internal.security;

/**
 * Permission names this module enforces, as constants rather than string literals at each call
 * site.
 *
 * <h2>Why constants and not literals</h2>
 *
 * A literal is invisible to a reader looking for everywhere a permission is used, and it is one
 * typo away from an authority nobody holds — {@code @PreAuthorize} fails closed, so a misspelling
 * does not break the build or the test suite, it silently locks the endpoint. Naming the permission
 * once means the compiler checks every use of it.
 *
 * <p>The repo-wide permission tooling reads these too:
 * {@code scripts/generate-permissions.sh --sync} resolves constant references when it decides
 * whether a permission is registered in the catalogs, so a permission introduced here is picked up
 * without a manual bit assignment.
 */
public final class AccountingPermissions {
    /** View Wave 2 read-only accounting analytics (invoiced-vs-collected, payment-lag cohorts). */
    public static final String ANALYTICS_VIEW = "accounting:analytics:view";

    /** Process payments. */
    public static final String AP_PAY = "accounting:ap:pay";

    /** View accounts payable. */
    public static final String AP_VIEW = "accounting:ap:view";

    /** Create accounts in chart of accounts. */
    public static final String COA_CREATE = "accounting:coa:create";

    /** Deactivate coa. */
    public static final String COA_DEACTIVATE = "accounting:coa:deactivate";

    /** Edit accounts in chart of accounts. */
    public static final String COA_EDIT = "accounting:coa:edit";

    /** View chart of accounts. */
    public static final String COA_VIEW = "accounting:coa:view";

    /** Create credit memo. */
    public static final String CREDIT_MEMO_CREATE = "accounting:credit-memo:create";

    /** Read credit memo. */
    public static final String CREDIT_MEMO_READ = "accounting:credit-memo:read";

    /** Void a posted credit memo, restoring AR and the reversed tax liability. */
    public static final String CREDIT_MEMO_VOID = "accounting:credit-memo:void";

    /** Apply an open customer credit to an invoice. */
    public static final String CUSTOMER_CREDIT_APPLY = "accounting:customer-credit:apply";

    /** Refund an open customer credit to the customer. */
    public static final String CUSTOMER_CREDIT_REFUND = "accounting:customer-credit:refund";

    /** View AR customer credits and their open amounts. */
    public static final String CUSTOMER_CREDIT_VIEW = "accounting:customer-credit:view";

    /** Create default mapping. */
    public static final String DEFAULT_MAPPING_CREATE = "accounting:default-mapping:create";

    /** Delete default mapping. */
    public static final String DEFAULT_MAPPING_DELETE = "accounting:default-mapping:delete";

    /** Edit default mapping. */
    public static final String DEFAULT_MAPPING_EDIT = "accounting:default-mapping:edit";

    /** View default mapping. */
    public static final String DEFAULT_MAPPING_VIEW = "accounting:default-mapping:view";

    /** Reprocess a suspended accounting event. */
    public static final String EVENTS_REPROCESS = "accounting:events:reprocess";

    /** Retry failed accounting events. */
    public static final String EVENTS_RETRY = "accounting:events:retry";

    /** Submit accounting events. */
    public static final String EVENTS_SUBMIT = "accounting:events:submit";

    /** View accounting events. */
    public static final String EVENTS_VIEW = "accounting:events:view";

    /** Request an accounting data export. */
    public static final String EXPORT_REQUEST = "accounting:export:request";

    /** View accounting export status and history. */
    public static final String EXPORT_VIEW = "accounting:export:view";

    /** Create gl mapping. */
    public static final String GL_MAPPING_CREATE = "accounting:gl-mapping:create";

    /** Resolve gl mapping. */
    public static final String GL_MAPPING_RESOLVE = "accounting:gl-mapping:resolve";

    /** Create journal entries. */
    public static final String JE_CREATE = "accounting:je:create";

    /** Post journal entries. */
    public static final String JE_POST = "accounting:je:post";

    /** Reverse je. */
    public static final String JE_REVERSE = "accounting:je:reverse";

    /** View journal entries. */
    public static final String JE_VIEW = "accounting:je:view";

    /** Create mapping key. */
    public static final String MAPPING_KEY_CREATE = "accounting:mapping-key:create";

    /** Deactivate mapping key. */
    public static final String MAPPING_KEY_DEACTIVATE = "accounting:mapping-key:deactivate";

    /** Edit mapping key. */
    public static final String MAPPING_KEY_EDIT = "accounting:mapping-key:edit";

    /** View mapping key. */
    public static final String MAPPING_KEY_VIEW = "accounting:mapping-key:view";

    /** Apply payment. */
    public static final String PAYMENT_APPLY = "accounting:payment:apply";

    /** Reverse payment. */
    public static final String PAYMENT_REVERSE = "accounting:payment:reverse";

    /** Close an accounting period. */
    public static final String PERIOD_CLOSE = "accounting:period:close";

    /** Set the accounting hard-lock date. */
    public static final String PERIOD_HARD_LOCK = "accounting:period:hard_lock";

    /** Reopen a closed accounting period. */
    public static final String PERIOD_REOPEN = "accounting:period:reopen";

    /** View accounting periods. */
    public static final String PERIOD_VIEW = "accounting:period:view";

    /** Create posting category. */
    public static final String POSTING_CATEGORY_CREATE = "accounting:posting-category:create";

    /** Deactivate posting category. */
    public static final String POSTING_CATEGORY_DEACTIVATE = "accounting:posting-category:deactivate";

    /** Edit posting category. */
    public static final String POSTING_CATEGORY_EDIT = "accounting:posting-category:edit";

    /** View posting category. */
    public static final String POSTING_CATEGORY_VIEW = "accounting:posting-category:view";

    /** Archive posting rules. */
    public static final String POSTING_RULES_ARCHIVE = "accounting:posting_rules:archive";

    /** Create posting rules. */
    public static final String POSTING_RULES_CREATE = "accounting:posting_rules:create";

    /** Publish posting rules. */
    public static final String POSTING_RULES_PUBLISH = "accounting:posting_rules:publish";

    /** View posting rules. */
    public static final String POSTING_RULES_VIEW = "accounting:posting_rules:view";

    /** Manually match or write off settlement lines. */
    public static final String RECONCILIATION_ADJUST = "accounting:reconciliation:adjust";

    /** View processor settlements and reconciliation lines. */
    public static final String RECONCILIATION_VIEW = "accounting:reconciliation:view";

    /** Export report. */
    public static final String REPORT_EXPORT = "accounting:report:export";

    /** Freeze the sales-tax liability report for a closed accounting period. */
    public static final String TAX_SNAPSHOT_FREEZE = "accounting:tax-snapshot:freeze";

    // ── Permissions owned by other domains ──────────────────────────────────────────────
    //
    // Declared here so this module's call sites are constants like every other, but the names
    // belong elsewhere. Their definition, bit assignment and description live with their owner —
    // this is a reference, not a claim of ownership.

    /** Owned by the reporting domain. */
    public static final String REPORTING_VIEW_FINANCIAL_STATEMENTS = "reporting:view:financial-statements";

    private AccountingPermissions() {
        // Utility class - prevent instantiation
    }
}
