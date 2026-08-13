package com.positivity.domainevents.customer;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a CRM tag was attached to or removed from a party (Story #1136).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.party.tag-changed"}. Marketing consumes it to keep
 * audience replicas current without reading the CRM tables (ADR-0044 §6).
 *
 * @param partyId party whose tags changed (also the envelope aggregateId)
 * @param tagId the tag attached or removed
 * @param tagName tag name at emission time, so consumers need no lookup
 * @param assigned {@code true} for an attach, {@code false} for a removal
 * @param source how the assignment was made (MANUAL, CAMPAIGN, IMPORT, RULE); null on removal
 */
public record CustomerPartyTagChangedV1(
        @NonNull UUID partyId,
        @NonNull UUID tagId,
        @NonNull String tagName,
        boolean assigned,
        @Nullable String source) {

    public static final String EVENT_TYPE = "customer.party.tag-changed";
    public static final int SCHEMA_VERSION = 1;

    public CustomerPartyTagChangedV1 {
        if (partyId == null) {
            throw new IllegalArgumentException("partyId must not be null");
        }
        if (tagId == null) {
            throw new IllegalArgumentException("tagId must not be null");
        }
        if (tagName == null || tagName.isBlank()) {
            throw new IllegalArgumentException("tagName must not be blank");
        }
    }
}
