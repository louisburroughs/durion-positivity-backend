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
     * Total: 37 event types.
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
                EventTypeRegistration.search("CUSTOMER_PARTY_RESOLVE", "Batch-resolve party ids to display names")
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

                // CustomerRequirementsController - 1 event (Issue #653)
                EventTypeRegistration.fastRead(
                                "CUSTOMER_REQUIREMENTS_MET_GET",
                                "Check whether a customer meets workorder eligibility requirements")
                        .build(),

                // PromotionRedemptionController - 2 events (Story #94)
                EventTypeRegistration.write(
                                "PROMOTION_REDEMPTION_RECORD", "Record a promotion redemption for a customer")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.search("PROMOTION_REDEMPTION_LIST", "List redemptions by customer ID")
                        .apiVersion("1")
                        .build(),

                // CrmTagController - 7 events (Story #1136)
                EventTypeRegistration.fastRead("CRM_TAG_LIST", "List the CRM tag catalog")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("CRM_TAG_GET", "Retrieve a single CRM tag")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_TAG_CREATE", "Create a CRM tag")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_TAG_UPDATE", "Update a CRM tag")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_TAG_DELETE", "Delete a CRM tag and its assignments")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("CRM_PARTY_TAG_LIST", "List tags attached to a party")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_PARTY_TAG_ASSIGN", "Attach a tag to a party")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_PARTY_TAG_REMOVE", "Detach a tag from a party")
                        .apiVersion("1")
                        .build(),

                // CrmConsentController - 5 events (Stories #1138/#1139)
                EventTypeRegistration.fastRead(
                                "CRM_MARKETING_CONSENT_GET", "Retrieve per-channel marketing consent for a party")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write(
                                "CRM_MARKETING_CONSENT_UPDATE", "Update per-channel marketing consent for a party")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write(
                                "CRM_MARKETING_ACCOUNT_GATE_SET",
                                "Set or clear the commercial-account marketing master gate")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CRM_CONSENT_HISTORY_GET", "Retrieve the marketing-consent audit trail for a party")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CRM_MARKETING_ELIGIBILITY_GET",
                                "Resolve marketing send eligibility for a party and channel")
                        .apiVersion("1")
                        .build(),

                // CrmSuppressionController - 4 events (Story #1140)
                EventTypeRegistration.fastRead("CRM_SUPPRESSION_LIST", "List marketing suppression entries")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("CRM_SUPPRESSION_CHECK", "Check whether an address is suppressed")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_SUPPRESSION_ADD", "Hard-block an address from marketing")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_SUPPRESSION_REMOVE", "Lift a marketing suppression entry")
                        .apiVersion("1")
                        .build(),

                // CrmInteractionController - 2 events (Story #1141)
                EventTypeRegistration.fastRead("CRM_INTERACTION_LIST", "List a party's interaction history")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_INTERACTION_RECORD", "Record a CSR-initiated party interaction")
                        .apiVersion("1")
                        .build(),

                // CrmSegmentController - 8 events (Story #1137)
                EventTypeRegistration.fastRead("CRM_SEGMENT_LIST", "List saved audience segments")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("CRM_SEGMENT_GET", "Retrieve a segment definition")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_SEGMENT_CREATE", "Create an audience segment")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_SEGMENT_UPDATE", "Update an audience segment")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_SEGMENT_DELETE", "Delete an audience segment")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_SEGMENT_MEMBERS_ADD", "Pin parties into a static segment")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_SEGMENT_MEMBER_REMOVE", "Unpin a party from a static segment")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.search("CRM_SEGMENT_RESOLVE", "Resolve a segment to counts and a masked sample")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.search(
                                "CRM_SEGMENT_RESOLVE_PARTY_IDS", "Resolve a segment to its matching party ids")
                        .apiVersion("1")
                        .build(),

                // CrmFollowUpController - 7 events (Story #1153)
                EventTypeRegistration.fastRead("CRM_FOLLOWUP_QUEUE", "Retrieve the CSR follow-up work queue")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("CRM_FOLLOWUP_GET", "Retrieve a follow-up task")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("CRM_FOLLOWUP_LIST", "List follow-up tasks for a party")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_FOLLOWUP_CREATE", "Raise a follow-up task")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_FOLLOWUP_ASSIGN", "Assign a follow-up task")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_FOLLOWUP_COMPLETE", "Complete a follow-up task with an outcome")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_FOLLOWUP_DISMISS", "Dismiss a follow-up task")
                        .apiVersion("1")
                        .build(),

                // CrmInquiryController / PublicInquiryController - 7 events (Story #1154)
                EventTypeRegistration.fastRead("CRM_INQUIRY_LIST", "List inbound inquiries")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("CRM_INQUIRY_GET", "Retrieve an inquiry")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_INQUIRY_CAPTURE", "Capture an inquiry taken by staff")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_INQUIRY_ASSIGN", "Assign an inquiry")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_INQUIRY_STATUS_UPDATE", "Update an inquiry's status")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("CRM_INQUIRY_CONVERT", "Convert an inquiry into a party")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write(
                                "CRM_PUBLIC_INQUIRY_SUBMIT", "Capture an inquiry from the public unauthenticated form")
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
