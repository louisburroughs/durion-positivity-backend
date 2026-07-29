package com.positivity.customer.internal.enums;

/**
 * Channels a marketing message can reach a party on.
 *
 * <p>Deliberately narrower than {@code PreferredContactMethod}: operational contact
 * (a "your vehicle is ready" call) is governed by the channel preferences, while
 * marketing consent and suppression only ever apply to these two.
 */
public enum MarketingChannel {
    EMAIL,
    SMS
}
