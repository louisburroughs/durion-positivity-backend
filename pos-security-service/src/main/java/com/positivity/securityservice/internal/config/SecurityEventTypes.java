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
         * Total: 14 event types.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                // JwtController - 4 events
                                EventTypeRegistration.write("SECURITY_AUTH_LOGIN",
                                                "Authenticate user and issue JWT token").build(),
                                EventTypeRegistration.write("SECURITY_AUTH_TOKEN_PAIR",
                                                "Generate access and refresh token pair").build(),
                                EventTypeRegistration.write("SECURITY_AUTH_REFRESH",
                                                "Refresh access token using refresh token").build(),
                                EventTypeRegistration.write("SECURITY_AUTH_REVOKE",
                                                "Revoke JWT token immediately").build(),

                                // PermissionController - 1 event
                                EventTypeRegistration.write("SECURITY_PERMISSION_REGISTER",
                                                "Register permissions from a service").build(),

                                // RoleController - 4 events
                                EventTypeRegistration.write("SECURITY_ROLE_CREATE",
                                                "Create a new role").build(),
                                EventTypeRegistration.write("SECURITY_ROLE_UPDATE_PERMISSIONS",
                                                "Update permissions assigned to a role").build(),
                                EventTypeRegistration.write("SECURITY_ROLE_ASSIGNMENT_CREATE",
                                                "Create a role assignment for a user").build(),
                                EventTypeRegistration.write("SECURITY_ROLE_ASSIGNMENT_REVOKE",
                                                "Revoke a role assignment by setting its end date").build(),

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
                                                "Assign roles to a user").build());
        }
}
