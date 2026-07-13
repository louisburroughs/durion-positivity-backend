package com.positivity.domainevents.customer;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a person's CRM customer standing changed (ADR-0044 §6, issue #889 Phase 4.1).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.person-identity.updated"} whenever the person's individual
 * customer record or commercial relationships change. Consumed by pos-security-service into
 * its {@code ext_customer_person_identity} replica for self-registration conflict checks.
 *
 * <p>Deliberately carries only customer-owned linkage: names and contact points belong to
 * pos-people-contact and reach consumers on {@code people-contact.events.v1} — consumers
 * join the two replicas by {@code personId} instead of this module re-broadcasting
 * identity data it does not own.
 *
 * <p>There is no companion deleted event: when the person stops being a customer the fact
 * is re-emitted with both flags false and a zero count, which consumers upsert as-is.
 *
 * @param personId canonical people-contact person id (also the envelope aggregateId)
 * @param personPartyId the person's individual-customer party id (null when none)
 * @param individualCustomer true when the person is an individual customer
 * @param commercialContact true when the person is an active contact of a commercial account
 * @param commercialAccountCount number of distinct commercial accounts the person is linked to
 */
public record CustomerPersonIdentityUpdatedV1(
        @NonNull UUID personId,
        @Nullable UUID personPartyId,
        boolean individualCustomer,
        boolean commercialContact,
        int commercialAccountCount) {

    public static final String EVENT_TYPE = "customer.person-identity.updated";

    public CustomerPersonIdentityUpdatedV1 {
        if (personId == null) {
            throw new IllegalArgumentException("personId must not be null");
        }
        if (commercialAccountCount < 0) {
            throw new IllegalArgumentException("commercialAccountCount must not be negative");
        }
    }
}
