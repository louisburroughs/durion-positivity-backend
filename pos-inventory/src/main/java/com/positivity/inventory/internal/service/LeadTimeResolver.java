package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.service.InventoryLeadTimeService;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the replenishment lead time (in days) for one policy — the horizon the
 * forecast-aware orderpoint trigger projects to (odoo-parity F2, issue #1040; decision D-4).
 *
 * <p><b>Precedence (D-4):</b> policy {@code leadTimeDaysOverride} (parity-F3, issue #1041)
 * → vendor-feed lead time → configurable default. The override is an explicit operator
 * decision on the policy and therefore beats any feed estimate; policies without one fall
 * through to the feed chain unchanged.
 *
 * <p><b>Feed lookup:</b> {@link InventoryLeadTimeService} aggregates MIN/MAX day estimates
 * from normalized vendor feeds, keyed by product UUID. The MAX estimate is used —
 * conservative on purpose: projecting to the worst-case arrival date means supply that might
 * land later than the horizon never masks a breach, so we replenish early rather than
 * stock out (Odoo equivalently projects to the orderpoint lead date, and under-estimating
 * lead time is the failure mode that costs sales). Policies whose SKU is not a product UUID
 * or that have no feed rows fall back to the default.
 *
 * <p><b>Default:</b> {@code pos.inventory.replenishment.default-lead-time-days} (0 when
 * unset) — a horizon of "now", which makes the projection equal current on-hand plus
 * already-due supply minus open demand.
 */
@Component
public class LeadTimeResolver {

    /** Where the resolved lead time came from; recorded in decision logs. */
    public enum LeadTimeSource {
        POLICY_OVERRIDE,
        FEED,
        DEFAULT
    }

    /** Resolved lead time in whole days plus its provenance. */
    public record ResolvedLeadTime(int days, @NonNull LeadTimeSource source) {}

    private final InventoryLeadTimeService inventoryLeadTimeService;
    private final int defaultLeadTimeDays;

    public LeadTimeResolver(
            InventoryLeadTimeService inventoryLeadTimeService,
            @Value("${pos.inventory.replenishment.default-lead-time-days:0}") int defaultLeadTimeDays) {
        this.inventoryLeadTimeService = inventoryLeadTimeService;
        this.defaultLeadTimeDays = Math.max(0, defaultLeadTimeDays);
    }

    /** Resolves the lead time for one policy per the D-4 precedence chain. */
    public @NonNull ResolvedLeadTime resolve(@NonNull ReplenishmentPolicy policy) {
        // D-4 highest precedence (parity-F3, #1041): an explicit per-policy override.
        Integer overrideDays = policy.getLeadTimeDaysOverride();
        if (overrideDays != null) {
            return new ResolvedLeadTime(Math.max(0, overrideDays), LeadTimeSource.POLICY_OVERRIDE);
        }
        Integer feedDays = feedLeadTimeDays(policy);
        if (feedDays != null) {
            return new ResolvedLeadTime(Math.max(0, feedDays), LeadTimeSource.FEED);
        }
        return new ResolvedLeadTime(defaultLeadTimeDays, LeadTimeSource.DEFAULT);
    }

    private @Nullable Integer feedLeadTimeDays(ReplenishmentPolicy policy) {
        UUID productId = parseUuid(policy.getItemSKU());
        if (productId == null) {
            return null;
        }
        // Non-throwing lookup: absence is a normal outcome here, and an exception through
        // the transactional proxy would mark the enclosing scan transaction rollback-only.
        return inventoryLeadTimeService
                .findLeadTime(productId, policy.getLocationId(), null)
                // MAX estimate on purpose — see class javadoc (conservative horizon).
                .map(view -> view.getMaxDays() != null ? view.getMaxDays() : view.getMinDays())
                .orElse(null);
    }

    private static @Nullable UUID parseUuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
