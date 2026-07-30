package com.positivity.domainevents.peoplecontact;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code people-contact.organization-address.updated} v1 on
 * {@code people-contact.events.v1} (FI-4, #1135).
 *
 * <p>Published by pos-people-contact whenever an organization's structured postal address is
 * created or replaced. pos-people-contact is the postal-address authority for organization
 * parties too, but it does not own the organization aggregate itself — {@code organizationId}
 * is the party UUID minted by the module that owns the organization (pos-customer's commercial
 * party), stored and echoed verbatim.
 *
 * <p>The envelope's {@code aggregateVersion} is the emission timestamp in epoch milliseconds
 * (last-writer-wins ordering hint per organizationId), mirroring {@link PersonUpdatedV1}.
 * Removal is signalled by {@link OrganizationAddressRemovedV1}.
 */
public record OrganizationAddressUpdatedV1(
        @NonNull UUID organizationId,
        @NonNull PostalAddressV1 postalAddress,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "people-contact.organization-address.updated";
    public static final int SCHEMA_VERSION = 1;
}
