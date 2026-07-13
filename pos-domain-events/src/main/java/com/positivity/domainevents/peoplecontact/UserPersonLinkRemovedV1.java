package com.positivity.domainevents.peoplecontact;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code people-contact.user-person-link.removed} v1 on
 * {@code people-contact.events.v1} (ADR-0044 §6 / amended ADR-0043, #874).
 *
 * <p>Published when a user-person link is removed. pos-security-service clears the
 * {@code users.person_id} projection for the affected username.
 */
public record UserPersonLinkRemovedV1(
        @NonNull UUID linkId,
        @NonNull UUID personId,
        @NonNull String username) {

    public static final String EVENT_TYPE = "people-contact.user-person-link.removed";
    public static final int SCHEMA_VERSION = 1;
}
