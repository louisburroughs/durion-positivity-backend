package com.positivity.domainevents.peoplecontact;

import org.jspecify.annotations.NonNull;

/**
 * Command payload for {@code people-contact.user-person-link.remove-requested} v1 on
 * {@code people-contact.commands.v1} (ADR-0044 §2 / amended ADR-0043, #876).
 *
 * <p>Sent by pos-security-service when a user account is deleted. pos-people-contact removes the
 * link and confirms with the {@code people-contact.user-person-link.removed} fact; a missing link
 * makes the command a no-op.
 */
public record UserPersonLinkRemoveRequestedV1(@NonNull String username) {

    public static final String COMMAND_TYPE = "people-contact.user-person-link.remove-requested";
    public static final int SCHEMA_VERSION = 1;
}
