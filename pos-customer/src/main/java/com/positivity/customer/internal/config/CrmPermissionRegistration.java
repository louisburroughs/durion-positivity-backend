package com.positivity.customer.internal.config;

import com.positivity.security.common.PermissionDefinition;
import com.positivity.security.common.PermissionRegistrationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.positivity.customer.internal.security.CrmPermissionRegistry.*;

/**
 * Registers CRM permissions with pos-security-service at startup.
 *
 * <p>
 * Uses the existing
 * {@link com.positivity.customer.internal.security.CrmPermissionRegistry}
 * permission constants to ensure consistency between runtime checks and
 * registered permissions.
 * </p>
 */
@Component
public class CrmPermissionRegistration extends PermissionRegistrationSupport {

    public CrmPermissionRegistration(
            RestClient.Builder restClientBuilder,
            @Value("${pos.security.base-url:http://pos-security-service:8086}") String securityServiceUrl,
            @Value("${pos.security.permission-registration.enabled:true}") boolean enabled) {
        super(restClientBuilder, securityServiceUrl, "crm", "pos-customer", enabled);
    }

    @Override
    protected List<PermissionDefinition> getPermissions() {
        return List.of(
                // Party Management
                PermissionDefinition.of(PARTY_VIEW, "View party (person/organization) records and details"),
                PermissionDefinition.of(PARTY_SEARCH, "Search parties by name, email, phone, tax ID"),
                PermissionDefinition.of(PARTY_CREATE, "Create new commercial account (party)"),
                PermissionDefinition.of(PARTY_EDIT, "Edit party master data (name, tax ID, identifiers)"),
                PermissionDefinition.of(PARTY_DEACTIVATE, "Deactivate a party record (soft delete)"),
                PermissionDefinition.of(PARTY_MERGE, "Merge two party records into one survivor"),

                // Contact Management
                PermissionDefinition.of(CONTACT_VIEW, "View contact points (email, phone) for a party"),
                PermissionDefinition.of(CONTACT_CREATE, "Add new contact point (email/phone) to a party"),
                PermissionDefinition.of(CONTACT_EDIT, "Edit contact point details"),
                PermissionDefinition.of(CONTACT_DELETE, "Remove contact point from a party"),

                // Contact Roles
                PermissionDefinition.of(CONTACT_ROLE_VIEW, "View assigned roles for contacts on an account"),
                PermissionDefinition.of(CONTACT_ROLE_ASSIGN, "Assign roles (BILLING, APPROVER, DRIVER) to contacts"),
                PermissionDefinition.of(CONTACT_ROLE_REVOKE, "Revoke a role assignment from a contact"),

                // Contact Preferences
                PermissionDefinition.of(CONTACT_PREFERENCE_VIEW, "View communication preferences for a party"),
                PermissionDefinition.of(CONTACT_PREFERENCE_EDIT, "Update communication channel preferences"),

                // Vehicle Management
                PermissionDefinition.of(VEHICLE_VIEW, "View vehicle records (VIN, unit #, description, plate)"),
                PermissionDefinition.of(VEHICLE_SEARCH, "Search vehicles by VIN, unit #, or plate"),
                PermissionDefinition.of(VEHICLE_CREATE, "Create new vehicle record with VIN"),
                PermissionDefinition.of(VEHICLE_EDIT, "Edit vehicle details"),
                PermissionDefinition.of(VEHICLE_DEACTIVATE, "Deactivate vehicle record (soft delete)"),

                // Vehicle-Party Association
                PermissionDefinition.of(VEHICLE_PARTY_ASSOC_CREATE, "Associate party to vehicle"),
                PermissionDefinition.of(VEHICLE_PARTY_ASSOC_VIEW, "View party associations for a vehicle"),
                PermissionDefinition.of(VEHICLE_PARTY_ASSOC_EDIT, "Adjust effective dates for associations"),

                // Vehicle Preferences
                PermissionDefinition.of(VEHICLE_PREFERENCE_VIEW, "View vehicle care preferences"),
                PermissionDefinition.of(VEHICLE_PREFERENCE_EDIT, "Update vehicle care preferences"),

                // Integration Monitoring
                PermissionDefinition.of(PROCESSING_LOG_VIEW, "View ingestion event processing outcomes"),
                PermissionDefinition.of(SUSPENSE_VIEW, "View quarantined/unprocessable events"),
                PermissionDefinition.of(INTEGRATION_AUDIT, "View audit/attempt history for ingestion records"));
    }
}
