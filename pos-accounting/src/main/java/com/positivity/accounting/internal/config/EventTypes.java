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
     * Total: 68 event types (includes +3 from the GL + aged AR/AP reports
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

                // EventIngestionController - 4 events (updated for CAP:055)
                EventTypeRegistration.search("ACCOUNTING_EVENT_LIST", "List accounting events with filters")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_EVENT_SUBMIT", "Submit a new accounting event for processing")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_EVENT_RETRY", "Retry processing for a failed accounting event")
                        .build(),
                EventTypeRegistration.approval(
                                "ACCOUNTING_EVENT_REPROCESS",
                                "Reprocess a suspended accounting event after mapping/rule correction")
                        .build(),

                // InvoicePaymentController / PaymentApplicationController - 3 events
                EventTypeRegistration.write("ACCOUNTING_PAYMENT_APPLY", "Apply a payment to an invoice")
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

                // CreditMemoController - 3 events (CAP-052)
                EventTypeRegistration.write(
                                "ACCOUNTING_CREDIT_MEMO_CREATE", "Create a credit memo to reverse invoice charges")
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

                // VendorBillService - 6 events (CAP-053 Issue #130)
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
                EventTypeRegistration.write(
                                "VENDOR_BILL_GL_POSTING", "Post vendor bill to GL (Dr Inventory/Expense, Cr AP)")
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
                                "Select match candidate to approve vendor bill from ambiguous match")
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

                // ReportExportController — 3 events (PRD missing endpoints)
                EventTypeRegistration.write("ACCOUNTING_REPORT_EXPORT_REQUEST", "Request async report export")
                        .build(),
                EventTypeRegistration.fastRead("ACCOUNTING_REPORT_EXPORT_STATUS", "Get report export status by ID")
                        .build(),
                EventTypeRegistration.search("ACCOUNTING_REPORT_EXPORT_LIST", "List report export history")
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
                        .build());
    }
}
