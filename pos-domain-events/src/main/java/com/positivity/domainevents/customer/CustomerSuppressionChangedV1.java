package com.positivity.domainevents.customer;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: an address was added to or removed from the CRM marketing suppression list
 * (Story #1140).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.suppression.changed"}. pos-marketing maintains a local
 * replica so the send worker can check suppression without a synchronous CRM call on every
 * recipient (ADR-0044 §6).
 *
 * <p>The address travels as a hash only — the same SHA-256 of the normalized address the
 * CRM stores — so consumers can match without ever holding the raw value.
 *
 * @param channel EMAIL or SMS
 * @param addressHash SHA-256 hex of the normalized address
 * @param partyId party the address belonged to when suppressed, when known
 * @param suppressed {@code true} when the block was added, {@code false} when lifted
 * @param reason suppression reason on add; null on removal
 */
public record CustomerSuppressionChangedV1(
        @NonNull String channel,
        @NonNull String addressHash,
        @Nullable UUID partyId,
        boolean suppressed,
        @Nullable String reason) {

    public static final String EVENT_TYPE = "customer.suppression.changed";

    public CustomerSuppressionChangedV1 {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be blank");
        }
        if (addressHash == null || addressHash.isBlank()) {
            throw new IllegalArgumentException("addressHash must not be blank");
        }
    }
}
