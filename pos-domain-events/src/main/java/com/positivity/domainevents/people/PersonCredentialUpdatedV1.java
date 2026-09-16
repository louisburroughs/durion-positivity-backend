package com.positivity.domainevents.people;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code people.person-credential.updated} v1 on {@code people.events.v1} (ADR-0044 §6,
 * CAP-328 / durion#485).
 *
 * <p>Published by pos-people after every credential mutation: ingest, renewal (a new row), expiry
 * recomputation, revocation, supersession. It carries the credential the person holds and the
 * registry skill it certifies — code, competence and GVWR class range — so a consumer can schedule
 * against competence without a synchronous read of the registry. {@code status} is the state on the
 * emission date; a consumer that needs the state on another date derives it from the dates, which
 * is why both travel.
 *
 * <p>The envelope's {@code aggregateVersion} is the emission timestamp in epoch milliseconds
 * (last-writer-wins ordering hint per credentialId; compare with {@code >=} when guarding).
 */
public record PersonCredentialUpdatedV1(
        @NonNull UUID credentialId,
        @NonNull UUID personId,
        @NonNull UUID skillId,
        @NonNull String skillCode,
        @NonNull String competenceCode,
        int minGvwrClass,
        int maxGvwrClass,
        @NonNull String issuer,
        @Nullable String sourceCode,
        @Nullable String sourceCredentialCode,
        @NonNull LocalDate issuedOn,
        @Nullable LocalDate expiresOn,
        @Nullable Integer proficiency,
        @NonNull String status,
        @Nullable UUID evidenceRef,
        @Nullable String supersededBy) {
    public static final String EVENT_TYPE = "people.person-credential.updated";
    public static final int SCHEMA_VERSION = 1;
}
