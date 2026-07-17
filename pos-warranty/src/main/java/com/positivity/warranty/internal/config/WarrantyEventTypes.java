package com.positivity.warranty.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of every {@code @EmitEvent} id used by the warranty module
 * (PRD-warranty-claims-module.md §9.2). Every id passed to {@code @EmitEvent}
 * anywhere in this module MUST appear here so it is registered with
 * pos-event-receiver at startup.
 */
public final class WarrantyEventTypes {

    private WarrantyEventTypes() {}

    public static List<EventTypeRegistration> all() {
        return List.of(
                // Providers
                EventTypeRegistration.write("WARRANTY_PROVIDER_CREATE", "Create a warranty provider")
                        .build(),
                EventTypeRegistration.write("WARRANTY_PROVIDER_UPDATE", "Update a warranty provider")
                        .build(),
                // Policies
                EventTypeRegistration.write("WARRANTY_POLICY_CREATE", "Create a warranty policy")
                        .build(),
                EventTypeRegistration.write("WARRANTY_POLICY_UPDATE", "Update a warranty policy")
                        .build(),
                // Registrations (sold coverage)
                EventTypeRegistration.write("WARRANTY_REGISTRATION_CREATE", "Create a warranty registration")
                        .build(),
                EventTypeRegistration.write("WARRANTY_REGISTRATION_UPDATE", "Update a warranty registration")
                        .build(),
                // Claims
                EventTypeRegistration.write("WARRANTY_CLAIM_CREATE", "Create a draft warranty claim")
                        .build(),
                EventTypeRegistration.write("WARRANTY_CLAIM_UPDATE", "Update a warranty claim")
                        .build(),
                EventTypeRegistration.write("WARRANTY_CLAIM_SUBMIT", "Submit a warranty claim for decision")
                        .build(),
                EventTypeRegistration.write(
                                "WARRANTY_CLAIM_DECIDE", "Approve, deny, or request info on a warranty claim")
                        .build(),
                EventTypeRegistration.write("WARRANTY_CLAIM_SETTLE", "Execute a warranty claim settlement")
                        .build(),
                EventTypeRegistration.write("WARRANTY_CLAIM_CANCEL", "Cancel a warranty claim")
                        .build(),
                EventTypeRegistration.write("WARRANTY_CLAIM_CLOSE", "Close a warranty claim")
                        .build(),
                // Vendor reimbursement lifecycle
                EventTypeRegistration.write("WARRANTY_REIMBURSEMENT_SUBMIT", "Record vendor reimbursement submission")
                        .build(),
                EventTypeRegistration.write("WARRANTY_REIMBURSEMENT_UPDATE", "Update vendor reimbursement status")
                        .build(),
                // Part return (RMA)
                EventTypeRegistration.write("WARRANTY_PART_RETURN_CREATE", "Create a warranty part return (RMA)")
                        .build(),
                EventTypeRegistration.write("WARRANTY_PART_RETURN_UPDATE", "Update a warranty part return (RMA)")
                        .build(),
                // Searches
                EventTypeRegistration.search("WARRANTY_CLAIM_SEARCH", "Search warranty claims")
                        .build(),
                EventTypeRegistration.search(
                                "WARRANTY_CANDIDATE_LINE_SEARCH",
                                "Search invoices and workorders for claim origin candidate lines")
                        .build(),
                EventTypeRegistration.search(
                                "WARRANTY_SETTLEMENT_RECONCILIATION",
                                "Reconcile FAILED settlements against pos-invoice (double-pay worklist)")
                        .build());
    }
}
