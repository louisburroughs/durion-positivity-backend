package com.positivity.domainevents.peoplecontact;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command payload for {@code people-contact.person.upsert-requested} v1 on
 * {@code people-contact.commands.v1} (ADR-0044 §2, #875).
 *
 * <p>Sent by HR flows (pos-people employee create/update) that need identity attributes written.
 * pos-people-contact remains the authority: it applies the upsert and confirms by emitting the
 * {@code people-contact.person.updated} fact, which feeds the sender's
 * {@code ext_people_contact_person} replica. The sender generates {@code personId} (UUIDv7) for
 * creates so it can reference the person immediately; the write is eventually consistent.
 *
 * <p>The command is a full-attribute upsert (last writer wins per personId): name fields and the
 * flattened HR contact set (primary/secondary email, work phones) are replaced as a unit.
 */
public record PersonUpsertRequestedV1(
        @NonNull UUID personId,
        @Nullable String firstName,
        @Nullable String lastName,
        @Nullable String preferredName,
        @Nullable String primaryEmail,
        @Nullable String secondaryEmail,
        @NonNull List<String> workPhones) {

    public static final String COMMAND_TYPE = "people-contact.person.upsert-requested";
    public static final int SCHEMA_VERSION = 1;
}
