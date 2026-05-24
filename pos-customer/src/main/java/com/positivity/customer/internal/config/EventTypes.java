package com.positivity.customer.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-customer module.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the customer module.
     * Total: 36 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // CustomerController - 4 events
                EventTypeRegistration.write("CUSTOMER_CUSTOMER_CREATE", "Create a new customer")
                        .build(),
                EventTypeRegistration.write("CUSTOMER_CUSTOMER_UPDATE", "Update an existing customer")
                        .build(),
                EventTypeRegistration.write("CUSTOMER_CUSTOMER_DELETE", "Delete an existing customer")
                        .build(),
                EventTypeRegistration.write("CUSTOMER_BULK_INGEST", "Bulk import customer records")
                        .build(),

                // CrmAccountsController - 11 events
                EventTypeRegistration.fastRead(
                                "CUSTOMER_ACCOUNT_TIER_GET", "Retrieve the tier level for a specific account")
                        .build(),
                EventTypeRegistration.search(
                                "CUSTOMER_ACCOUNT_TIER_RESOLVE",
                                "Resolve or compute the account tier based on business rules")
                        .build(),
                EventTypeRegistration.write("CUSTOMER_PARTY_CREATE", "Create a new commercial party/account")
                        .build(),
                EventTypeRegistration.fastRead("CUSTOMER_PARTY_BROWSE", "Browse parties with paging and sorting")
                        .build(),
                EventTypeRegistration.search("CUSTOMER_PARTY_SEARCH", "Search for parties based on various criteria")
                        .build(),
                EventTypeRegistration.approval(
                                "CUSTOMER_PARTY_MERGE", "Merge multiple parties into a single party record")
                        .build(),
                EventTypeRegistration.write(
                                "CUSTOMER_CONTACT_ROLE_UPDATE", "Assign or update role assignments for a contact")
                        .build(),
                EventTypeRegistration.write(
                                "CUSTOMER_COMMUNICATION_PREFERENCE_UPSERT",
                                "Set or update communication preferences for a party")
                        .build(),
                EventTypeRegistration.write("CUSTOMER_VEHICLE_CREATE", "Associate a new vehicle with a party")
                        .build(),
                EventTypeRegistration.search(
                                "CUSTOMER_PARTY_DUPLICATE_CHECK", "Check for potential duplicate commercial parties")
                        .build(),
                EventTypeRegistration.write(
                                "CUSTOMER_BILLING_RULES_UPSERT", "Upsert billing rules for a commercial party")
                        .build(),

                // CrmVehiclesController - 4 events
                EventTypeRegistration.write(
                                "CUSTOMER_VEHICLE_CREATE_LEGACY", "Create vehicle for customer (legacy path)")
                        .build(),
                EventTypeRegistration.write("CUSTOMER_VEHICLE_UPDATE", "Update vehicle information")
                        .build(),
                EventTypeRegistration.write("CUSTOMER_VEHICLE_DELETE", "Delete or deactivate vehicle association")
                        .build(),
                EventTypeRegistration.write("CUSTOMER_VEHICLE_TRANSFER", "Transfer vehicle ownership between customers")
                        .build(),

                // CrmContactsController - 1 event (legacy path)
                EventTypeRegistration.write("CUSTOMER_CONTACT_ROLE_UPDATE_LEGACY", "Update contact roles (legacy path)")
                        .build(),

                // CrmCommunicationPreferencesController - 1 event (legacy path)
                EventTypeRegistration.write(
                                "CUSTOMER_COMMUNICATION_PREFERENCE_UPSERT_LEGACY",
                                "Set or update communication preferences (legacy path)")
                        .build(),

                // CrmPersonController - 3 events (Issue #111)
                EventTypeRegistration.write("CRM_PERSON_CREATE", "Create a new individual person record")
                        .build(),
                EventTypeRegistration.fastRead("CRM_PERSON_GET", "Retrieve an individual person by ID")
                        .build(),
                EventTypeRegistration.search("CRM_PERSON_SEARCH", "Search for persons by name, email, or phone")
                        .build(),

                // CrmPartyRelationshipController - 4 events (Issue #110)
                EventTypeRegistration.write(
                                "CRM_RELATIONSHIP_CREATE",
                                "Create a relationship between a commercial account and an individual")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CRM_ACCOUNT_CONTACTS_GET", "Retrieve contacts associated with a commercial account")
                        .build(),
                EventTypeRegistration.write(
                                "CRM_RELATIONSHIP_PRIMARY_BILLING_UPDATE",
                                "Designate a relationship as the primary billing contact")
                        .build(),
                EventTypeRegistration.write("CRM_RELATIONSHIP_DEACTIVATE", "Deactivate a party relationship")
                        .build(),

                // CrmSnapshotController - 3 events (Story #99)
                EventTypeRegistration.fastRead(
                                "CRM_SNAPSHOT_PARTY_RETRIEVE", "Retrieve comprehensive CRM snapshot for a party")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CRM_SNAPSHOT_VEHICLE_RETRIEVE",
                                "Retrieve comprehensive CRM snapshot via vehicle ownership")
                        .build(),
                EventTypeRegistration.fastRead("CRM_SNAPSHOT_BILLING_RULES_GET", "Get billing rules for party")
                        .build(),

                // PromotionRedemptionController - 2 events (Story #94)
                EventTypeRegistration.write(
                                "PROMOTION_REDEMPTION_RECORD", "Record a promotion redemption for a customer")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.search("PROMOTION_REDEMPTION_LIST", "List redemptions by customer ID")
                        .apiVersion("1")
                        .build(),

                // WorkorderEventHandler - 3 inbound event processing entries (Story #92)
                EventTypeRegistration.write(
                                "CUSTOMER_EVENT_VEHICLE_UPDATED_PROCESSED",
                                "Process VehicleUpdated event from workorder")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write(
                                "CUSTOMER_EVENT_CONTACT_PREFERENCE_UPDATED_PROCESSED",
                                "Process ContactPreferenceUpdated event from workorder")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write(
                                "CUSTOMER_EVENT_PARTY_NOTE_ADDED_PROCESSED",
                                "Process PartyNoteAdded event from workorder")
                        .apiVersion("1")
                        .build());
    }
}
