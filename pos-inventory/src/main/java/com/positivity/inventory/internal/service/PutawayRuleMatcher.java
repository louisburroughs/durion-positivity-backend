package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.exception.NoPutawayRuleMatchException;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.service.SkuCategoryLookup.SkuCategoryRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picks the putaway rule that governs each received line (issue #1514).
 *
 * <p>This replaces the pre-#1514 selection, which was
 * {@code findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc().get(0)} — one rule for every line of every
 * receipt, whatever the item was. Resolution is now per line item and runs the tiers in the strict
 * precedence {@link PutawayRuleMatchType#precedence()}: {@code SKU} beats {@code SUBCATEGORY} beats
 * {@code CATEGORY} beats {@code ANY}, and inside a tier the lowest {@code priority} wins.
 *
 * <p>Two queries serve a whole receipt regardless of how many lines it has: one for the enabled rule
 * set and one batched {@link SkuCategoryLookup#categoryRefOfAll} for the classifications. The rule
 * set is partitioned by tier in memory because it is small operator-authored configuration, and
 * because paying four queries per line for a large receipt buys nothing.
 *
 * <p>Matching is on ids, never on category names: names arrive as un-refreshed snapshots on product
 * facts (catalog publishes product facts, not category facts, so a rename needs a product replay),
 * whereas an id survives a rename.
 */
@Component
@RequiredArgsConstructor
public class PutawayRuleMatcher {

    private final PutawayRuleRepository putawayRuleRepository;
    private final SkuCategoryLookup skuCategoryLookup;

    /**
     * Resolves the governing rule for every supplied product id.
     *
     * @param productIds the received lines' product ids; duplicates are collapsed
     * @return every supplied product id mapped to its winning rule — never a partial map
     * @throws NoPutawayRuleMatchException when a product matches no tier at all, including
     *     {@code ANY}. Deliberately loud: the pre-#1514 code silently routed this case at a
     *     hardcoded default-location UUID that no environment has, which turned a configuration gap
     *     into a task pointing at a bin that does not exist.
     */
    @Transactional(readOnly = true)
    public @NonNull Map<UUID, PutawayRule> matchAll(@NonNull Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }

        Collection<UUID> distinctProductIds = new LinkedHashSet<>(productIds);
        Map<PutawayRuleMatchType, List<PutawayRule>> rulesByTier = rulesByTier();
        Map<String, SkuCategoryRef> refs = skuCategoryLookup.categoryRefOfAll(
                distinctProductIds.stream().map(UUID::toString).toList());

        Map<UUID, PutawayRule> matched = new HashMap<>(distinctProductIds.size());
        for (UUID productId : distinctProductIds) {
            matched.put(productId, match(productId, refs.get(productId.toString()), rulesByTier));
        }
        return Map.copyOf(matched);
    }

    /**
     * The winning rule for one line, resolved with its own two lookups.
     *
     * <p>Deliberately package-private: {@link #matchAll} is the only production entry point, and a
     * caller looping over this method would reintroduce exactly the per-line lookups {@code matchAll}
     * exists to avoid. It stays for the single-line case and so the precedence rules can be exercised
     * directly.
     */
    @Transactional(readOnly = true)
    @NonNull
    PutawayRule match(@NonNull UUID productId) {
        return match(
                productId, skuCategoryLookup.categoryRefOf(productId.toString()).orElse(null), rulesByTier());
    }

    private PutawayRule match(
            UUID productId, @Nullable SkuCategoryRef ref, Map<PutawayRuleMatchType, List<PutawayRule>> rulesByTier) {
        for (PutawayRuleMatchType tier : PutawayRuleMatchType.precedence()) {
            Optional<PutawayRule> winner = matchTier(tier, productId, ref, rulesByTier);
            if (winner.isPresent()) {
                return winner.get();
            }
        }
        throw new NoPutawayRuleMatchException(productId);
    }

    private Optional<PutawayRule> matchTier(
            PutawayRuleMatchType tier,
            UUID productId,
            @Nullable SkuCategoryRef ref,
            Map<PutawayRuleMatchType, List<PutawayRule>> rulesByTier) {
        List<PutawayRule> candidates = rulesByTier.getOrDefault(tier, List.of());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // ANY carries no value, so the first candidate in priority order is the answer.
        if (tier == PutawayRuleMatchType.ANY) {
            return Optional.of(candidates.get(0));
        }

        UUID target = targetFor(tier, productId, ref);
        if (target == null) {
            // Nothing to match on at this level — an unclassified SKU, or a product whose
            // subcategory has never been published. Fall through to the next tier rather than
            // treating "unknown" as "matches anything".
            return Optional.empty();
        }

        // candidates is already ordered by priority ascending, so the first hit is the winner.
        return candidates.stream()
                .filter(rule -> matchesId(rule.getMatchValue(), target))
                .findFirst();
    }

    private static @Nullable UUID targetFor(PutawayRuleMatchType tier, UUID productId, @Nullable SkuCategoryRef ref) {
        return switch (tier) {
            case SKU -> productId;
            case SUBCATEGORY -> ref == null ? null : ref.subcategoryId();
            case CATEGORY -> ref == null ? null : ref.categoryId();
            case ANY -> null;
        };
    }

    /**
     * Compares a rule's stored text value against a resolved id. The value is parsed as a UUID
     * rather than string-compared so that a rule authored with a differently-cased or
     * differently-spaced UUID still matches the id it names; a value that is not a UUID at all
     * matches nothing, which is what an unparseable match value should do.
     */
    private static boolean matchesId(@Nullable String matchValue, UUID target) {
        if (matchValue == null) {
            return false;
        }
        try {
            return UUID.fromString(matchValue.trim()).equals(target);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Enabled rules grouped by tier, each group preserving the repository's priority-ascending
     * order so the first element of a group is the most preferred rule in that tier.
     */
    private Map<PutawayRuleMatchType, List<PutawayRule>> rulesByTier() {
        Map<PutawayRuleMatchType, List<PutawayRule>> byTier = new EnumMap<>(PutawayRuleMatchType.class);
        for (PutawayRule rule : putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc()) {
            PutawayRuleMatchType tier = rule.getMatchType();
            if (tier == null) {
                // Defensive: the column is NOT NULL with a CHECK constraint, so this is only
                // reachable from a hand-built entity in a test. Skipping beats an NPE mid-receipt.
                continue;
            }
            byTier.computeIfAbsent(tier, key -> new ArrayList<>()).add(rule);
        }
        return byTier;
    }
}
