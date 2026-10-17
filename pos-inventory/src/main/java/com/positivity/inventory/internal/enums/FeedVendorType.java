package com.positivity.inventory.internal.enums;

/**
 * Which normalized feed a feed vendor comes from (CAP:550 S36, #2525): a manufacturer ({@code normalized_availability},
 * keyed by {@code manufacturerId}, a UUID) or a distributor ({@code distributor_normalized_inventory}, keyed by
 * {@code distributorId}, free text). The names match {@link SuggestedVendorType}.
 */
public enum FeedVendorType {
    MANUFACTURER,
    DISTRIBUTOR;

    /** The feed type of a suggestion's {@link SuggestedVendorType}, which names the same two feeds. */
    public static FeedVendorType of(SuggestedVendorType suggestedVendorType) {
        return valueOf(suggestedVendorType.name());
    }
}
