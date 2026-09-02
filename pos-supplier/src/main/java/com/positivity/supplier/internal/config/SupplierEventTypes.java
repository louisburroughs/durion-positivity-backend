package com.positivity.supplier.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of every {@code @EmitEvent} id used by the supplier module (AGENTS.md mandatory
 * pattern; durion-positivity-backend#1222). Every id passed to {@code @EmitEvent} anywhere in
 * this module MUST appear here so it is registered with pos-event-receiver at startup. Event
 * payload identity uses {@code vendorProfileId} — never {@code supplierRef}.
 */
public final class SupplierEventTypes {

    private SupplierEventTypes() {}

    public static List<EventTypeRegistration> all() {
        return List.of(
                // Vendor profiles (ADR-0050 §1/§2)
                EventTypeRegistration.fastRead("SUPPLIER_PROFILE_LIST", "List vendor profiles")
                        .build(),
                EventTypeRegistration.fastRead("SUPPLIER_PROFILE_GET", "Get a vendor profile")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_PROFILE_CREATE", "Create a vendor profile")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_PROFILE_UPDATE", "Update a vendor profile")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_PROFILE_DELETE", "Delete a vendor profile")
                        .build(),
                // Auth configs (ADR-0050 §4)
                EventTypeRegistration.fastRead("SUPPLIER_AUTHCONFIG_LIST", "List vendor auth configs")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_AUTHCONFIG_CREATE", "Create a vendor auth config")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_AUTHCONFIG_UPDATE", "Update a vendor auth config")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_AUTHCONFIG_DELETE", "Delete a vendor auth config")
                        .build(),
                // Commercial accounts (ADR-0050 §5)
                EventTypeRegistration.fastRead("SUPPLIER_ACCOUNT_LIST", "List vendor commercial accounts")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_ACCOUNT_CREATE", "Create a vendor commercial account")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_ACCOUNT_UPDATE", "Update a vendor commercial account")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_ACCOUNT_DELETE", "Delete a vendor commercial account")
                        .build(),
                // Endpoint bindings (ADR-0050 §3)
                EventTypeRegistration.fastRead("SUPPLIER_BINDING_LIST", "List capability endpoint bindings")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_BINDING_CREATE", "Create a capability endpoint binding")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_BINDING_UPDATE", "Update a capability endpoint binding")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_BINDING_DELETE", "Delete a capability endpoint binding")
                        .build(),
                // Exchange audit (ADR-0050 §7)
                EventTypeRegistration.search("SUPPLIER_AUDIT_EXCHANGE_LIST", "List supplier exchanges in a window")
                        .build(),
                EventTypeRegistration.search(
                                "SUPPLIER_AUDIT_EXCHANGE_TRACE", "Trace one logical call's attempts by correlation id")
                        .build(),
                EventTypeRegistration.fastRead("SUPPLIER_AUDIT_EXCHANGE_GET", "Get one exchange's audit metadata")
                        .build(),
                // A write budget, not a read one, and not by accident: serving payload content also
                // inserts the ADR-0050 §7 access record, so this operation's latency is a write's.
                EventTypeRegistration.write(
                                "SUPPLIER_AUDIT_PAYLOAD_READ",
                                "Read one exchange's stored payload content; writes an access record")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SUPPLIER_AUDIT_ACCESS_LIST", "List who read one exchange's payload content")
                        .build(),
                // Price catalog (ADR-0053). The trigger is registered as an approval-grade budget
                // rather than a write: it fetches a whole vendor catalog and stages every line, so a
                // write threshold would alert on every healthy large import.
                EventTypeRegistration.approval(
                                "SUPPLIER_PRICECATALOG_IMPORT_TRIGGER",
                                "Trigger an on-demand vendor price-catalog (PRICAT) import")
                        .build(),
                EventTypeRegistration.search(
                                "SUPPLIER_PRICECATALOG_IMPORT_LIST", "List vendor price-catalog import runs")
                        .build(),
                EventTypeRegistration.search(
                                "SUPPLIER_PRICECATALOG_UNMATCHED_LIST",
                                "List price-catalog lines quarantined for catalog review")
                        .build(),
                // Re-application stages and publishes a batch of lines, so it carries a write's
                // latency rather than a read's.
                EventTypeRegistration.write(
                                "SUPPLIER_PRICECATALOG_REAPPLY",
                                "Re-apply quarantined price-catalog lines after a catalog fix")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SUPPLIER_PRICECATALOG_FRESHNESS_GET",
                                "Read one vendor price-catalog's freshness and staleness verdict")
                        .build(),
                // Order transmission ledger (ADR-0052, CAP-320)
                EventTypeRegistration.search(
                                "SUPPLIER_TRANSMISSION_LIST", "List the vendor transmissions of one purchase order")
                        .build(),
                EventTypeRegistration.search(
                                "SUPPLIER_TRANSMISSION_SEARCH",
                                "Search the transmission ledger across purchase orders (manual-review worklist)")
                        .build(),
                EventTypeRegistration.fastRead("SUPPLIER_TRANSMISSION_GET", "Get one vendor transmission")
                        .build(),
                // Approval-grade rather than write, and not for latency reasons: this is an operator
                // asserting whether a vendor holds a real purchase order, which is the most
                // consequential single action in this module (ADR-0052 §4).
                EventTypeRegistration.approval(
                                "SUPPLIER_TRANSMISSION_RESOLVE",
                                "Resolve a purchase-order transmission awaiting manual review")
                        .build(),
                // Live stock inquiry (CAP-319). Threshold is a write's, not a read's: this makes a
                // synchronous call to a trading partner while a customer waits, so the latency worth
                // alerting on is the vendor's, not this module's.
                EventTypeRegistration.write("SUPPLIER_STOCK_INQUIRY", "Ask a vendor for live stock availability")
                        .build(),
                // Stock-report snapshots (CAP-322). Local reads of already-fetched documents — no
                // vendor is contacted, so a fast read's threshold is the right one.
                EventTypeRegistration.fastRead(
                                "SUPPLIER_STOCK_SNAPSHOT_GET", "Get a vendor profile's latest stock-snapshot metadata")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SUPPLIER_STOCK_SNAPSHOT_LINES_LIST", "List the lines of one stock snapshot")
                        .build(),
                EventTypeRegistration.write(
                                "SUPPLIER_INVOICE_FETCH", "Run a vendor invoice fetch for an explicit window")
                        .build(),
                // Fleet workorder authorization (CAP-323). Approval-grade rather than write: this
                // asks a trading partner for a commercial decision, and asking twice can open two
                // authorizations against one contract — so a duplicate here costs a phone call to a
                // fleet manager, not a retry.
                EventTypeRegistration.approval(
                                "SUPPLIER_WORKORDER_AUTHORIZATION_REQUEST",
                                "Ask a fleet program to authorize work on a workorder")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SUPPLIER_WORKORDER_AUTHORIZATION_READ",
                                "Read the fleet authorization recorded for a workorder")
                        .build(),
                // The lookups reach a vendor synchronously while a service advisor waits, so their
                // threshold is the vendor's latency, as with the stock inquiry above.
                EventTypeRegistration.write("SUPPLIER_FLEET_VEHICLE_LOOKUP", "Look up a vehicle in a fleet program")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_FLEET_CONTRACT_LIST", "List a vendor's fleet contracts")
                        .build(),
                EventTypeRegistration.write("SUPPLIER_FLEET_POLICY_LIST", "List a fleet program's policies")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SUPPLIER_WORKORDER_AUTHORIZATION_REVIEW_LIST",
                                "List fleet authorizations and completions waiting on a person")
                        .build(),
                // Marketing catalogue (CAP-324). A full catalogue read, not a local query: the
                // threshold that matters is the standards body's, and it serves every consumer.
                EventTypeRegistration.write("SUPPLIER_MKTCAT_IMPORT", "Run a marketing-catalogue import on demand")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SUPPLIER_MKTCAT_VARIANT_LIST", "List staged marketing-catalogue enrichment")
                        .build());
    }
}
