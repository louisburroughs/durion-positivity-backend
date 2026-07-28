package com.positivity.marketing.internal.domain;

import com.positivity.marketing.internal.enums.AudienceType;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The closed vocabulary of substitution tokens a message template may use (Story #1147).
 *
 * <p>Tokens are validated against this catalog at save time. The alternative — substituting
 * whatever the author typed and leaving unknown tokens to render as blanks — fails in front of
 * the customer, at send time, across the whole audience at once. A typo should cost a
 * validation error, not a batch of messages reading "Hi ,".
 *
 * <p>Availability is scoped by audience because the vocabularies genuinely differ:
 * {@code accountName} is meaningless for an individual, and a fleet has no single
 * {@code vehicleMake}.
 */
public enum TemplateToken {
    ACCOUNT_NAME("accountName", AudienceType.COMMERCIAL),
    ACCOUNT_TIER("accountTier", AudienceType.COMMERCIAL),
    CONTACT_FIRST_NAME("contactFirstName", null),
    CUSTOMER_NUMBER("customerNumber", null),
    VEHICLE_MAKE("vehicleMake", AudienceType.INDIVIDUAL),
    VEHICLE_MODEL("vehicleModel", AudienceType.INDIVIDUAL),
    VEHICLE_YEAR("vehicleYear", AudienceType.INDIVIDUAL),
    CAMPAIGN_CODE("campaignCode", null),
    OFFER_CODE("offerCode", null),
    SHOP_NAME("shopName", null),
    UNSUBSCRIBE_URL("unsubscribeUrl", null);

    private final String tokenName;

    /** The audience this token is exclusive to; null means available to both. */
    private final AudienceType exclusiveTo;

    TemplateToken(String tokenName, AudienceType exclusiveTo) {
        this.tokenName = tokenName;
        this.exclusiveTo = exclusiveTo;
    }

    public String tokenName() {
        return tokenName;
    }

    public boolean availableFor(AudienceType audienceType) {
        return exclusiveTo == null || exclusiveTo == audienceType;
    }

    public static Optional<TemplateToken> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(token -> token.tokenName.toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }

    public static Set<String> namesFor(AudienceType audienceType) {
        return Arrays.stream(values())
                .filter(token -> token.availableFor(audienceType))
                .map(TemplateToken::tokenName)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
