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
                        .build());
    }
}
