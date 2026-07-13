package com.positivity.domainevents.peoplecontact;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command payload for {@code people-contact.user-person-link.create-requested} v1 on
 * {@code people-contact.commands.v1} (ADR-0044 §2 / amended ADR-0043, #876).
 *
 * <p>Sent by pos-security-service when a security flow (self-registration, admin linking) needs a
 * user linked to a person. pos-people-contact remains the link authority: it creates the link and
 * confirms with the {@code people-contact.user-person-link.updated} fact, which is the only
 * writer of the sender's {@code users.person_id} projection. Idempotent per (username, personId);
 * a username already linked to a different person is a permanent conflict the authority logs and
 * drops — the sender's reconciliation surfaces the unmatched projection.
 */
public record UserPersonLinkCreateRequestedV1(
        @NonNull UUID personId,
        @NonNull String username,
        @Nullable String linkType,
        @Nullable String notes) {

    public static final String COMMAND_TYPE = "people-contact.user-person-link.create-requested";
    public static final int SCHEMA_VERSION = 1;
}
