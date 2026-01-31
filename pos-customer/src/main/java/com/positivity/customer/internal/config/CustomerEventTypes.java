package com.positivity.customer.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-customer module.
 */
public final class CustomerEventTypes {

    private CustomerEventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the customer module.
     * Total: 15 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // CustomerController - 2 events
                EventTypeRegistration.write("CUSTOMER_CUSTOMER_CREATE",
                        "Create a new customer").build(),
                EventTypeRegistration.write("CUSTOMER_CUSTOMER_UPDATE",
                        "Update an existing customer").build(),

                // CrmAccountsController - 8 events
                EventTypeRegistration.search("CUSTOMER_ACCOUNT_TIER_RESOLVE",
                        "Resolve or compute the account tier based on business rules").build(),
                EventTypeRegistration.write("CUSTOMER_PARTY_CREATE",
                        "Create a new commercial party/account").build(),
                EventTypeRegistration.search("CUSTOMER_PARTY_SEARCH",
                        "Search for parties based on various criteria").build(),
                EventTypeRegistration.approval("CUSTOMER_PARTY_MERGE",
                        "Merge multiple parties into a single party record").build(),
                EventTypeRegistration.write("CUSTOMER_CONTACT_ROLE_UPDATE",
                        "Assign or update role assignments for a contact").build(),
                EventTypeRegistration.write("CUSTOMER_COMMUNICATION_PREFERENCE_UPSERT",
                        "Set or update communication preferences for a party").build(),
                EventTypeRegistration.write("CUSTOMER_VEHICLE_CREATE",
                        "Associate a new vehicle with a party").build(),

                // CrmVehiclesController - 3 events
                EventTypeRegistration.write("CUSTOMER_VEHICLE_CREATE_LEGACY",
                        "Create vehicle for customer (legacy path)").build(),
                EventTypeRegistration.write("CUSTOMER_VEHICLE_UPDATE",
                        "Update vehicle information").build(),
                EventTypeRegistration.write("CUSTOMER_VEHICLE_TRANSFER",
                        "Transfer vehicle ownership between customers").build(),

                // CrmContactsController - 1 event (legacy path)
                EventTypeRegistration.write("CUSTOMER_CONTACT_ROLE_UPDATE_LEGACY",
                        "Update contact roles (legacy path)").build(),

                // CrmCommunicationPreferencesController - 1 event (legacy path)
                EventTypeRegistration.write("CUSTOMER_COMMUNICATION_PREFERENCE_UPSERT_LEGACY",
                        "Set or update communication preferences (legacy path)").build());
    }
}
