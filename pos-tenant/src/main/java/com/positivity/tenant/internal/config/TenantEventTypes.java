package com.positivity.tenant.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/** Registry of the {@code @EmitEvent} ids the platform-admin API emits, registered at startup. */
public final class TenantEventTypes {

    private TenantEventTypes() {
        // Utility class
    }

    public static final EventTypeRegistration TENANT_CREATE = EventTypeRegistration.approval(
                    "TENANT_CREATE", "Register a tenant under an account")
            .build();

    public static final EventTypeRegistration TENANT_GET =
            EventTypeRegistration.fastRead("TENANT_GET", "Read a tenant").build();

    public static final EventTypeRegistration TENANT_LIST =
            EventTypeRegistration.search("TENANT_LIST", "List tenants").build();

    public static final EventTypeRegistration TENANT_UPDATE = EventTypeRegistration.write(
                    "TENANT_UPDATE", "Update a tenant's display name or cell")
            .build();

    public static final EventTypeRegistration TENANT_SUSPEND =
            EventTypeRegistration.approval("TENANT_SUSPEND", "Suspend a tenant").build();

    public static final EventTypeRegistration TENANT_REACTIVATE = EventTypeRegistration.approval(
                    "TENANT_REACTIVATE", "Reactivate a suspended tenant")
            .build();

    public static final EventTypeRegistration TENANT_DECOMMISSION = EventTypeRegistration.approval(
                    "TENANT_DECOMMISSION", "Decommission a tenant (terminal)")
            .build();

    public static final EventTypeRegistration ACCOUNT_CREATE =
            EventTypeRegistration.write("ACCOUNT_CREATE", "Create an account").build();

    public static final EventTypeRegistration ACCOUNT_GET =
            EventTypeRegistration.fastRead("ACCOUNT_GET", "Read an account").build();

    public static final EventTypeRegistration ACCOUNT_LIST =
            EventTypeRegistration.search("ACCOUNT_LIST", "List accounts").build();

    public static final EventTypeRegistration ACCOUNT_UPDATE =
            EventTypeRegistration.write("ACCOUNT_UPDATE", "Update an account").build();

    public static final EventTypeRegistration ACCOUNT_CONTACT_ADD = EventTypeRegistration.write(
                    "ACCOUNT_CONTACT_ADD", "Add a contact to an account")
            .build();

    public static final EventTypeRegistration ACCOUNT_CONTACT_UPDATE = EventTypeRegistration.write(
                    "ACCOUNT_CONTACT_UPDATE", "Update an account contact")
            .build();

    public static final EventTypeRegistration ACCOUNT_CONTACT_REMOVE = EventTypeRegistration.write(
                    "ACCOUNT_CONTACT_REMOVE", "Remove an account contact")
            .build();

    public static final EventTypeRegistration ACCOUNT_BILLING_PROFILE_PUT = EventTypeRegistration.write(
                    "ACCOUNT_BILLING_PROFILE_PUT", "Create or replace an account's billing profile")
            .build();

    public static List<EventTypeRegistration> all() {
        return List.of(
                TENANT_CREATE,
                TENANT_GET,
                TENANT_LIST,
                TENANT_UPDATE,
                TENANT_SUSPEND,
                TENANT_REACTIVATE,
                TENANT_DECOMMISSION,
                ACCOUNT_CREATE,
                ACCOUNT_GET,
                ACCOUNT_LIST,
                ACCOUNT_UPDATE,
                ACCOUNT_CONTACT_ADD,
                ACCOUNT_CONTACT_UPDATE,
                ACCOUNT_CONTACT_REMOVE,
                ACCOUNT_BILLING_PROFILE_PUT);
    }
}
