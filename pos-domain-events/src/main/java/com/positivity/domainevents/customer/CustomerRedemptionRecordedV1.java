package com.positivity.domainevents.customer;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a promotion redemption was recorded against a workorder (Story #1142).
 *
 * <p>Published by pos-customer on {@code customer.events.v1} with
 * {@code eventType = "customer.redemption.recorded"}. pos-marketing consumes it to attribute
 * conversions back to the campaign named by {@code campaignCode}, keyed on
 * {@code redemptionId} so replays cannot double-count.
 *
 * @param redemptionId redemption record identifier — the consumer's idempotency key
 * @param promotionId promotion that was redeemed
 * @param customerId party that redeemed it (also the envelope aggregateId)
 * @param workorderId workorder the redemption was applied to
 * @param promotionCode redeemed promotion code
 * @param campaignCode campaign that drove the redemption, when reached through one
 * @param discountAmount discount value applied
 */
public record CustomerRedemptionRecordedV1(
        @NonNull UUID redemptionId,
        @NonNull UUID promotionId,
        @NonNull UUID customerId,
        @NonNull UUID workorderId,
        @NonNull String promotionCode,
        @Nullable String campaignCode,
        @Nullable BigDecimal discountAmount) {

    public static final String EVENT_TYPE = "customer.redemption.recorded";
    public static final int SCHEMA_VERSION = 1;

    public CustomerRedemptionRecordedV1 {
        if (redemptionId == null) {
            throw new IllegalArgumentException("redemptionId must not be null");
        }
        if (customerId == null) {
            throw new IllegalArgumentException("customerId must not be null");
        }
    }
}
