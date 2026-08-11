package com.positivity.peoplecontact.internal.enums;

/**
 * Which kind of party a postal address belongs to (FI-4, #1135). {@code PERSON} rows reference
 * a local {@code person.id}; {@code ORGANIZATION} rows carry the organization/commercial-party
 * UUID owned by another module (pos-customer) — pos-people-contact is the address authority for
 * both, but owns only the person aggregate itself.
 */
public enum PartyType {
    PERSON,
    ORGANIZATION
}
