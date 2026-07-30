package com.positivity.domainevents.peoplecontact;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code people-contact.organization-address.removed} v1 on
 * {@code people-contact.events.v1} (FI-4, #1135).
 *
 * <p>Published by pos-people-contact when an organization's structured postal address is
 * deleted; consumers drop their replica row for {@code organizationId}. Person address removal
 * needs no dedicated event — it rides on {@link PersonUpdatedV1} with a null
 * {@code postalAddress}.
 */
public record OrganizationAddressRemovedV1(@NonNull UUID organizationId) {

    public static final String EVENT_TYPE = "people-contact.organization-address.removed";
    public static final int SCHEMA_VERSION = 1;
}
