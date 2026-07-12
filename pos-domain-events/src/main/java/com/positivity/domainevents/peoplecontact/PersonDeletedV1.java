package com.positivity.domainevents.peoplecontact;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code people-contact.person.deleted} v1 on {@code people-contact.events.v1}
 * (ADR-0044 §6, #874).
 *
 * <p>Published when a person record is hard-deleted from the identity authority. Consumers
 * remove (or tombstone) the corresponding {@code ext_people_contact_person} replica row.
 */
public record PersonDeletedV1(@NonNull UUID personId) {

    public static final String EVENT_TYPE = "people-contact.person.deleted";
    public static final int SCHEMA_VERSION = 1;
}
