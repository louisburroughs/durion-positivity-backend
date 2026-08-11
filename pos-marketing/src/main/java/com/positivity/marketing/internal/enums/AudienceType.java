package com.positivity.marketing.internal.enums;

/**
 * Which kind of party a campaign targets (Story #1146).
 *
 * <p>Immutable after campaign creation. Commercial and individual audiences differ in who
 * consents, which message tokens resolve, and which offers apply — changing it mid-flight
 * would leave a campaign bound to a segment and templates written for the other kind.
 */
public enum AudienceType {
    COMMERCIAL,
    INDIVIDUAL
}
