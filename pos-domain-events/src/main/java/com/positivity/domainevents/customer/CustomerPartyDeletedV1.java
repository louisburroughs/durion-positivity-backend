package com.positivity.domainevents.customer;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a customer party was hard-deleted by its owner (ADR-0044 §6, issue #889 Phase 4.1).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.party.deleted"}. Consumers remove the party from their
 * {@code ext_customer_party} replicas.
 *
 * @param partyId deleted party identifier (also the envelope aggregateId)
 * @param personId canonical person id the party referenced (person parties only)
 */
public record CustomerPartyDeletedV1(
        @NonNull UUID partyId, @Nullable UUID personId) {

    public static final String EVENT_TYPE = "customer.party.deleted";

    public CustomerPartyDeletedV1 {
        if (partyId == null) {
            throw new IllegalArgumentException("partyId must not be null");
        }
    }
}
