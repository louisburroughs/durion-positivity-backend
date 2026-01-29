package com.positivity.customer.internal.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRM Permission Registry
 * 
 * Defines all CRM permissions per ADR 0002 (CRM Domain Permission Taxonomy).
 * Permissions are registered with the central Security Domain at service
 * startup.
 * 
 * Permission Format: crm:resource:action
 * Risk Levels: LOW, MEDIUM, HIGH, CRITICAL
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CrmPermissionRegistry {

    // ==================== PERMISSION DEFINITIONS ====================

    // Party Management
    public static final String PARTY_VIEW = "crm:party:view";
    public static final String PARTY_SEARCH = "crm:party:search";
    public static final String PARTY_CREATE = "crm:party:create";
    public static final String PARTY_EDIT = "crm:party:edit";
    public static final String PARTY_DEACTIVATE = "crm:party:deactivate";
    public static final String PARTY_MERGE = "crm:party:merge";

    // Contact Management
    public static final String CONTACT_VIEW = "crm:contact:view";
    public static final String CONTACT_CREATE = "crm:contact:create";
    public static final String CONTACT_EDIT = "crm:contact:edit";
    public static final String CONTACT_DELETE = "crm:contact:delete";

    // Contact Roles
    public static final String CONTACT_ROLE_VIEW = "crm:contact_role:view";
    public static final String CONTACT_ROLE_ASSIGN = "crm:contact_role:assign";
    public static final String CONTACT_ROLE_REVOKE = "crm:contact_role:revoke";

    // Contact Preferences
    public static final String CONTACT_PREFERENCE_VIEW = "crm:contact_preference:view";
    public static final String CONTACT_PREFERENCE_EDIT = "crm:contact_preference:edit";

    // Vehicle Management
    public static final String VEHICLE_VIEW = "crm:vehicle:view";
    public static final String VEHICLE_SEARCH = "crm:vehicle:search";
    public static final String VEHICLE_CREATE = "crm:vehicle:create";
    public static final String VEHICLE_EDIT = "crm:vehicle:edit";
    public static final String VEHICLE_DEACTIVATE = "crm:vehicle:deactivate";

    // Vehicle-Party Association
    public static final String VEHICLE_PARTY_ASSOC_CREATE = "crm:vehicle_party_association:create";
    public static final String VEHICLE_PARTY_ASSOC_VIEW = "crm:vehicle_party_association:view";
    public static final String VEHICLE_PARTY_ASSOC_EDIT = "crm:vehicle_party_association:edit";

    // Vehicle Preferences
    public static final String VEHICLE_PREFERENCE_VIEW = "crm:vehicle_preference:view";
    public static final String VEHICLE_PREFERENCE_EDIT = "crm:vehicle_preference:edit";

    // Integration Monitoring (Read-Only)
    public static final String PROCESSING_LOG_VIEW = "crm:processing_log:view";
    public static final String SUSPENSE_VIEW = "crm:suspense:view";
    public static final String INTEGRATION_AUDIT = "crm:integration:audit";

    // ==================== PERMISSION REGISTRATION ====================

    /**
     * Build CRM permission registration request for Security Domain
     */
    public static Map<String, Object> buildCrmPermissionRegistration() {
        Map<String, Object> registration = new LinkedHashMap<>();

        registration.put("domain", "crm");
        registration.put("serviceName", "pos-customer");
        registration.put("version", "1.0");
        registration.put("permissions", buildPermissionDefinitions());

        return registration;
    }

    /**
     * Define all CRM permissions with metadata
     */
    private static List<Map<String, String>> buildPermissionDefinitions() {
        return Arrays.asList(
                // Party Management (6 permissions)
                permission(PARTY_VIEW, "View party (person/organization) records and details", "LOW"),
                permission(PARTY_SEARCH, "Search parties by name, email, phone, tax ID", "LOW"),
                permission(PARTY_CREATE, "Create new commercial account (party)", "MEDIUM", "Issue #176"),
                permission(PARTY_EDIT, "Edit party master data (name, tax ID, identifiers)", "MEDIUM"),
                permission(PARTY_DEACTIVATE, "Deactivate a party record (soft delete)", "HIGH"),
                permission(PARTY_MERGE, "Merge two party records into one survivor", "CRITICAL", "Issue #173"),

                // Contact Management (8 permissions)
                permission(CONTACT_VIEW, "View contact points (email, phone) for a party", "LOW"),
                permission(CONTACT_CREATE, "Add new contact point (email/phone) to a party", "MEDIUM"),
                permission(CONTACT_EDIT, "Edit contact point details (phone normalization, primary flag)", "MEDIUM"),
                permission(CONTACT_DELETE, "Remove contact point from a party", "MEDIUM"),

                // Contact Roles (3 permissions)
                permission(CONTACT_ROLE_VIEW, "View assigned roles for contacts on an account", "LOW", "Issue #172"),
                permission(CONTACT_ROLE_ASSIGN, "Assign roles (BILLING, APPROVER, DRIVER) to contacts", "MEDIUM",
                        "Issue #172"),
                permission(CONTACT_ROLE_REVOKE, "Revoke a role assignment from a contact", "MEDIUM"),

                // Contact Preferences (2 permissions)
                permission(CONTACT_PREFERENCE_VIEW, "View communication preferences and consent flags for a party",
                        "MEDIUM", "Issue #171"),
                permission(CONTACT_PREFERENCE_EDIT, "Update communication channel preferences and consent for a party",
                        "HIGH", "Issue #171"),

                // Vehicle Management (5 permissions)
                permission(VEHICLE_VIEW, "View vehicle records (VIN, unit #, description, plate)", "LOW", "Issue #169"),
                permission(VEHICLE_SEARCH, "Search vehicles by VIN, unit #, or plate", "LOW", "Issue #169"),
                permission(VEHICLE_CREATE, "Create new vehicle record with VIN and optional party association",
                        "MEDIUM", "Issue #169"),
                permission(VEHICLE_EDIT, "Edit vehicle details (unit #, description, license plate, status)", "MEDIUM"),
                permission(VEHICLE_DEACTIVATE, "Deactivate vehicle record (soft delete)", "HIGH"),

                // Vehicle-Party Association (3 permissions)
                permission(VEHICLE_PARTY_ASSOC_CREATE,
                        "Associate party (owner/driver/lessee) to vehicle with effective dating", "MEDIUM"),
                permission(VEHICLE_PARTY_ASSOC_VIEW, "View party associations for a vehicle (current and historical)",
                        "LOW"),
                permission(VEHICLE_PARTY_ASSOC_EDIT, "Adjust effective dates for party-vehicle associations", "MEDIUM"),

                // Vehicle Preferences (2 permissions)
                permission(VEHICLE_PREFERENCE_VIEW,
                        "View vehicle care preferences (rotation intervals, service types, notes)", "LOW"),
                permission(VEHICLE_PREFERENCE_EDIT,
                        "Update vehicle care preferences with audit history and optimistic locking", "MEDIUM"),

                // Integration Monitoring (3 permissions, read-only)
                permission(PROCESSING_LOG_VIEW,
                        "View ingestion event processing outcomes (success/failure/retry state)", "MEDIUM"),
                permission(SUSPENSE_VIEW, "View quarantined/unprocessable events requiring triage", "MEDIUM"),
                permission(INTEGRATION_AUDIT, "View audit/attempt history for ingestion records and retry outcomes",
                        "LOW"));
    }

    /**
     * Helper to create a permission definition map
     */
    private static Map<String, String> permission(String name, String description, String riskLevel) {
        Map<String, String> perm = new LinkedHashMap<>();
        perm.put("name", name);
        perm.put("description", description);
        perm.put("riskLevel", riskLevel);
        return perm;
    }

    /**
     * Helper to create a permission definition map with story reference
     */
    private static Map<String, String> permission(String name, String description, String riskLevel, String storyRef) {
        Map<String, String> perm = permission(name, description, riskLevel);
        perm.put("applicableStory", storyRef);
        return perm;
    }

    // ==================== PERMISSION SETS FOR COMMON OPERATIONS
    // ====================

    /**
     * Party CRUD permissions
     */
    public static List<String> partyPermissions() {
        return Arrays.asList(PARTY_VIEW, PARTY_SEARCH, PARTY_CREATE, PARTY_EDIT, PARTY_DEACTIVATE, PARTY_MERGE);
    }

    /**
     * Contact management permissions
     */
    public static List<String> contactPermissions() {
        return Arrays.asList(
                CONTACT_VIEW, CONTACT_CREATE, CONTACT_EDIT, CONTACT_DELETE,
                CONTACT_ROLE_VIEW, CONTACT_ROLE_ASSIGN, CONTACT_ROLE_REVOKE,
                CONTACT_PREFERENCE_VIEW, CONTACT_PREFERENCE_EDIT);
    }

    /**
     * Vehicle management permissions
     */
    public static List<String> vehiclePermissions() {
        return Arrays.asList(
                VEHICLE_VIEW, VEHICLE_SEARCH, VEHICLE_CREATE, VEHICLE_EDIT, VEHICLE_DEACTIVATE,
                VEHICLE_PARTY_ASSOC_CREATE, VEHICLE_PARTY_ASSOC_VIEW, VEHICLE_PARTY_ASSOC_EDIT,
                VEHICLE_PREFERENCE_VIEW, VEHICLE_PREFERENCE_EDIT);
    }

    /**
     * Integration monitoring permissions
     */
    public static List<String> integrationPermissions() {
        return Arrays.asList(PROCESSING_LOG_VIEW, SUSPENSE_VIEW, INTEGRATION_AUDIT);
    }
}
