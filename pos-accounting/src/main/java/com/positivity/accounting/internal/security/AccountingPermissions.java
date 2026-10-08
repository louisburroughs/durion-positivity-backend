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

    /**
     * Send a vendor bill for approval, correct a match exception, select a match candidate, enter its real due date,
     * and approve or accept it within the clerk limit (#2509, #2510; SPEC-accounting-workspace §4.3, AW4, AW5, AW31):
     * ACCOUNTING_CLERK, CONTROLLER, GENERAL_MANAGER, ADMIN.
     */
    public static final String AP_APPROVE = "accounting:ap:approve";

    /**
     * Read and change the AP approval policy: the clerk and automatic approval limits, the two separation-of-duties
     * exception switches and the default AP terms (CAP:550 S13, #2510; SPEC-accounting-workspace §4.3, §5.5; AW4, AW5,
     * AW33): CONTROLLER, GENERAL_MANAGER, ADMIN.
     */
    public static final String AP_APPROVAL_POLICY_MANAGE = "accounting:ap_approval_policy:manage";

    /**
     * Approve a vendor bill over the clerk limit, including ACCEPT of a match exception and the void of an approved
     * bill (#2509, #2510; AW4, AW5): CONTROLLER, GENERAL_MANAGER, ADMIN.
     */
    public static final String AP_APPROVE_OVER_LIMIT = "accounting:ap:approve_over_limit";

    /** Reject a vendor bill awaiting approval, void a match exception or an approved bill (#2509): as AP_APPROVE. */
    public static final String AP_REJECT = "accounting:ap:reject";

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

    /**
     * Establish a register's go-live float and change it (#2511; SPEC-accounting-workspace §4.6
     * "Float", AW16-AW17, AW31): CONTROLLER and ADMIN.
     */
    public static final String FLOAT_MANAGE = "accounting:float:manage";

    /**
     * Read the undeposited register sessions and record a bank deposit of their drawer cash (CAP:550 S18, #2514;
     * SPEC-accounting-workspace §4.5, §7.1, AW10; Security sign-off OI-5/AW31): ACCOUNTING_CLERK, CONTROLLER and ADMIN.
     */
    public static final String DEPOSIT_CREATE = "accounting:deposit:create";

    /**
     * Reverse a bank deposit of drawer cash (CAP:550 S18, #2514; §4.5, ADR-0047; Security sign-off OI-5/AW31):
     * CONTROLLER and ADMIN.
     */
    public static final String DEPOSIT_REVERSE = "accounting:deposit:reverse";

    /** Create gl mapping. */
    public static final String GL_MAPPING_CREATE = "accounting:gl-mapping:create";

    /** Resolve gl mapping. */
    public static final String GL_MAPPING_RESOLVE = "accounting:gl-mapping:resolve";

    /** Reconcile invoice revenue GL postings from the invoice replica (#1851). */
    public static final String GL_RECONCILE = "accounting:gl:reconcile";

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

    /**
     * Assign a customer, once and with a justification, to a payment received without one (AD-004).
     * Registered ahead of its endpoint (CAP:550 S3); no role holds it until that endpoint enforces it.
     */
    public static final String PAYMENT_ASSIGN_CUSTOMER = "accounting:payment:assign-customer";

    /** Reverse payment. */
    public static final String PAYMENT_REVERSE = "accounting:payment:reverse";

    /** Close an accounting period. */
    public static final String PERIOD_CLOSE = "accounting:period:close";

    /** Set the accounting hard-lock date. */
    public static final String PERIOD_HARD_LOCK = "accounting:period:hard_lock";

    /** Post into a closed accounting period with justification. */
    public static final String PERIOD_OVERRIDE = "accounting:period:override";

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

    /**
     * Prepare reconciliations: manually match or write off settlement lines; import and enter bank statements
     * (a corrected one included), review possible duplicates, maintain bank-account profiles, and create,
     * match, register outstanding items on, adjust and submit bank reconciliations (SPEC §6.2).
     */
    public static final String RECONCILIATION_ADJUST = "accounting:reconciliation:adjust";

    /**
     * Approve bank reconciliation work — the separation-of-duties key (SPEC-manual-bank-reconciliation
     * §6.2, D3): approve (finalize), return, cancel and supersede reconciliations (story S5, #2304); exclude and
     * restore bank transactions (S2); reverse adjustments, clear outstanding items in a gap and post OTHER
     * adjustments above the tenant threshold (S4); read a retained import file (S3).
     */
    public static final String RECONCILIATION_APPROVE = "accounting:reconciliation:approve";

    /** View processor settlements, bank statements, bank transactions and reconciliations. */
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
