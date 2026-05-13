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
     * Total: 50 event types (includes +2 from CAP-251 #5:
     * ACCOUNTING_STATUS_SYNC_PROCESS and ACCOUNTING_STATUS_VIEW, in addition to
     * CAP-053 Vendor Bill workflow + GL Mapping, and +3 from PRD missing endpoints:
     * ACCOUNTING_REPORT_EXPORT_REQUEST, ACCOUNTING_REPORT_EXPORT_STATUS,
     * ACCOUNTING_REPORT_EXPORT_LIST).
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // JournalEntryController - 3 events
                EventTypeRegistration.search(
                                "ACCOUNTING_JOURNAL_ENTRY_LIST", "List journal entries with optional filters")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_JOURNAL_ENTRY_CREATE", "Create a new journal entry")
                        .build(),
                EventTypeRegistration.write("ACCOUNTING_JOURNAL_ENTRY_UPDATE", "Update an existing journal entry")
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

                // FinancialReportingController - 4 events (CAP-054)
                EventTypeRegistration.search(
                                "REPORT_INCOME_STATEMENT_GENERATE", "Generate income statement report for a date range")
                        .build(),
                EventTypeRegistration.search(
                                "REPORT_BALANCE_SHEET_GENERATE", "Generate balance sheet report as of a specific date")
                        .build(),
                EventTypeRegistration.fastRead(
                                "REPORT_DRILLDOWN_ACCOUNTS",
                                "Drill down from statement line to contributing GL accounts")
                        .build(),
                EventTypeRegistration.fastRead(
                                "REPORT_DRILLDOWN_JOURNAL_LINES",
                                "Drill down from GL account to source journal entries")
                        .build(),

                // GLMappingController - 2 events (GL Mapping)
                EventTypeRegistration.write(
                                "ACCOUNTING_GL_MAPPING_CREATE", "Create GL mapping from external code to GL account")
                        .build(),
                EventTypeRegistration.fastRead(
                                "ACCOUNTING_GL_MAPPING_RESOLVE",
                                "Resolve external code to GL account using effective-dated mapping")
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
                        .build());
    }
}
