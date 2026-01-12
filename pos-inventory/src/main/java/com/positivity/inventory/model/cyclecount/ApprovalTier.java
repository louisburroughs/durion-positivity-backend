package com.positivity.inventory.model.cyclecount;

/**
 * Two-tier approval hierarchy for inventory adjustments.
 * 
 * <p>Tiers are evaluated in order:
 * <ul>
 *   <li>TIER_1_MANAGER: For moderate-risk adjustments (e.g., $100-$1000)</li>
 *   <li>TIER_2_DIRECTOR: For high-risk adjustments (e.g., &gt;$1000 or &gt;25% variance)</li>
 * </ul>
 */
public enum ApprovalTier {
    /**
     * Manager-level approval for moderate-risk adjustments.
     * Typical threshold: variance value between $100 and $1000.
     */
    TIER_1_MANAGER,
    
    /**
     * Director/Controller-level approval for high-risk adjustments.
     * Typical threshold: variance value &gt; $1000 or percentage variance &gt; 25%.
     */
    TIER_2_DIRECTOR
}
