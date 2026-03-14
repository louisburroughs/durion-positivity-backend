package com.positivity.securityservice.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-security-service module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 */
public final class SecurityEventTypes {

        private SecurityEventTypes() {
                // Utility class
        }

        /**
         * All event type registrations for the security module.
         * Total: 28 event types.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                // AuthController - 1 event
                                EventTypeRegistration.write("SECURITY_AUTH_LOGIN",
                                                "User login via /v1/auth/login").build(),

                                // JwtController - 4 events
                                EventTypeRegistration.write("SECURITY_AUTH_INTERNAL_TOKEN_ISSUE",
                                                "Issue JWT token for internal trusted caller").build(),
                                EventTypeRegistration.write("SECURITY_AUTH_TOKEN_PAIR",
                                                "Generate access and refresh token pair").build(),
                                EventTypeRegistration.write("SECURITY_AUTH_REFRESH",
                                                "Refresh access token using refresh token").build(),
                                EventTypeRegistration.write("SECURITY_AUTH_REVOKE",
                                                "Revoke JWT token immediately").build(),

                                // PermissionController - 2 events
                                EventTypeRegistration.write("SECURITY_PERMISSION_REGISTER",
                                                "Register permissions from a service").build(),
                                EventTypeRegistration.fastRead("PERMISSION_DECODE_EXECUTE",
                                                "Decode a perm_bits claim for diagnostics").build(),

                                // RoleController - 7 events
                                EventTypeRegistration.write("SECURITY_ROLE_CREATE",
                                                "Create a new role").build(),
                                EventTypeRegistration.write("SECURITY_ROLE_PERMISSIONS_UPDATE",
                                                "Update permissions assigned to a role").build(),
                                EventTypeRegistration.write("SECURITY_ROLE_ASSIGNMENT_CREATE",
                                                "Create a role assignment for a user").build(),
                                EventTypeRegistration.write("SECURITY_ROLE_ASSIGNMENT_REVOKE",
                                                "Revoke a role assignment by setting its end date").build(),
                                EventTypeRegistration.write("SECURITY_ROLE_ASSIGNED",
                                                "Persist audit record when a role is assigned to a user").build(),
                                EventTypeRegistration.write("SECURITY_ROLE_REVOKED",
                                                "Persist audit record when a role is revoked from a user").build(),
                                EventTypeRegistration.write("SECURITY_PERMISSION_DENIED",
                                                "Persist audit record when authorization is denied").build(),

                                // UserRoleController - 2 events
                                EventTypeRegistration.write("SECURITY_USER_ROLE_ASSIGN",
                                                "Assign a role to a user").build(),
                                EventTypeRegistration.write("SECURITY_USER_ROLE_REVOKE",
                                                "Revoke a role from a user").build(),

                                // AuditController - 2 events
                                EventTypeRegistration.write("SECURITY_AUDIT_EVENT_CREATE",
                                                "Create an immutable audit event").build(),
                                EventTypeRegistration.write("SECURITY_AUDIT_PRICING_SNAPSHOT_CREATE",
                                                "Create a pricing snapshot for audit purposes").build(),

                                // UserController - 5 events
                                EventTypeRegistration.write("SECURITY_USER_CREATE",
                                                "Create a new user account").build(),
                                EventTypeRegistration.write("SECURITY_USER_LOGIN",
                                                "User login via username and password").build(),
                                EventTypeRegistration.write("SECURITY_USER_UPDATE",
                                                "Update an existing user account").build(),
                                EventTypeRegistration.write("SECURITY_USER_DELETE",
                                                "Delete a user account").build(),
                                EventTypeRegistration.write("SECURITY_USER_ASSIGN_ROLES",
                                                "Assign roles to a user").build(),

                                // AdminAccountStateController - 5 events
                                EventTypeRegistration.write("SECURITY_USER_UNLOCK",
                                                "Unlock a user account").build(),
                                EventTypeRegistration.write("SECURITY_USER_ENABLE",
                                                "Enable a user account").build(),
                                EventTypeRegistration.write("SECURITY_USER_DISABLE",
                                                "Disable a user account").build(),
                                EventTypeRegistration.write("SECURITY_USER_EXPIRE_ACCOUNT",
                                                "Expire a user account").build(),
                                EventTypeRegistration.write("SECURITY_USER_EXPIRE_CREDENTIALS",
                                                "Expire user credentials").build());
        }
}
