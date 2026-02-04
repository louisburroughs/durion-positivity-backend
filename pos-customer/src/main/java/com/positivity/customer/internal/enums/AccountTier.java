package com.positivity.customer.internal.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Account tier levels for customer segmentation and service classification.
 * 
 * Tiers are typically assigned based on business rules such as:
 * - Annual revenue/spending
 * - Contract terms
 * - Service level agreements
 * - Account age and history
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/">Related
 *      backend issue</a>
 */
@Schema(description = "Account tier/classification level")
public enum AccountTier {

    /**
     * Standard tier - default for new accounts.
     * Basic service level with standard pricing.
     */
    @Schema(description = "Standard tier with basic service level")
    STANDARD,

    /**
     * Bronze tier - entry-level premium.
     * Enhanced support and minor discounts.
     */
    @Schema(description = "Bronze tier with enhanced support")
    BRONZE,

    /**
     * Silver tier - mid-level premium.
     * Priority support and moderate discounts.
     */
    @Schema(description = "Silver tier with priority support")
    SILVER,

    /**
     * Gold tier - high-value premium.
     * Premium support, significant discounts, and dedicated account management.
     */
    @Schema(description = "Gold tier with premium support and account management")
    GOLD,

    /**
     * Platinum tier - top-level premium.
     * White-glove service, maximum discounts, and strategic partnership benefits.
     */
    @Schema(description = "Platinum tier with white-glove service and strategic benefits")
    PLATINUM,

    /**
     * Enterprise tier - custom arrangement.
     * Customized service agreements and pricing for large organizations.
     */
    @Schema(description = "Enterprise tier with customized agreements")
    ENTERPRISE;

    /**
     * Get the display name for this tier.
     * 
     * @return formatted tier name
     */
    public String getDisplayName() {
        return switch (this) {
            case STANDARD -> "Standard";
            case BRONZE -> "Bronze";
            case SILVER -> "Silver";
            case GOLD -> "Gold";
            case PLATINUM -> "Platinum";
            case ENTERPRISE -> "Enterprise";
        };
    }

    /**
     * Check if this tier is premium (above STANDARD).
     * 
     * @return true if premium tier
     */
    public boolean isPremium() {
        return this != STANDARD;
    }

    /**
     * Get the tier level as an ordinal rank (higher = better).
     * 
     * @return tier level rank
     */
    public int getTierLevel() {
        return ordinal();
    }
}
