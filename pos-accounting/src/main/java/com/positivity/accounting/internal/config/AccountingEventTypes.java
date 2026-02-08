package com.positivity.accounting.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-accounting module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class AccountingEventTypes {

        private AccountingEventTypes() {
                // Utility class
        }

        /**
         * All event type registrations for the accounting module.
         * Total: 22 event types.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                // JournalEntryController - 3 events
                                EventTypeRegistration.search("ACCOUNTING_JOURNAL_ENTRY_LIST",
                                                "List journal entries with optional filters").build(),
                                EventTypeRegistration.write("ACCOUNTING_JOURNAL_ENTRY_CREATE",
                                                "Create a new journal entry").build(),
                                EventTypeRegistration.write("ACCOUNTING_JOURNAL_ENTRY_UPDATE",
                                                "Update an existing journal entry").build(),

                                // GLAccountController - 6 events
                                EventTypeRegistration.search("ACCOUNTING_GL_ACCOUNT_LIST",
                                                "List GL accounts with pagination").build(),
                                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_CREATE",
                                                "Create a new GL account").build(),
                                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_UPDATE",
                                                "Update an existing GL account").build(),
                                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_ACTIVATE",
                                                "Activate a GL account").build(),
                                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_DEACTIVATE",
                                                "Deactivate a GL account").build(),
                                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_ARCHIVE",
                                                "Archive a GL account").build(),

                                // PostingRuleController - 4 events
                                EventTypeRegistration.search("ACCOUNTING_POSTING_RULE_LIST",
                                                "List posting rule sets").build(),
                                EventTypeRegistration.write("ACCOUNTING_POSTING_RULE_CREATE",
                                                "Create a new posting rule set").build(),
                                EventTypeRegistration.write("ACCOUNTING_POSTING_RULE_PUBLISH",
                                                "Publish a posting rule set").build(),
                                EventTypeRegistration.write("ACCOUNTING_POSTING_RULE_ARCHIVE",
                                                "Archive a posting rule set").build(),

                                // AccountingController - 4 events (legacy paths)
                                EventTypeRegistration.approval("ACCOUNTING_PAYMENT_VOID",
                                                "Void a payment before settlement").build(),
                                EventTypeRegistration.approval("ACCOUNTING_PAYMENT_REVERSE",
                                                "Reverse a previously applied payment").build(),
                                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_CREATE_LEGACY",
                                                "Create GL account via legacy path").build(),
                                EventTypeRegistration.write("ACCOUNTING_GL_ACCOUNT_UPDATE_LEGACY",
                                                "Update GL account via legacy path").build(),

                                // EventIngestionController - 3 events
                                EventTypeRegistration.search("ACCOUNTING_EVENT_LIST",
                                                "List accounting events with filters").build(),
                                EventTypeRegistration.write("ACCOUNTING_EVENT_SUBMIT",
                                                "Submit a new accounting event for processing").build(),
                                EventTypeRegistration.write("ACCOUNTING_EVENT_RETRY",
                                                "Retry processing for a failed accounting event").build(),

                                // InvoicePaymentController / PaymentApplicationController - 3 events
                                EventTypeRegistration.write("ACCOUNTING_PAYMENT_APPLY",
                                                "Apply a payment to an invoice").build(),
                                EventTypeRegistration.write("ACCOUNTING_PAYMENT_APPLICATION_REVERSE",
                                                "Reverse a payment application").build(),
                                EventTypeRegistration.write("ACCOUNTING_INVOICE_REGENERATE",
                                                "Regenerate invoice from workorder").build(),

                                // AuditTrailController - 3 events
                                EventTypeRegistration.approval("ACCOUNTING_AUDIT_PRICE_OVERRIDE",
                                                "Record a price override with policy validation").build(),
                                EventTypeRegistration.approval("ACCOUNTING_AUDIT_REFUND",
                                                "Record a refund with policy validation").build(),
                                EventTypeRegistration.write("ACCOUNTING_AUDIT_CANCELLATION",
                                                "Record an order or invoice cancellation").build());
        }
}
