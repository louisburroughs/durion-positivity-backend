package com.positivity.invoice.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types for the Invoice module.
 * CAP:092 - Preferences & Billing Rules
 * 
 * Event naming convention: BILLING_{RESOURCE}_{ACTION}
 */
public final class InvoiceEventTypes {

        private InvoiceEventTypes() {
                // Utility class
        }

        // ==================== BILLING RULES EVENTS ====================

        /** Get billing rules for a party */
        public static final EventTypeRegistration BILLING_RULES_GET = EventTypeRegistration
                        .fastRead("BILLING_RULES_GET",
                                        "Retrieve billing rules for a party/customer")
                        .apiVersion("1")
                        .build();

        /** Upsert billing rules */
        public static final EventTypeRegistration BILLING_RULES_UPSERT = EventTypeRegistration
                        .write("BILLING_RULES_UPSERT",
                                        "Create or update billing rules for a party/customer")
                        .apiVersion("1")
                        .build();

        // ==================== INVOICE EVENTS ====================

        public static final EventTypeRegistration INVOICE_CREATE = EventTypeRegistration.write("INVOICE_CREATE",
                        "Create invoice draft from workorder")
                        .apiVersion("1")
                        .build();

        public static final EventTypeRegistration INVOICE_GET = EventTypeRegistration.fastRead("INVOICE_GET",
                        "Fetch invoice details")
                        .apiVersion("1")
                        .build();

        public static final EventTypeRegistration INVOICE_ADJUSTMENT_APPLY = EventTypeRegistration
                        .write("INVOICE_ADJUSTMENT_APPLY",
                                        "Apply adjustment to draft invoice")
                        .apiVersion("1")
                        .build();

        // ==================== STORY #13 — FINALIZATION EVENTS ====================

        /** AC3/AC4: Manager approval requested before finalization */
        public static final EventTypeRegistration INVOICE_FINALIZATION_REQUESTED = EventTypeRegistration
                        .approval("INVOICE_FINALIZATION_REQUESTED",
                                        "Manager approval requested for invoice finalization")
                        .apiVersion("1")
                        .build();

        /** AC4: Invoice successfully finalized — triggers async GL posting */
        public static final EventTypeRegistration INVOICE_FINALIZED = EventTypeRegistration
                        .write("INVOICE_FINALIZED",
                                        "Invoice finalized and InvoiceFinalized event emitted for GL posting")
                        .apiVersion("1")
                        .build();

        /** AC5: Invoice posted to General Ledger successfully */
        public static final EventTypeRegistration INVOICE_POSTED_TO_GL = EventTypeRegistration
                        .write("INVOICE_POSTED_TO_GL",
                                        "Invoice posted to General Ledger with GL entry ID")
                        .apiVersion("1")
                        .build();

        /** AC5: GL posting failed; invoice marked ERROR for retry */
        public static final EventTypeRegistration INVOICE_POSTING_FAILED = EventTypeRegistration
                        .write("INVOICE_POSTING_FAILED",
                                        "Invoice GL posting failed; status set to ERROR for retry")
                        .apiVersion("1")
                        .build();

        /** AC6: Invoice successfully reverted from FINALIZED back to DRAFT */
        public static final EventTypeRegistration INVOICE_DRAFT_REVERT = EventTypeRegistration
                        .approval("INVOICE_DRAFT_REVERT",
                                        "Revert finalized invoice to draft (Story #13, AC6)")
                        .apiVersion("1")
                        .build();

        // ==================== STORY #9 — PAYMENT EVENTS ====================

        public static final EventTypeRegistration INVOICE_PAYMENT_INITIATE = EventTypeRegistration
                        .write("INVOICE_PAYMENT_INITIATE",
                                        "Initiate card authorization or sale-capture for invoice payment")
                        .apiVersion("1")
                        .build();

        public static final EventTypeRegistration INVOICE_PAYMENT_CAPTURE = EventTypeRegistration
                        .write("INVOICE_PAYMENT_CAPTURE", "Explicit manual capture of an authorized payment hold")
                        .apiVersion("1")
                        .build();

        // ==================== STORY #8 — VOID/REFUND EVENTS ====================

        public static final EventTypeRegistration INVOICE_PAYMENT_VOID = EventTypeRegistration
                        .write("INVOICE_PAYMENT_VOID", "Void an authorized payment for an invoice")
                        .apiVersion("1")
                        .build();

        public static final EventTypeRegistration INVOICE_PAYMENT_REFUND = EventTypeRegistration
                        .write("INVOICE_PAYMENT_REFUND", "Refund a captured payment for an invoice")
                        .apiVersion("1")
                        .build();

        // ==================== STORY #7 — RECEIPT EVENTS ====================

        public static final EventTypeRegistration INVOICE_RECEIPT_GENERATE = EventTypeRegistration
                        .write("INVOICE_RECEIPT_GENERATE",
                                        "Generate a payment receipt for an invoice")
                        .apiVersion("1").build();

        public static final EventTypeRegistration INVOICE_RECEIPT_REPRINT = EventTypeRegistration
                        .write("INVOICE_RECEIPT_REPRINT",
                                        "Reprint an existing invoice receipt")
                        .apiVersion("1").build();

        public static final EventTypeRegistration INVOICE_RECEIPT_PRINT_DELIVERY = EventTypeRegistration
                        .write("INVOICE_RECEIPT_PRINT_DELIVERY",
                                        "Record print delivery for a receipt")
                        .apiVersion("1").build();

        public static final EventTypeRegistration INVOICE_RECEIPT_EMAIL_DELIVERY = EventTypeRegistration
                        .write("INVOICE_RECEIPT_EMAIL_DELIVERY",
                                        "Record email delivery for a receipt")
                        .apiVersion("1").build();

        /**
         * Returns all event type registrations for the Invoice module.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                BILLING_RULES_GET,
                                BILLING_RULES_UPSERT,
                                INVOICE_CREATE,
                                INVOICE_GET,
                                INVOICE_ADJUSTMENT_APPLY,
                                INVOICE_FINALIZATION_REQUESTED,
                                INVOICE_FINALIZED,
                                INVOICE_POSTED_TO_GL,
                                INVOICE_POSTING_FAILED,
                                INVOICE_DRAFT_REVERT,
                                INVOICE_PAYMENT_INITIATE,
                                INVOICE_PAYMENT_CAPTURE,
                                INVOICE_PAYMENT_VOID,
                                INVOICE_PAYMENT_REFUND,
                                INVOICE_RECEIPT_GENERATE,
                                INVOICE_RECEIPT_REPRINT,
                                INVOICE_RECEIPT_PRINT_DELIVERY,
                                INVOICE_RECEIPT_EMAIL_DELIVERY);
        }
}
