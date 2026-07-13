package com.positivity.domainevents.customer;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a customer party (person or commercial account) was created or changed
 * (ADR-0044 §6, issue #889 Phase 4.1).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.party.updated"}. Consumers maintain read-only
 * {@code ext_customer_party} replicas serving name search/resolution (pos-invoice),
 * requirements-met checks (pos-workorder) and customer lookups (pos-shop-manager).
 * Additions must be additive-only within schema version 1.
 *
 * <p>{@code displayName} is the CRM-resolved display name: for commercial parties the
 * account display/legal name, for person parties the identity display name materialized
 * from the owner's people-contact replica. {@code requirementsMet} is the owner-computed
 * work-authorization verdict (status is ACTIVE and, for commercial parties, no credit
 * hold) — re-emitted whenever its inputs change so consumers never recompute it.
 *
 * @param partyId party identifier (also the envelope aggregateId)
 * @param partyType owner's party type name, e.g. {@code PERSON}, {@code COMMERCIAL}
 * @param customerNumber unique human-facing customer number
 * @param displayName CRM-resolved display name (may be null when no identity is known yet)
 * @param legalName legal name (commercial parties only)
 * @param personId canonical people-contact person id (person parties only)
 * @param status account status name, e.g. {@code ACTIVE}, {@code INACTIVE}, {@code MERGED}
 * @param tier account tier name, e.g. {@code STANDARD}
 * @param requirementsMet owner-computed verdict: party currently meets work requirements
 * @param creditHold commercial billing-rules credit hold flag (null when not applicable)
 * @param parentPartyId parent commercial account (null for roots and person parties)
 */
public record CustomerPartyUpdatedV1(
        @NonNull UUID partyId,
        @NonNull String partyType,
        @Nullable String customerNumber,
        @Nullable String displayName,
        @Nullable String legalName,
        @Nullable UUID personId,
        @NonNull String status,
        @Nullable String tier,
        boolean requirementsMet,
        @Nullable Boolean creditHold,
        @Nullable UUID parentPartyId) {

    public static final String EVENT_TYPE = "customer.party.updated";

    public CustomerPartyUpdatedV1 {
        if (partyId == null) {
            throw new IllegalArgumentException("partyId must not be null");
        }
        if (partyType == null || partyType.isBlank()) {
            throw new IllegalArgumentException("partyType must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
    }
}
