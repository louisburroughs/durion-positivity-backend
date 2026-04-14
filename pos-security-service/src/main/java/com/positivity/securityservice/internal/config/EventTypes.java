package com.positivity.securityservice.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-security-service module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the security module.
     * Total: 31 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // AuthController - 1 event
                EventTypeRegistration.write("SECURITY_AUTH_LOGIN", "User login via /v1/auth/login")
                        .build(),
                EventTypeRegistration.write("SECURITY_AUTH_SELF_REGISTER", "Register a new self-service user account")
                        .build(),

                // JwtController - 4 events
                EventTypeRegistration.write(
                                "SECURITY_AUTH_INTERNAL_TOKEN_ISSUE", "Issue JWT token for internal trusted caller")
                        .build(),
                EventTypeRegistration.write("SECURITY_AUTH_TOKEN_PAIR", "Generate access and refresh token pair")
                        .build(),
                EventTypeRegistration.write("SECURITY_AUTH_REFRESH", "Refresh access token using refresh token")
                        .build(),
                EventTypeRegistration.write("SECURITY_AUTH_REVOKE", "Revoke JWT token immediately")
                        .build(),

                // PermissionController - 2 events
                EventTypeRegistration.write("SECURITY_PERMISSION_REGISTER", "Register permissions from a service")
                        .build(),
                EventTypeRegistration.fastRead(
                                "SECURITY_PERMISSION_DECODE_EXECUTE", "Decode a perm_bits claim for diagnostics")
                        .build(),

                // PrincipalRoleController - 1 event
                EventTypeRegistration.write("SECURITY_PRINCIPAL_ROLE_ASSIGN", "Assign a principal to a role")
                        .build(),

                // RoleController - 8 events
                EventTypeRegistration.write("SECURITY_ROLE_CREATE", "Create a new role")
                        .build(),
                EventTypeRegistration.write("SECURITY_ROLE_PERMISSION_GRANT", "Grant a permission to a role")
                        .build(),
                EventTypeRegistration.write("SECURITY_ROLE_PERMISSION_REVOKE", "Revoke a permission from a role")
                        .build(),
                EventTypeRegistration.write("SECURITY_ROLE_PERMISSION_ASSIGN", "Assign a permission to a role")
                        .build(),
                EventTypeRegistration.write("SECURITY_ROLE_PERMISSIONS_UPDATE", "Update permissions assigned to a role")
                        .build(),
                EventTypeRegistration.write("SECURITY_ROLE_ASSIGNMENT_CREATE", "Create a role assignment for a user")
                        .build(),
                EventTypeRegistration.write(
                                "SECURITY_ROLE_ASSIGNMENT_REVOKE", "Revoke a role assignment by setting its end date")
                        .build(),
                EventTypeRegistration.write("SECURITY_ROLE_DELETE", "Delete a role")
                        .build(),

                // UserRoleController - 2 events
                EventTypeRegistration.write("SECURITY_USER_ROLE_ASSIGN", "Assign a role to a user")
                        .build(),
                EventTypeRegistration.write("SECURITY_USER_ROLE_REVOKE", "Revoke a role from a user")
                        .build(),

                // AuditController - 2 events
                EventTypeRegistration.write("SECURITY_AUDIT_EVENT_CREATE", "Create an immutable audit event")
                        .build(),
                EventTypeRegistration.write(
                                "SECURITY_AUDIT_PRICING_SNAPSHOT_CREATE",
                                "Create a pricing snapshot for audit purposes")
                        .build(),

                // UserController - 4 events
                EventTypeRegistration.write("SECURITY_USER_CREATE", "Create a new user account")
                        .build(),
                EventTypeRegistration.write("SECURITY_USER_UPDATE", "Update an existing user account")
                        .build(),
                EventTypeRegistration.write("SECURITY_USER_DELETE", "Delete a user account")
                        .build(),
                EventTypeRegistration.write("SECURITY_USER_ASSIGN_ROLES", "Assign roles to a user")
                        .build(),

                // AdminAccountStateController - 5 events
                EventTypeRegistration.write("SECURITY_USER_UNLOCK", "Unlock a user account")
                        .build(),
                EventTypeRegistration.write("SECURITY_USER_ENABLE", "Enable a user account")
                        .build(),
                EventTypeRegistration.write("SECURITY_USER_DISABLE", "Disable a user account")
                        .build(),
                EventTypeRegistration.write("SECURITY_USER_EXPIRE_ACCOUNT", "Expire a user account")
                        .build(),
                EventTypeRegistration.write("SECURITY_USER_EXPIRE_CREDENTIALS", "Expire user credentials")
                        .build(),

                // SelfRegistrationReviewController - 1 event
                EventTypeRegistration.write(
                                "SECURITY_SELF_REGISTRATION_REVIEW_RESOLVE",
                                "Resolve a blocked self-registration review case")
                        .build());
    }
}
