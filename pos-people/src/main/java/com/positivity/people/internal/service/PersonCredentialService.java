package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.CredentialUpsertCommand;
import com.positivity.people.internal.dto.PersonCredentialResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Credentials a person holds (CAP-328, spec D6/D7): upsert by natural key, supersede, read. */
public interface PersonCredentialService {

    /**
     * Inserts or updates the credential with the natural key (person, skill, issuer, issuedOn). A
     * renewal — a later {@code issuedOn} — is a new row. Resolves the skill through the registry
     * and fails loudly on an unknown code. Publishes the fact.
     */
    @NonNull
    PersonCredentialResponse upsert(
            @NonNull UUID personId, @NonNull CredentialUpsertCommand command, @NonNull String actor);

    /**
     * Marks the person's credentials from {@code sourceSystem} that are not in {@code retainedIds}
     * SUPERSEDED, recording {@code supersededBy} — a feed that stops sending a credential never
     * deletes it. Returns how many rows changed. Already superseded or revoked rows are left alone.
     */
    int supersedeAbsent(
            @NonNull UUID personId,
            @NonNull String sourceSystem,
            @NonNull Set<UUID> retainedIds,
            @NonNull String supersededBy);

    /** Every credential the person holds or held, newest issue first, with today's derived status. */
    @NonNull
    List<PersonCredentialResponse> listByPerson(@NonNull UUID personId);
}
