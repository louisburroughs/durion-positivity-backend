package com.positivity.domainevents.peoplecontact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code people-contact.person.updated} v1 on {@code people-contact.events.v1}
 * (ADR-0044 §6, #874).
 *
 * <p>Published by pos-people-contact after every person mutation (create, update, resolve-create,
 * contact-point replacement). Carries the full identity snapshot pos-people-contact owns, including
 * the person's typed contact points, so consumers can maintain {@code ext_people_contact_person}
 * replicas without callbacks. Hard deletes are signalled by {@link PersonDeletedV1}.
 *
 * <p>The envelope's {@code aggregateVersion} is the emission timestamp in epoch milliseconds
 * (the person row carries no optimistic-lock version); consumers should treat it as a
 * last-writer-wins ordering hint per personId.
 */
public record PersonUpdatedV1(
        @NonNull UUID personId,
        @Nullable String firstName,
        @Nullable String lastName,
        @Nullable String preferredName,
        @NonNull List<ContactPointV1> contactPoints,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "people-contact.person.updated";
    public static final int SCHEMA_VERSION = 1;

    /** One typed contact point (email or phone) owned by the person (ADR-0015 I2). */
    public record ContactPointV1(
            @NonNull String contactType, @NonNull String value, boolean primary) {}
}
