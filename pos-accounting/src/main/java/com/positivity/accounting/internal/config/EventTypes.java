package com.positivity.accounting.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-accounting module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the accounting module.
     * Total: 161 event types (includes +6 from the vendor-bill approval lifecycle and -1 for the retired
     * VENDOR_BILL_GL_POSTING (CAP:550 S12, Issue #2509): ACCOUNTING_VENDOR_BILL_SUBMIT, _APPROVE, _REJECT, _VOID,
     * _STAGES_VIEW, _STAGE_LIST, +4 from bank deposits of drawer cash (CAP:550 S18, Issue #2514):
     * ACCOUNTING_UNDEPOSITED_SESSIONS_VIEW, ACCOUNTING_DEPOSIT_CREATE, ACCOUNTING_DEPOSIT_VIEW,
     * ACCOUNTING_DEPOSIT_REVERSE, +1 from the bank opening balance (CAP:550, Issue #2572):
     * ACCOUNTING_BANK_OPENING_BALANCE_ESTABLISH, +1 from the unpaid walk-in sales read (CAP:550 S11, Issue #2508):
     * ACCOUNTING_UNPAID_WALK_IN_SALES_VIEW, +1 from the automatic payment applications read (CAP:550 S2,
     * Issue #2503): ACCOUNTING_PAYMENT_APPLICATION_AUTOMATIC_LIST_VIEW, +2 from the receivables worklist
     * reads (CAP:550 S1, Issue #2502):
     * ACCOUNTING_RECEIVABLE_PAYMENT_LIST_VIEW, ACCOUNTING_CUSTOMER_OPEN_INVOICES_VIEW, +2 from tenant
     * template provisioning (CAP:550 S37, Issue #2526):
     * ACCOUNTING_TENANT_TEMPLATE_STATUS_VIEW, ACCOUNTING_TENANT_TEMPLATE_ADD_ON_ENABLE,
     * +4 from the reconciliation approval workflow
     * (SPEC-manual-bank-reconciliation story S5, Issue #2304): ACCOUNTING_RECONCILIATION_SUBMIT,
     * ACCOUNTING_RECONCILIATION_RETURN, ACCOUNTING_RECONCILIATION_SUPERSEDE, ACCOUNTING_RECONCILIATION_CANCEL,
     * +11 from the reconciliation core (SPEC-manual-bank-reconciliation
     * story S4, Issue #2303): ACCOUNTING_RECONCILIATION_CREATE, ACCOUNTING_RECONCILIATION_CANDIDATES,
     * ACCOUNTING_RECONCILIATION_AUTO_MATCH, ACCOUNTING_RECONCILIATION_MATCH_ACCEPT,
     * ACCOUNTING_RECONCILIATION_MATCH_REJECT, ACCOUNTING_RECONCILIATION_OUTSTANDING_REGISTER,
     * ACCOUNTING_RECONCILIATION_OUTSTANDING_RELEASE, ACCOUNTING_RECONCILIATION_OUTSTANDING_REAFFIRM,
     * ACCOUNTING_RECONCILIATION_OUTSTANDING_CLEAR_IN_GAP, ACCOUNTING_RECONCILIATION_ADJUSTMENT_REVERSE,
     * ACCOUNTING_RECONCILIATION_REVIEW, +9 from statement-file imports (story S3, Issue #2302):
     * ACCOUNTING_BANK_IMPORT_CREATE, ACCOUNTING_BANK_IMPORT_LIST, ACCOUNTING_BANK_IMPORT_GET,
     * ACCOUNTING_BANK_IMPORT_ROWS, ACCOUNTING_BANK_IMPORT_MAPPING_SET, ACCOUNTING_BANK_IMPORT_ROW_CORRECT,
     * ACCOUNTING_BANK_IMPORT_COMMIT, ACCOUNTING_BANK_IMPORT_DISCARD, ACCOUNTING_BANK_IMPORT_FILE_READ,
     * +10 from bank statements, transactions and accounts
     * (SPEC-manual-bank-reconciliation story S2, Issue #2301): ACCOUNTING_BANK_STATEMENT_CREATE,
     * ACCOUNTING_BANK_STATEMENT_LIST, ACCOUNTING_BANK_STATEMENT_GET, ACCOUNTING_BANK_TRANSACTION_LIST,
     * ACCOUNTING_BANK_TRANSACTION_GET, ACCOUNTING_BANK_TRANSACTION_DUPLICATE_REVIEW,
     * ACCOUNTING_BANK_TRANSACTION_EXCLUDE, ACCOUNTING_BANK_TRANSACTION_RESTORE,
     * ACCOUNTING_BANK_ACCOUNT_LIST, ACCOUNTING_BANK_ACCOUNT_PROFILE_SET, and +3 from the Wave 2 vendor-spend / vendor-bill-list /
     * payment-application-list endpoints (Issues #1596 E8 / #1597 E9 / #1598 E10):
     * ACCOUNTING_ANALYTICS_VENDOR_SPEND_VIEW, ACCOUNTING_VENDOR_BILL_LIST_VIEW,
     * ACCOUNTING_PAYMENT_APPLICATION_LIST_VIEW, +2 from the Wave 2 read-only analytics endpoints
     * (Issues #1590 E2 / #1591 E3): ACCOUNTING_ANALYTICS_COLLECTIONS_VIEW,
     * ACCOUNTING_ANALYTICS_PAYMENT_LAG_COHORTS_VIEW, +1 from the credit-memo void (issue #997
     * symmetry): ACCOUNTING_CREDIT_MEMO_VOID, +4 from the customer-credit lifecycle
     * (PR #1004): ACCOUNTING_CUSTOMER_CREDIT_LIST, ACCOUNTING_CUSTOMER_CREDIT_GET,
     * ACCOUNTING_CUSTOMER_CREDIT_APPLY, ACCOUNTING_CUSTOMER_CREDIT_REFUND,
     * +4 from the tax-liability period-close freeze
     * (Issue #998 Phase-2 item 2): TAX_LIABILITY_SNAPSHOT_FREEZE,
     * TAX_LIABILITY_SNAPSHOT_LIST, TAX_LIABILITY_SNAPSHOT_GET,
     * TAX_LIABILITY_SNAPSHOT_VERIFY, +1 from the sales-tax liability report
     * (Story T8, Issue #966): REPORT_TAX_LIABILITY_GENERATE, +9 from manual CSV bank reconciliation
     * (Story F2, Issue #965; its ACCOUNTING_RECONCILIATION_IMPORT is retired, D14, #2302):
     * ACCOUNTING_RECONCILIATION_MATCH, ACCOUNTING_RECONCILIATION_UNMATCH,
     * ACCOUNTING_RECONCILIATION_ADJUSTMENT, ACCOUNTING_RECONCILIATION_FINALIZE,
     * ACCOUNTING_RECONCILIATION_LIST, ACCOUNTING_RECONCILIATION_GET,
     * ACCOUNTING_RECONCILIATION_REPORT, ACCOUNTING_RECONCILIATION_AUDIT,
     * ACCOUNTING_RECONCILIATION_ADJUSTMENT_TYPES_LIST, +3 from settlement reconciliation
     * (Story F1c, Issue #963): ACCOUNTING_SETTLEMENT_LINES_LIST,
     * ACCOUNTING_SETTLEMENT_LINE_MATCH, ACCOUNTING_SETTLEMENT_LINE_WRITE_OFF, +3 from the GL + aged AR/AP reports
     * (Story G2, Issue #960): REPORT_GENERAL_LEDGER_GENERATE,
     * REPORT_AGED_RECEIVABLES_GENERATE, REPORT_AGED_PAYABLES_GENERATE, +1 from AR payment application reversal GL
     * posting (Story C2, Issue #958): PAYMENT_APPLICATION_REVERSAL_GL_POSTING, +1
     * from the mapping resolution dry-run
     * (Story E3, Issue #957): ACCOUNTING_MAPPING_RESOLVE_TEST, and +2 from CAP-251 #5:
     * ACCOUNTING_STATUS_SYNC_PROCESS and ACCOUNTING_STATUS_VIEW, in addition to
     * CAP-053 Vendor Bill workflow + GL Mapping, +3 from PRD missing endpoints:
     * ACCOUNTING_REPORT_EXPORT_REQUEST, ACCOUNTING_REPORT_EXPORT_STATUS,
     * ACCOUNTING_REPORT_EXPORT_LIST, +2 from the vendor directory
     * (Issue #816): ACCOUNTING_VENDOR_SEARCH, ACCOUNTING_VENDOR_GET, +3
     * from accounting period lifecycle (Story B1, Issue #937):
     * ACCOUNTING_PERIOD_LIST, ACCOUNTING_PERIOD_CLOSE,
     * ACCOUNTING_PERIOD_REOPEN, +2 previously emitted but unregistered
     * journal-entry lifecycle events (Story A3, Issue #943):
     * ACCOUNTING_JOURNAL_ENTRY_POST, ACCOUNTING_JOURNAL_ENTRY_REVERSE, and
     * +2 from the org-level hard-lock date (Story B2, Issue #944):
     * ACCOUNTING_PERIOD_HARD_LOCK_VIEW, ACCOUNTING_PERIOD_HARD_LOCK_SET, and
     * +1 from AR cash-receipt GL posting (Story C1, Issue #954):
     * PAYMENT_APPLICATION_GL_POSTING, and +1 from the trial balance report
     * (Story G1, Issue #956): REPORT_TRIAL_BALANCE_GENERATE).
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // JournalEntryController - 5 events
                EventTypeRegistration.search(
                                "ACCOUNTING_JOURNAL_ENTRY_LIST", "List journal entries with optional filters")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_JOURNAL_ENTRY_CREATE", "Create a new journal entry")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_JOURNAL_ENTRY_UPDATE", "Update an existing journal entry")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_JOURNAL_ENTRY_POST", "Post a draft journal entry to the ledger")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_JOURNAL_ENTRY_REVERSE",
                                "Reverse a posted journal entry (original flips to REVERSED)")
                        .build(),

                // TenantTemplateController - 2 events (CAP:550 S37, #2526)
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_TENANT_TEMPLATE_STATUS_VIEW",
                                "View where the tenant stands against the accounting template")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_TENANT_TEMPLATE_ADD_ON_ENABLE",
                                "Turn the retread-plant accounting template add-on on for the tenant")
                        .build(),

                // RegisterFloatController - 3 events (CAP:550 S15, #2511; relocation #2571)
                EventTypeRegistration.write(
                                "ACCOUNTING_REGISTER_FLOAT_GO_LIVE", "Establish a register's go-live change float")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_REGISTER_FLOAT_CHANGE", "Change a register's float")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_REGISTER_FLOAT_RELOCATE", "Move a register's float to another location")
                        .build(),

                // BankOpeningBalanceController - 1 event (CAP:550, #2572)
                EventTypeRegistration.write(
                                "ACCOUNTING_BANK_OPENING_BALANCE_ESTABLISH",
                                "Establish a bank account's opening balance at cutover")
                        .build(),

                // BankDepositController - 4 events (CAP:550 S18, #2514)
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_UNDEPOSITED_SESSIONS_VIEW",
                                "List the closed register sessions whose drawer cash is not yet deposited")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_DEPOSIT_CREATE", "Record a bank deposit of closed sessions' drawer cash")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_DEPOSIT_VIEW", "View a bank deposit of drawer cash")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_DEPOSIT_REVERSE", "Reverse a bank deposit of drawer cash")
                        .build(),

                // PettyExpenseCategoryController - 5 events (CAP:550 S15, #2511)
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_PETTY_EXPENSE_CATEGORY_LIST", "List petty-expense categories")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_PETTY_EXPENSE_CATEGORY_CREATE", "Create a petty-expense category")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_PETTY_EXPENSE_CATEGORY_UPDATE", "Relabel a petty-expense category")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_PETTY_EXPENSE_CATEGORY_DEACTIVATE", "Deactivate a petty-expense category")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_PETTY_EXPENSE_CATEGORY_REMAP", "Change a petty-expense category's account")
                        .build(),

                // GLAccountController - 6 events
                EventTypeRegistration.search("ACCOUNTING_GL_ACCOUNT_LIST", "List GL accounts with pagination")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_CREATE", "Create a new GL account")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_UPDATE", "Update an existing GL account")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_ACTIVATE", "Activate a GL account")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_DEACTIVATE", "Deactivate a GL account")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_ARCHIVE", "Archive a GL account")
                        .build(),

                // PostingRuleController - 4 events
                EventTypeRegistration.search("ACCOUNTING_POSTING_RULE_LIST", "List posting rule sets")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_POSTING_RULE_CREATE", "Create a new posting rule set")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_POSTING_RULE_PUBLISH", "Publish a posting rule set")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_POSTING_RULE_ARCHIVE", "Archive a posting rule set")
                        .build(),

                // Legacy payment/gl-account path events - 4 events
                EventTypeRegistration.approval("ACCOUNTING_PAYMENT_VOID", "Void a payment before settlement")
                        .build(),
                EventTypeRegistration.approval("ACCOUNTING_PAYMENT_REVERSE", "Reverse a previously applied payment")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_CREATE_LEGACY", "Create GL account via legacy path")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_UPDATE_LEGACY", "Update GL account via legacy path")
                        .build(),

                // EventIngestionController - 5 events (updated for CAP:055, #2436), plus
                // InvoiceRevenueReconciliationController - 1 event (ACCOUNTING_INVOICE_REVENUE_RECONCILE)
                EventTypeRegistration.search("ACCOUNTING_EVENT_LIST", "List accounting events with filters")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_EVENT_STATUS_LIST", "List accounting event statuses")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_EVENT_TYPE_LIST", "List the accounting event types the module records")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_EVENT_SUBMIT", "Submit a new accounting event for processing")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_EVENT_RETRY", "Retry processing for a failed accounting event")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_INVOICE_REVENUE_RECONCILE",
                                "Reconcile invoice revenue GL postings from the invoice replica")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_EVENT_REPROCESS",
                                "Reprocess a suspended accounting event after mapping/rule correction")
                        .build(),

                // InvoicePaymentController / PaymentApplicationController - 4 events
                EventTypeRegistration.write("ACCOUNTING_PAYMENT_APPLY", "Apply a payment to an invoice")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_PAYMENT_REMAINDER_CREDIT",
                                "Keep a payment's unapplied remainder as a customer credit")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_PAYMENT_APPLICATION_REVERSE", "Reverse a payment application")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_INVOICE_REGENERATE", "Regenerate invoice from workorder")
                        .build(),

                // AuditTrailController - 3 events
                EventTypeRegistration.approval(
                                "ACCOUNTING_AUDIT_PRICE_OVERRIDE", "Record a price override with policy validation")
                        .build(),
                EventTypeRegistration.approval("ACCOUNTING_AUDIT_REFUND", "Record a refund with policy validation")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_AUDIT_CANCELLATION", "Record an order or invoice cancellation")
                        .build(),

                // CreditMemoController - 4 events (CAP-052; +1 void, issue #997 symmetry)
                EventTypeRegistration.write(
                                "ACCOUNTING_CREDIT_MEMO_CREATE", "Create a credit memo to reverse invoice charges")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_CREDIT_MEMO_VOID",
                                "Void a posted credit memo, restoring AR and the reversed tax liability")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_CREDIT_MEMO_LIST", "List credit memos with optional filters")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_CREDIT_MEMO_GET", "Get credit memo details by ID")
                        .build(),

                // FinancialReportingController - 5 events (CAP-054, parity-G1)
                EventTypeRegistration.search(
                                "REPORT_INCOME_STATEMENT_GENERATE", "Generate income statement report for a date range")
                        .build(),
                EventTypeRegistration.search(
                                "REPORT_BALANCE_SHEET_GENERATE", "Generate balance sheet report as of a specific date")
                        .build(),
                EventTypeRegistration.search(
                                "REPORT_TRIAL_BALANCE_GENERATE",
                                "Generate trial balance report as of a specific date with entry-number gap footnote")
                        .build(),
                EventTypeRegistration.fastRead(
                                "REPORT_DRILLDOWN_ACCOUNTS",
                                "Drill down from statement line to contributing GL accounts")
                        .build(),
                EventTypeRegistration.fastRead(
                                "REPORT_DRILLDOWN_JOURNAL_LINES",
                                "Drill down from GL account to source journal entries")
                        .build(),

                // FinancialReportingController GL + aging reports - 3 events (parity-G2, Issue #960)
                EventTypeRegistration.search(
                                "REPORT_GENERAL_LEDGER_GENERATE",
                                "Generate General Ledger report (per-account POSTED lines with running balance)")
                        .build(),
                EventTypeRegistration.search(
                                "REPORT_AGED_RECEIVABLES_GENERATE",
                                "Generate Aged Receivables report (bucketed open invoice balances)")
                        .build(),
                EventTypeRegistration.search(
                                "REPORT_AGED_PAYABLES_GENERATE",
                                "Generate Aged Payables report (bucketed open vendor-bill balances)")
                        .build(),

                // FinancialReportingController sales-tax liability - 1 event (parity-T8, Issue #966)
                EventTypeRegistration.search(
                                "REPORT_TAX_LIABILITY_GENERATE",
                                "Generate Sales-Tax Liability report (per-jurisdiction taxable/exempt base, net tax,"
                                        + " GL drift)")
                        .build(),

                // TaxLiabilitySnapshotController - 4 events (Issue #998 Phase-2 item 2)
                EventTypeRegistration.write(
                                "TAX_LIABILITY_SNAPSHOT_FREEZE",
                                "Freeze the Sales-Tax Liability report for a closed accounting period")
                        .build(),
                EventTypeRegistration.search("TAX_LIABILITY_SNAPSHOT_LIST", "List frozen Sales-Tax Liability snapshots")
                        .build(),
                EventTypeRegistration.fastRead(
                                "TAX_LIABILITY_SNAPSHOT_GET", "Get a frozen Sales-Tax Liability snapshot by id")
                        .build(),
                EventTypeRegistration.search(
                                "TAX_LIABILITY_SNAPSHOT_VERIFY",
                                "Re-derive a frozen Sales-Tax Liability snapshot from live data and compare hashes")
                        .build(),

                // LaborOverheadReportController - 1 event (CAP-316)
                EventTypeRegistration.search(
                                "REPORT_LABOR_OVERHEAD_GENERATE",
                                "Generate Labor & Overhead cost report for a location and fiscal year")
                        .build(),

                // GLMappingController - 3 events (GL Mapping + parity-E3 dry-run)
                EventTypeRegistration.write(
                                "ACCOUNTING_GL_MAPPING_CREATE", "Create GL mapping from external code to GL account")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_GL_MAPPING_RESOLVE",
                                "Resolve external code to GL account using effective-dated mapping")
                        .build(),
                EventTypeRegistration.search(
                                "ACCOUNTING_MAPPING_RESOLVE_TEST",
                                "Dry-run mapping/rule resolution for a hypothetical event (no persistence)")
                        .build(),

                // VendorBillService - 8 events (CAP-053 Issue #130; VENDOR_BILL_GL_POSTING retired by #2509)
                EventTypeRegistration.write(
                                "ACCOUNTING_VENDOR_BILL_CREATE",
                                "Create vendor bill from goods received event (Receipt Accrual)")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_VENDOR_BILL_MATCH", "Three-way match vendor invoice to existing bill")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_VENDOR_BILL_MATCH_EXCEPTION_RESOLVE",
                                "Resolve vendor bill match exception (accept/correct/void)")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_VENDOR_BILL_GET", "Get vendor bill details by ID")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_VENDOR_BILL_GET_BY_EVENT",
                                "Get vendor bill by origin event ID (idempotency check)")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATES_LIST",
                                "List unresolved match candidates for ambiguous invoice match")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_VENDOR_BILL_MATCH_CANDIDATE_SELECT",
                                "Select a match candidate of an ambiguous match; the bill goes to approval")
                        .build(),
                // Vendor-bill approval lifecycle - 6 events (CAP:550 S12, Issue #2509)
                EventTypeRegistration.approval("ACCOUNTING_VENDOR_BILL_SUBMIT", "Send a vendor bill for approval")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_VENDOR_BILL_APPROVE", "Approve a vendor bill; the approval posts it")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_VENDOR_BILL_REJECT", "Reject a vendor bill awaiting approval")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_VENDOR_BILL_VOID",
                                "Void an approved vendor bill with nothing allocated; reverses its entry")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_VENDOR_BILL_STAGES_VIEW", "Count vendor bills in each Bills to pay stage")
                        .build(),
                EventTypeRegistration.search(
                                "ACCOUNTING_VENDOR_BILL_STAGE_LIST", "List the vendor bills of one Bills to pay stage")
                        .build(),
                EventTypeRegistration.search(
                                "ACCOUNTING_VENDOR_BILL_LIST_VIEW",
                                "List vendor bills due in a date window, optionally filtered by status (Wave 2 E9,"
                                        + " issue #1597)")
                        .build(),

                // AP Payment GL Posting - 1 event (Issue #128)
                EventTypeRegistration.write("AP_PAYMENT_GL_POSTING", "Post AP payment to GL (Dr AP, Cr Cash/Bank)")
                        .build(),

                // AR Payment Application GL Posting - 1 event (story C1, Issue #954)
                EventTypeRegistration.write(
                                "PAYMENT_APPLICATION_GL_POSTING",
                                "Post AR payment application to GL (Dr Undeposited Funds, Cr AR)")
                        .build(),

                // AR Payment Application Reversal GL Posting - 1 event (story C2, Issue #958)
                EventTypeRegistration.write(
                                "PAYMENT_APPLICATION_REVERSAL_GL_POSTING",
                                "Post AR payment application reversal to GL (reversing entry: Dr AR, Cr Undeposited"
                                        + " Funds)")
                        .build(),

                // AccountingStatusSyncService — 2 events (CAP-251 #5)
                EventTypeRegistration.write(
                                "ACCOUNTING_STATUS_SYNC_PROCESS",
                                "Process accounting status change event for invoice reconciliation")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_STATUS_VIEW", "View current accounting status for an invoice")
                        .build(),

                // ReportExportController — 4 events (PRD missing endpoints + issue #999 download)
                EventTypeRegistration.write("ACCOUNTING_REPORT_EXPORT_REQUEST", "Request async report export")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_REPORT_EXPORT_STATUS", "Get report export status by ID")
                        .build(),
                EventTypeRegistration.search("ACCOUNTING_REPORT_EXPORT_LIST", "List report export history")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_REPORT_EXPORT_DOWNLOAD", "Download rendered report export artifact")
                        .build(),

                // TimekeepingExportController — 1 event (Wave 4 SDK migration)
                EventTypeRegistration.write("ACCOUNTING_EXPORT_REQUEST", "Request timekeeping export job")
                        .build(),

                // VendorDirectoryController — 2 events (Issue #816)
                EventTypeRegistration.search(
                                "ACCOUNTING_VENDOR_SEARCH", "Search AP vendor directory by name (typeahead)")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_VENDOR_GET", "Get AP vendor directory entry by ID")
                        .build(),

                // AccountingPeriodController — 3 events (Story B1, Issue #937)
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_PERIOD_LIST", "List accounting periods with lifecycle status")
                        .build(),
                EventTypeRegistration.approval("ACCOUNTING_PERIOD_CLOSE", "Close an accounting period (OPEN to CLOSED)")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_PERIOD_REOPEN",
                                "Reopen a closed accounting period with mandatory justification")
                        .build(),

                // AccountingPeriodController hard lock — 2 events (Story B2, Issue #944)
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_PERIOD_HARD_LOCK_VIEW", "View the org-level accounting hard-lock date")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_PERIOD_HARD_LOCK_SET",
                                "Set the org-level accounting hard-lock date (monotonic forward,"
                                        + " mandatory justification)")
                        .build(),

                // AccountingPeriodController bank reconciliation close readiness + policy — 3 events
                // (SPEC-manual-bank-reconciliation §5.9, story S6, Issue #2305)
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_PERIOD_CLOSE_READINESS",
                                "Read the bank reconciliation close readiness of an accounting period")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_PERIOD_BANK_REC_POLICY_VIEW",
                                "View the tenant's bank reconciliation close policy")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_PERIOD_BANK_REC_POLICY_SET",
                                "Replace the tenant's bank reconciliation close policy (mandatory justification)")
                        .build(),

                // AccountingConfigurationController — 1 event (Issue #2558)
                EventTypeRegistration.approval(
                                "ACCOUNTING_CONFIGURATION_TIME_ZONE_SET",
                                "Set the tenant's accounting-calendar time zone (before the first period close)")
                        .build(),

                // SettlementReconciliationController — 3 events (Story F1c, Issue #963)
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_SETTLEMENT_LINES_LIST",
                                "List processor settlement lines for reconciliation review")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_SETTLEMENT_LINE_MATCH",
                                "Manually match an unmatched settlement line to a receivable payment")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_SETTLEMENT_LINE_WRITE_OFF",
                                "Write off a small unmatched settlement line (reversible JE,"
                                        + " threshold-gated, mandatory reason)")
                        .build(),

                // BankReconciliationController — 9 events (Story F2, Issue #965; the F2 import,
                // ACCOUNTING_RECONCILIATION_IMPORT, is retired with its endpoint, D14, #2302)
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_MATCH",
                                "Create a match of bank transactions and posted ledger lines (1:1, 1:N, N:1)")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_UNMATCH", "Unmatch an accepted match with a reason")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_RECONCILIATION_ADJUSTMENT",
                                "Record a reconciliation adjustment (posts a real balanced JE)")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_RECONCILIATION_FINALIZE",
                                "Approve a submitted reconciliation behind the gate E4 (SUBMITTED to FINALIZED)")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_RECONCILIATION_LIST",
                                "List reconciliations with optional glAccountId/status filters")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_RECONCILIATION_GET",
                                "Get one reconciliation with its lines and adjustments")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_RECONCILIATION_REPORT",
                                "Reconciliation report: balances, matched vs outstanding, adjustments, difference")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_RECONCILIATION_AUDIT", "Audit trail of a reconciliation's actions")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_RECONCILIATION_ADJUSTMENT_TYPES_LIST",
                                "List the supported reconciliation adjustment types (decision D-6)")
                        .build(),

                // Reconciliation core — 11 events (SPEC-manual-bank-reconciliation §3.10, story S4, issue #2303)
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_CREATE",
                                "Start a reconciliation from a COMMITTED bank statement")
                        .build(),
                EventTypeRegistration.search(
                                "ACCOUNTING_RECONCILIATION_CANDIDATES",
                                "Rank match candidates for a bank transaction or a ledger line")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_AUTO_MATCH",
                                "Propose one-to-one matches by rule (never accepted by the system)")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_MATCH_ACCEPT", "Accept a proposed reconciliation match")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_MATCH_REJECT", "Reject a proposed reconciliation match")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_OUTSTANDING_REGISTER",
                                "Register a non-posting outstanding (timing) item")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_OUTSTANDING_RELEASE",
                                "Release an outstanding item, with reason")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_OUTSTANDING_REAFFIRM",
                                "Reaffirm an aged OTHER_LEDGER_TIMING item in this window")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_RECONCILIATION_OUTSTANDING_CLEAR_IN_GAP",
                                "Close an outstanding item that cleared during an acknowledged gap")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_RECONCILIATION_ADJUSTMENT_REVERSE",
                                "Reverse a reconciliation adjustment (posts the reversal JE)")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_RECONCILIATION_REVIEW",
                                "Read the reconciliation review: equation, diagnostics, unresolved, evidence")
                        .build(),

                // Reconciliation approval workflow — 4 events (SPEC-manual-bank-reconciliation §3.10, story S5,
                // issue #2304); ACCOUNTING_RECONCILIATION_FINALIZE is now the approve step
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_SUBMIT",
                                "Submit a reconciliation for approval behind the gate E4")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_RECONCILIATION_RETURN",
                                "Return a submitted reconciliation to its preparer, with a reason")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_RECONCILIATION_SUPERSEDE",
                                "Start a reconciliation superseding a FINALIZED or INVALIDATED one")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_RECONCILIATION_CANCEL",
                                "Cancel an IN_PROGRESS or SUBMITTED reconciliation, with a justification")
                        .build(),

                // Bank statements, transactions and accounts — 10 events (SPEC-manual-bank-reconciliation
                // §3.10, story S2, issue #2301)
                EventTypeRegistration.write(
                                "ACCOUNTING_BANK_STATEMENT_CREATE",
                                "Commit a bank statement entered by hand through the intake port")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_BANK_STATEMENT_LIST", "List bank statements by account and window")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_BANK_STATEMENT_GET",
                                "Get one bank statement with its counts and reconciliation links")
                        .build(),
                EventTypeRegistration.search(
                                "ACCOUNTING_BANK_TRANSACTION_LIST",
                                "List an account's bank transactions by status, window and source")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_BANK_TRANSACTION_GET", "Get one bank transaction with its provenance")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_BANK_TRANSACTION_DUPLICATE_REVIEW",
                                "Review a possible duplicate bank transaction (distinct or duplicate)")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_BANK_TRANSACTION_EXCLUDE",
                                "Exclude an unmatched bank transaction, justified")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_BANK_TRANSACTION_RESTORE",
                                "Restore an excluded bank transaction, justified")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_BANK_ACCOUNT_LIST",
                                "List bank accounts with profile, baseline, frontiers and unexplained counts")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_BANK_ACCOUNT_PROFILE_SET", "Create or update a bank-account profile")
                        .build(),

                // Statement-file imports — 9 events (SPEC-manual-bank-reconciliation §3.10, story S3, issue #2302)
                EventTypeRegistration.write(
                                "ACCOUNTING_BANK_IMPORT_CREATE",
                                "Upload a bank statement file and stage its parsed rows for review")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_BANK_IMPORT_LIST", "List statement-file imports")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_BANK_IMPORT_GET", "Get one statement-file import with its preview")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_BANK_IMPORT_ROWS", "List a statement-file import's rows by row number")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_BANK_IMPORT_MAPPING_SET",
                                "Set an import's column mapping and options and re-parse every row")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_BANK_IMPORT_ROW_CORRECT",
                                "Correct, skip or decide one row of a statement-file import")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_BANK_IMPORT_COMMIT",
                                "Commit a statement-file import as a statement through the intake port")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_BANK_IMPORT_DISCARD", "Discard a statement-file import, with a reason")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_BANK_IMPORT_FILE_READ",
                                "Download the retained raw file of an import (audited)")
                        .build(),

                // Customer credit lifecycle (issue #992) - 4 events
                EventTypeRegistration.search(
                                "ACCOUNTING_CUSTOMER_CREDIT_LIST",
                                "List AR customer credits with their remaining open amounts")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_CUSTOMER_CREDIT_GET", "Get one AR customer credit and its open amount")
                        .build(),
                EventTypeRegistration.write(
                                "ACCOUNTING_CUSTOMER_CREDIT_APPLY",
                                "Apply an open customer credit to an invoice (Dr credit liability / Cr AR)")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_CUSTOMER_CREDIT_REFUND",
                                "Refund an open customer credit to the customer (Dr credit liability / Cr cash)")
                        .build(),

                // AccountingAnalyticsController — 2 events (Wave 2, Issues #1590 E2 / #1591 E3)
                EventTypeRegistration.search(
                                "ACCOUNTING_ANALYTICS_COLLECTIONS_VIEW",
                                "View invoiced-vs-collected analytics for a date window")
                        .build(),
                EventTypeRegistration.search(
                                "ACCOUNTING_ANALYTICS_PAYMENT_LAG_COHORTS_VIEW",
                                "View payment-lag cohorts (<=30/31-60/61-90/unpaid) for invoices issued in a"
                                        + " date window")
                        .build(),
                EventTypeRegistration.search(
                                "ACCOUNTING_ANALYTICS_VENDOR_SPEND_VIEW",
                                "View per-vendor spend analytics (settled A/P cash and bill count/average) for a"
                                        + " date window (Wave 2 E8, issue #1596)")
                        .build(),

                // PaymentApplicationController list route — 1 event (Wave 2, Issue #1598 E10)
                EventTypeRegistration.search(
                                "ACCOUNTING_PAYMENT_APPLICATION_LIST_VIEW",
                                "List pos-accounting cash applications of customer payments to invoices in an"
                                        + " applied-date window")
                        .build(),

                // ReceivablePaymentController / CustomerReceivablesController — 2 events (CAP:550 S1, #2502)
                EventTypeRegistration.search(
                                "ACCOUNTING_RECEIVABLE_PAYMENT_LIST_VIEW",
                                "List customer payments waiting to be matched, with match suggestions")
                        .build(),
                EventTypeRegistration.search(
                                "ACCOUNTING_CUSTOMER_OPEN_INVOICES_VIEW",
                                "List a customer's open invoices with the derived balance due")
                        .build(),

                // AutomaticPaymentApplicationController — 1 event (CAP:550 S2, #2503)
                EventTypeRegistration.search(
                                "ACCOUNTING_PAYMENT_APPLICATION_AUTOMATIC_LIST_VIEW",
                                "List payment applications made automatically, with Undo")
                        .build(),

                // UnpaidWalkInSalesController — 1 event (CAP:550 S11, #2508)
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_UNPAID_WALK_IN_SALES_VIEW",
                                "Read the CASH walk-in balance and the day-end needs-attention item")
                        .build());
    }
}
