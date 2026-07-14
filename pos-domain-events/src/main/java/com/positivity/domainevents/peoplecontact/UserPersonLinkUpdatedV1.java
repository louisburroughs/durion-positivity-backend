package com.positivity.domainevents.peoplecontact;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code people-contact.user-person-link.updated} v1 on
 * {@code people-contact.events.v1} (ADR-0044 §6 / amended ADR-0043, #874).
 *
 * <p>Published after a user-person link is created or its attributes change. pos-security-service
 * projects {@code users.person_id} exclusively from these facts (link fact events are the only
 * writer of that projection per the amended ADR-0043 §2). The link aggregate is keyed by
 * {@code linkId}; {@code username} is unique across links.
 */
public record UserPersonLinkUpdatedV1(
        @NonNull UUID linkId,
        @NonNull UUID personId,
        @NonNull String username,
        @NonNull String status,
        @Nullable String linkType,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "people-contact.user-person-link.updated";
    public static final int SCHEMA_VERSION = 1;
}
