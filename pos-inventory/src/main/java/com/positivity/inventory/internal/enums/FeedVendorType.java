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

    /**
     * The one key a feed vendor is stored and looked up under (CAP:550 S36, #2525): trimmed and lower-cased for both
     * feeds, so a manufacturer UUID in any case and a distributor id whose case varies between feed and legacy
     * {@code uuid::text} rows meet the same mapping. Every caller (selection, mapping writes, resolution, the
     * unmapped scan) goes through here.
     */
    public static String canonical(String feedVendorRef) {
        return feedVendorRef == null ? "" : feedVendorRef.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
