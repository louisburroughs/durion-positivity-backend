package com.positivity.customer.internal.security;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * CRM Permission Registry
 *
 * Defines CRM permission constants and legacy registration metadata.
 * Registration policy is governed by ADR-0025 (`permissions.yaml` as the
 * canonical source).
 *
 * Permission Format: crm:resource:action
 * Risk Levels: LOW, MEDIUM, HIGH, CRITICAL
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CrmPermissionRegistry {
    private static final String MEDIUM_RISK = "MEDIUM";

    private static final String ISSUE_169 = "Issue #169";

    private static final String ISSUE_1137 = "Issue #1137";

    private static final String ISSUE_1136 = "Issue #1136";

    // ==================== PERMISSION DEFINITIONS ====================

    // Party Management
    public static final String PARTY_VIEW = "crm:party:view";
    public static final String PARTY_SEARCH = "crm:party:search";
    public static final String PARTY_CREATE = "crm:party:create";
    public static final String PARTY_EDIT = "crm:party:edit";
    public static final String PARTY_DEACTIVATE = "crm:party:deactivate";
    public static final String PARTY_MERGE = "crm:party:merge";

    // Billing Rules
    public static final String BILLING_RULES_EDIT = "crm:billing_rules:edit";

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

    // Vehicle Management (reads + party association only; registry writes moved to
    // pos-vehicle-inventory per ADR-0044 §6 — crm:vehicle:edit / crm:vehicle:deactivate retired)
    public static final String VEHICLE_VIEW = "crm:vehicle:view";
    public static final String VEHICLE_SEARCH = "crm:vehicle:search";
    public static final String VEHICLE_CREATE = "crm:vehicle:create";

    // Vehicle-Party Association
    public static final String VEHICLE_PARTY_ASSOC_CREATE = "crm:vehicle_party_association:create";
    public static final String VEHICLE_PARTY_ASSOC_VIEW = "crm:vehicle_party_association:view";
    public static final String VEHICLE_PARTY_ASSOC_EDIT = "crm:vehicle_party_association:edit";

    // Vehicle Preferences
    public static final String VEHICLE_PREFERENCE_VIEW = "crm:vehicle_preference:view";
    public static final String VEHICLE_PREFERENCE_EDIT = "crm:vehicle_preference:edit";

    // Party Tags (Story #1136)
    public static final String TAG_VIEW = "crm:tag:view";
    public static final String TAG_MANAGE = "crm:tag:manage";
    public static final String TAG_ASSIGN = "crm:tag:assign";

    // Segments (Story #1137)
    public static final String SEGMENT_VIEW = "crm:segment:view";
    public static final String SEGMENT_MANAGE = "crm:segment:manage";
    public static final String SEGMENT_RESOLVE = "crm:segment:resolve";

    // Marketing Consent (Story #1138, #1139)
    public static final String CONSENT_VIEW = "crm:consent:view";
    public static final String CONSENT_MANAGE = "crm:consent:manage";

    // Suppression (Story #1140)
    public static final String SUPPRESSION_VIEW = "crm:suppression:view";
    public static final String SUPPRESSION_MANAGE = "crm:suppression:manage";

    // Interaction History (Story #1141)
    public static final String INTERACTION_VIEW = "crm:interaction:view";

    // Person, relationship and promotion-redemption permissions, previously written as string literals at each call
    // site.
    public static final String PERSON_CREATE = "crm:person:create";
    public static final String PERSON_READ = "crm:person:read";
    public static final String PROMOTION_REDEMPTION_RECORD = "crm:promotion_redemption:record";
    public static final String PROMOTION_REDEMPTION_VIEW = "crm:promotion_redemption:view";
    public static final String RELATIONSHIP_CREATE = "crm:relationship:create";
    public static final String RELATIONSHIP_DELETE = "crm:relationship:delete";
    public static final String RELATIONSHIP_READ = "crm:relationship:read";
    public static final String RELATIONSHIP_UPDATE = "crm:relationship:update";

    /**
     * Separate from {@link #INTERACTION_VIEW}: the timeline is evidence of what was said to a
     * customer, so reading it must not carry the right to write into it.
     */
    public static final String INTERACTION_MANAGE = "crm:interaction:manage";

    // Follow-up Tasks (Story #1153)
    public static final String FOLLOWUP_VIEW = "crm:followup:view";
    public static final String FOLLOWUP_MANAGE = "crm:followup:manage";

    // Inquiries (Story #1154)
    public static final String INQUIRY_VIEW = "crm:inquiry:view";
    public static final String INQUIRY_MANAGE = "crm:inquiry:manage";

    // Integration Monitoring (Read-Only)
    public static final String PROCESSING_LOG_VIEW = "crm:processing_log:view";
    public static final String SUSPENSE_VIEW = "crm:suspense:view";
    public static final String INTEGRATION_AUDIT = "crm:integration:audit";

    // ==================== PERMISSION REGISTRATION ====================

    /**
     * Build CRM permission registration request for Security Domain
     */
    /**
     * Builds a permission-registration payload.
     *
     * <p><strong>Nothing calls this.</strong> The live startup registration is
     * {@code internal.config.PermissionRegistration}, which extends
     * {@code PermissionRegistrationSupport} and POSTs {@code permissions.yaml} — the manifest the
     * repo's tooling generates. This method and {@link #buildPermissionDefinitions()} are a legacy
     * second copy of the same facts, kept alive only by
     * {@code CrmPermissionRegistryTest.registration_coversEveryDeclaredConstant}. Removing both is
     * worth doing, but it is a change to what that test guards rather than a rename, so it is not
     * folded into an unrelated refactor.
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
                permission(PARTY_CREATE, "Create new commercial account (party)", MEDIUM_RISK, "Issue #176"),
                permission(PARTY_EDIT, "Edit party master data (name, tax ID, identifiers)", MEDIUM_RISK),
                permission(PARTY_DEACTIVATE, "Deactivate a party record (soft delete)", "HIGH"),
                permission(PARTY_MERGE, "Merge two party records into one survivor", "CRITICAL", "Issue #173"),
                permission(
                        BILLING_RULES_EDIT,
                        "Create or update billing rules configuration for a commercial party",
                        "HIGH"),

                // Contact Management (8 permissions)
                permission(CONTACT_VIEW, "View contact points (email, phone) for a party", "LOW"),
                permission(CONTACT_CREATE, "Add new contact point (email/phone) to a party", MEDIUM_RISK),
                permission(CONTACT_EDIT, "Edit contact point details (phone normalization, primary flag)", MEDIUM_RISK),
                permission(CONTACT_DELETE, "Remove contact point from a party", MEDIUM_RISK),

                // Contact Roles (3 permissions)
                permission(CONTACT_ROLE_VIEW, "View assigned roles for contacts on an account", "LOW", "Issue #172"),
                permission(
                        CONTACT_ROLE_ASSIGN,
                        "Assign roles (BILLING, APPROVER, DRIVER) to contacts",
                        MEDIUM_RISK,
                        "Issue #172"),
                permission(CONTACT_ROLE_REVOKE, "Revoke a role assignment from a contact", MEDIUM_RISK),

                // Contact Preferences (2 permissions)
                permission(
                        CONTACT_PREFERENCE_VIEW,
                        "View communication preferences and consent flags for a party",
                        MEDIUM_RISK,
                        "Issue #171"),
                permission(
                        CONTACT_PREFERENCE_EDIT,
                        "Update communication channel preferences and consent for a party",
                        "HIGH",
                        "Issue #171"),

                // Vehicle Management (3 permissions; registry writes live in pos-vehicle-inventory)
                permission(VEHICLE_VIEW, "View vehicle records (VIN, unit #, description, plate)", "LOW", ISSUE_169),
                permission(VEHICLE_SEARCH, "Search vehicles by VIN, unit #, or plate", "LOW", ISSUE_169),
                permission(VEHICLE_CREATE, "Associate a vehicle VIN with a party", MEDIUM_RISK, ISSUE_169),

                // Vehicle-Party Association (3 permissions)
                permission(
                        VEHICLE_PARTY_ASSOC_CREATE,
                        "Associate party (owner/driver/lessee) to vehicle with effective dating",
                        MEDIUM_RISK),
                permission(
                        VEHICLE_PARTY_ASSOC_VIEW,
                        "View party associations for a vehicle (current and historical)",
                        "LOW"),
                permission(
                        VEHICLE_PARTY_ASSOC_EDIT, "Adjust effective dates for party-vehicle associations", MEDIUM_RISK),

                // Vehicle Preferences (2 permissions)
                permission(
                        VEHICLE_PREFERENCE_VIEW,
                        "View vehicle care preferences (rotation intervals, service types, notes)",
                        "LOW"),
                permission(
                        VEHICLE_PREFERENCE_EDIT,
                        "Update vehicle care preferences with audit history and optimistic locking",
                        MEDIUM_RISK),

                // Party Tags (3 permissions, Story #1136)
                permission(TAG_VIEW, "View the CRM tag catalog and tags attached to parties", "LOW", ISSUE_1136),
                permission(TAG_MANAGE, "Create, edit, retire, or delete CRM tags", MEDIUM_RISK, ISSUE_1136),
                permission(TAG_ASSIGN, "Attach or detach tags on a party", MEDIUM_RISK, ISSUE_1136),

                // Segments (3 permissions, Story #1137)
                permission(SEGMENT_VIEW, "View saved audience segments and their definitions", "LOW", ISSUE_1137),
                permission(
                        SEGMENT_MANAGE,
                        "Create, edit, or delete audience segments and their membership",
                        MEDIUM_RISK,
                        ISSUE_1137),
                permission(
                        SEGMENT_RESOLVE,
                        "Resolve a segment to its matching parties (returns counts and a masked sample)",
                        "HIGH",
                        ISSUE_1137),

                // Marketing Consent (2 permissions, Stories #1138/#1139)
                permission(
                        CONSENT_VIEW,
                        "View per-channel marketing consent and the consent-change audit trail",
                        MEDIUM_RISK,
                        "Issue #1138"),
                permission(
                        CONSENT_MANAGE,
                        "Set per-channel marketing consent, opt-out reasons, and the account master gate",
                        "HIGH",
                        "Issue #1138"),

                // Suppression (2 permissions, Story #1140)
                permission(SUPPRESSION_VIEW, "View the marketing suppression list", MEDIUM_RISK, "Issue #1140"),
                permission(
                        SUPPRESSION_MANAGE,
                        "Add or remove hard address-level suppression entries",
                        "HIGH",
                        "Issue #1140"),

                // Interaction History (2 permissions, Story #1141)
                permission(
                        INTERACTION_VIEW,
                        "View a party's interaction and touch history (campaign sends, calls, notes)",
                        MEDIUM_RISK,
                        "Issue #1141"),
                permission(
                        INTERACTION_MANAGE,
                        "Record a CSR-initiated interaction on a party's timeline",
                        MEDIUM_RISK,
                        "Issue #1141"),

                // Follow-up Tasks (2 permissions, Story #1153)
                permission(FOLLOWUP_VIEW, "View follow-up tasks and the CSR work queue", "LOW", "Issue #1153"),
                permission(
                        FOLLOWUP_MANAGE,
                        "Raise, assign, complete, or dismiss follow-up tasks",
                        MEDIUM_RISK,
                        "Issue #1153"),

                // Inquiries (2 permissions, Story #1154)
                permission(INQUIRY_VIEW, "View inbound service and fleet-quote inquiries", "LOW", "Issue #1154"),
                permission(
                        INQUIRY_MANAGE,
                        "Capture, assign, triage, and convert inbound inquiries into parties",
                        MEDIUM_RISK,
                        "Issue #1154"),

                // Integration Monitoring (3 permissions, read-only)
                permission(
                        PROCESSING_LOG_VIEW,
                        "View ingestion event processing outcomes (success/failure/retry state)",
                        MEDIUM_RISK),
                permission(SUSPENSE_VIEW, "View quarantined/unprocessable events requiring triage", MEDIUM_RISK),
                permission(
                        INTEGRATION_AUDIT,
                        "View audit/attempt history for ingestion records and retry outcomes",
                        "LOW"),

                // Person, relationship and promotion redemption (8 permissions).
                // Added because CrmPermissionRegistryTest binds this list to the declared constants,
                // and converting these call sites from literals to constants brought them into that
                // check. They were already registered for real: the live startup path is
                // PermissionRegistration, which POSTs permissions.yaml — this payload builder has no
                // callers. See the class javadoc.
                permission(PERSON_READ, "View person records and their party linkage", "LOW"),
                permission(PERSON_CREATE, "Create a person record", MEDIUM_RISK),
                permission(RELATIONSHIP_READ, "View relationships between parties", "LOW"),
                permission(RELATIONSHIP_CREATE, "Create a relationship between two parties", MEDIUM_RISK),
                permission(RELATIONSHIP_UPDATE, "Change an existing party relationship", MEDIUM_RISK),
                permission(RELATIONSHIP_DELETE, "Remove a relationship between two parties", "HIGH"),
                permission(PROMOTION_REDEMPTION_VIEW, "View recorded promotion redemptions", "LOW"),
                permission(PROMOTION_REDEMPTION_RECORD, "Record that a customer redeemed a promotion", MEDIUM_RISK));
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
                CONTACT_VIEW,
                CONTACT_CREATE,
                CONTACT_EDIT,
                CONTACT_DELETE,
                CONTACT_ROLE_VIEW,
                CONTACT_ROLE_ASSIGN,
                CONTACT_ROLE_REVOKE,
                CONTACT_PREFERENCE_VIEW,
                CONTACT_PREFERENCE_EDIT);
    }

    /**
     * Vehicle management permissions
     */
    public static List<String> vehiclePermissions() {
        return Arrays.asList(
                VEHICLE_VIEW,
                VEHICLE_SEARCH,
                VEHICLE_CREATE,
                VEHICLE_PARTY_ASSOC_CREATE,
                VEHICLE_PARTY_ASSOC_VIEW,
                VEHICLE_PARTY_ASSOC_EDIT,
                VEHICLE_PREFERENCE_VIEW,
                VEHICLE_PREFERENCE_EDIT);
    }

    /**
     * Integration monitoring permissions
     */
    public static List<String> integrationPermissions() {
        return Arrays.asList(PROCESSING_LOG_VIEW, SUSPENSE_VIEW, INTEGRATION_AUDIT);
    }
}
