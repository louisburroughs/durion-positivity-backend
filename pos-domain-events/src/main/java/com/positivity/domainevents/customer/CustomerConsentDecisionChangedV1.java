package com.positivity.domainevents.customer;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a party's effective marketing-send eligibility changed on one channel (Story #1138).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.consent.decision-changed"}.
 *
 * <p>Carries the <em>resolved decision</em>, not the raw consent fields. The commercial rule
 * (decision O-2: account master gate, else the primary business contact's personal consent)
 * belongs to the CRM, and re-deriving it in every consumer would guarantee the copies drift.
 * A consumer only needs to know whether it may send.
 *
 * <p>Consumers replicating this must treat a missing or stale row as "may not send". Consent is
 * the one signal where being out of date means contacting someone who asked not to be.
 *
 * @param partyId party the decision is about (also the envelope aggregateId)
 * @param channel EMAIL or SMS
 * @param allowed whether a marketing send is currently permitted
 * @param reason machine-readable decision reason, e.g. {@code ACCOUNT_OPT_OUT}
 * @param governingPartyId party whose personal consent governed the decision
 */
public record CustomerConsentDecisionChangedV1(
        @NonNull UUID partyId,
        @NonNull String channel,
        boolean allowed,
        @NonNull String reason,
        @Nullable UUID governingPartyId) {

    public static final String EVENT_TYPE = "customer.consent.decision-changed";

    public CustomerConsentDecisionChangedV1 {
        if (partyId == null) {
            throw new IllegalArgumentException("partyId must not be null");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
