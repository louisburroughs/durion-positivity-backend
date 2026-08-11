package com.positivity.customer.internal.enums;

/**
 * Which kind of party a segment or campaign targets (Story #1137).
 *
 * <p>The split is not cosmetic: commercial and individual audiences differ in who consents
 * (an account's primary contact vs the person), what tokens a message can use, and which
 * offers apply. Mixing them in one segment produces messages that are wrong for half the
 * recipients, so {@code audienceType} is validated on resolve and immutable on a campaign.
 */
public enum AudienceType {
    COMMERCIAL,
    INDIVIDUAL
}
