package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.exception.NoPutawayRuleMatchException;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.service.SkuCategoryLookup.SkuCategoryRef;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Rule selection is per line item and honours the tier precedence
 * SKU &gt; SUBCATEGORY &gt; CATEGORY &gt; ANY, lowest priority winning inside a tier (issue #1514).
 *
 * <p>The behaviour under test replaced {@code findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc().get(0)},
 * which gave every line of every receipt the same rule whatever the item was.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PutawayRuleMatcher")
class PutawayRuleMatcherTest {

    // Real seeded catalog ids, so the test and the V43 matrix speak the same language.
    private static final UUID TIRES_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000001");
    private static final UUID ELECTRICAL_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000004");
    private static final UUID BATTERIES_SUBCATEGORY = UUID.fromString("01960031-0000-7000-8000-00000000000e");
    private static final UUID PASSENGER_TIRES_SUBCATEGORY = UUID.fromString("01960031-0000-7000-8000-000000000003");

    private static final UUID BATTERY_PRODUCT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0001");
    private static final UUID TIRE_PRODUCT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0002");
    private static final UUID UNCATEGORISED_PRODUCT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0003");

    private static final UUID SKU_BIN = UUID.fromString("01960004-0001-7000-8000-000000000001");
    private static final UUID SUBCATEGORY_BIN = UUID.fromString("01960004-0001-7000-8000-000000000002");
    private static final UUID CATEGORY_BIN = UUID.fromString("01960004-0001-7000-8000-000000000003");
    private static final UUID ANY_BIN = UUID.fromString("01960004-0001-7000-8000-000000000004");
    private static final UUID TIE_BREAK_BIN = UUID.fromString("01960004-0001-7000-8000-000000000005");

    @Mock
    private PutawayRuleRepository putawayRuleRepository;

    @Mock
    private SkuCategoryLookup skuCategoryLookup;

    private PutawayRuleMatcher matcher;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        matcher = new PutawayRuleMatcher(putawayRuleRepository, skuCategoryLookup);
    }

    private static PutawayRule rule(int priority, PutawayRuleMatchType matchType, UUID matchValue, UUID destination) {
        return PutawayRule.builder()
                .ruleId(UUID.randomUUID())
                .priority(priority)
                .matchType(matchType)
                .matchValue(matchValue == null ? null : matchValue.toString())
                .destinationLocationId(destination)
                .isEnabled(true)
                .build();
    }

    /**
     * Stubs the repository the way the real derived query behaves: enabled rules, priority
     * ascending. The matcher relies on that ordering to pick the winner inside a tier, so the stub
     * has to reproduce it rather than return the declaration order.
     */
    private void givenEnabledRules(PutawayRule... rules) {
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(java.util.Arrays.stream(rules)
                        .sorted(Comparator.comparingInt(PutawayRule::getPriority))
                        .toList());
    }

    private void givenCategories(Map<UUID, SkuCategoryRef> refs) {
        Map<String, SkuCategoryRef> byString = refs.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue));
        when(skuCategoryLookup.categoryRefOfAll(anyCollection())).thenReturn(byString);
    }

    private static SkuCategoryRef battery() {
        return new SkuCategoryRef(ELECTRICAL_CATEGORY, "Electrical System", BATTERIES_SUBCATEGORY, "Batteries");
    }

    private static SkuCategoryRef tire() {
        return new SkuCategoryRef(TIRES_CATEGORY, "Tires & Wheels", PASSENGER_TIRES_SUBCATEGORY, "Passenger Car Tires");
    }

    @Nested
    @DisplayName("tier precedence")
    class TierPrecedence {

        @Test
        @DisplayName("a SKU rule beats a subcategory, category and ANY rule for the same item")
        void skuRuleWins() {
            givenEnabledRules(
                    rule(1, PutawayRuleMatchType.ANY, null, ANY_BIN),
                    rule(2, PutawayRuleMatchType.CATEGORY, ELECTRICAL_CATEGORY, CATEGORY_BIN),
                    rule(3, PutawayRuleMatchType.SUBCATEGORY, BATTERIES_SUBCATEGORY, SUBCATEGORY_BIN),
                    rule(4, PutawayRuleMatchType.SKU, BATTERY_PRODUCT, SKU_BIN));
            givenCategories(Map.of(BATTERY_PRODUCT, battery()));

            assertThat(matcher.matchAll(List.of(BATTERY_PRODUCT)))
                    .extractingByKey(BATTERY_PRODUCT)
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(SKU_BIN);
        }

        @Test
        @DisplayName("precedence beats priority: the SKU rule wins even with the worst priority number")
        void precedenceOutranksPriority() {
            givenEnabledRules(
                    rule(1, PutawayRuleMatchType.ANY, null, ANY_BIN),
                    rule(999, PutawayRuleMatchType.SKU, BATTERY_PRODUCT, SKU_BIN));
            givenCategories(Map.of(BATTERY_PRODUCT, battery()));

            assertThat(matcher.matchAll(List.of(BATTERY_PRODUCT)).get(BATTERY_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(SKU_BIN);
        }

        @Test
        @DisplayName("a subcategory rule beats a category rule — the Batteries containment case")
        void subcategoryRuleBeatsCategoryRule() {
            givenEnabledRules(
                    rule(1, PutawayRuleMatchType.CATEGORY, ELECTRICAL_CATEGORY, CATEGORY_BIN),
                    rule(2, PutawayRuleMatchType.SUBCATEGORY, BATTERIES_SUBCATEGORY, SUBCATEGORY_BIN));
            givenCategories(Map.of(BATTERY_PRODUCT, battery()));

            assertThat(matcher.matchAll(List.of(BATTERY_PRODUCT)).get(BATTERY_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(SUBCATEGORY_BIN);
        }

        @Test
        @DisplayName("a category rule beats ANY")
        void categoryRuleBeatsAny() {
            givenEnabledRules(
                    rule(1, PutawayRuleMatchType.ANY, null, ANY_BIN),
                    rule(2, PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY, CATEGORY_BIN));
            givenCategories(Map.of(TIRE_PRODUCT, tire()));

            assertThat(matcher.matchAll(List.of(TIRE_PRODUCT)).get(TIRE_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(CATEGORY_BIN);
        }

        @Test
        @DisplayName("a rule in a tier that does not match the item is skipped, not applied")
        void nonMatchingRuleInAHigherTierIsSkipped() {
            givenEnabledRules(
                    // A SKU rule for a different product must not capture this line.
                    rule(1, PutawayRuleMatchType.SKU, TIRE_PRODUCT, SKU_BIN),
                    rule(2, PutawayRuleMatchType.CATEGORY, ELECTRICAL_CATEGORY, CATEGORY_BIN));
            givenCategories(Map.of(BATTERY_PRODUCT, battery()));

            assertThat(matcher.matchAll(List.of(BATTERY_PRODUCT)).get(BATTERY_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(CATEGORY_BIN);
        }
    }

    @Nested
    @DisplayName("priority tie-break within a tier")
    class PriorityWithinTier {

        @Test
        @DisplayName("the lowest priority number wins among category rules that both match")
        void lowestPriorityWinsInTier() {
            givenEnabledRules(
                    rule(20, PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY, TIE_BREAK_BIN),
                    rule(10, PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY, CATEGORY_BIN));
            givenCategories(Map.of(TIRE_PRODUCT, tire()));

            assertThat(matcher.matchAll(List.of(TIRE_PRODUCT)).get(TIRE_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(CATEGORY_BIN);
        }

        @Test
        @DisplayName("#1514 - equal-priority rules in a tier resolve the same way every time")
        void equalPriorityTiesAreStable() {
            PutawayRule first = rule(10, PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY, CATEGORY_BIN);
            PutawayRule second = rule(10, PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY, TIE_BREAK_BIN);
            // The repository orders by priority then ruleId, so the tie is broken by ruleId and the
            // same answer comes back whatever order the rows physically arrive in.
            when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                    .thenReturn(java.util.stream.Stream.of(first, second)
                            .sorted(Comparator.comparingInt(PutawayRule::getPriority)
                                    .thenComparing(PutawayRule::getRuleId))
                            .toList());
            givenCategories(Map.of(TIRE_PRODUCT, tire()));

            UUID expected = first.getRuleId().compareTo(second.getRuleId()) < 0 ? CATEGORY_BIN : TIE_BREAK_BIN;
            assertThat(matcher.matchAll(List.of(TIRE_PRODUCT)).get(TIRE_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the lowest priority number wins among ANY rules")
        void lowestPriorityWinsAmongAnyRules() {
            givenEnabledRules(
                    rule(50, PutawayRuleMatchType.ANY, null, TIE_BREAK_BIN),
                    rule(5, PutawayRuleMatchType.ANY, null, ANY_BIN));
            givenCategories(Map.of());

            assertThat(matcher.matchAll(List.of(UNCATEGORISED_PRODUCT)).get(UNCATEGORISED_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(ANY_BIN);
        }
    }

    @Nested
    @DisplayName("multi-line receipts")
    class MultiLineReceipts {

        @Test
        @DisplayName("two lines of one receipt resolve to different destinations — the get(0) bug is gone")
        void differentLinesGetDifferentDestinations() {
            givenEnabledRules(
                    rule(1, PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY, CATEGORY_BIN),
                    rule(2, PutawayRuleMatchType.SUBCATEGORY, BATTERIES_SUBCATEGORY, SUBCATEGORY_BIN),
                    rule(3, PutawayRuleMatchType.ANY, null, ANY_BIN));
            givenCategories(Map.of(TIRE_PRODUCT, tire(), BATTERY_PRODUCT, battery()));

            Map<UUID, PutawayRule> matched =
                    matcher.matchAll(List.of(TIRE_PRODUCT, BATTERY_PRODUCT, UNCATEGORISED_PRODUCT));

            assertThat(matched.get(TIRE_PRODUCT).getDestinationLocationId()).isEqualTo(CATEGORY_BIN);
            assertThat(matched.get(BATTERY_PRODUCT).getDestinationLocationId()).isEqualTo(SUBCATEGORY_BIN);
            assertThat(matched.get(UNCATEGORISED_PRODUCT).getDestinationLocationId())
                    .isEqualTo(ANY_BIN);
        }

        @Test
        @DisplayName("a multi-line receipt costs one rule read and one batched category read")
        void resolvesAWholeReceiptInTwoQueries() {
            givenEnabledRules(rule(1, PutawayRuleMatchType.ANY, null, ANY_BIN));
            givenCategories(Map.of());

            matcher.matchAll(List.of(TIRE_PRODUCT, BATTERY_PRODUCT, UNCATEGORISED_PRODUCT));

            verify(putawayRuleRepository).findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc();
            verify(skuCategoryLookup).categoryRefOfAll(anyCollection());
        }

        @Test
        @DisplayName("a repeated product id is resolved once and still answered for")
        void collapsesDuplicateProductIds() {
            givenEnabledRules(rule(1, PutawayRuleMatchType.ANY, null, ANY_BIN));
            givenCategories(Map.of());

            assertThat(matcher.matchAll(List.of(TIRE_PRODUCT, TIRE_PRODUCT))).hasSize(1);
        }

        @Test
        @DisplayName("no lines means no queries and no rules")
        void emptyReceiptResolvesToAnEmptyMap() {
            assertThat(matcher.matchAll(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("the ANY terminal fallback")
    class TerminalFallback {

        @Test
        @DisplayName("a brand-new uncategorised SKU still lands, via the ANY rule")
        void uncategorisedSkuLandsViaAnyRule() {
            givenEnabledRules(
                    rule(1, PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY, CATEGORY_BIN),
                    rule(2, PutawayRuleMatchType.ANY, null, ANY_BIN));
            // The replica has never heard of this product, so it is absent from the batch answer.
            givenCategories(Map.of());

            assertThat(matcher.matchAll(List.of(UNCATEGORISED_PRODUCT)).get(UNCATEGORISED_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(ANY_BIN);
        }

        @Test
        @DisplayName("a product with a category but no subcategory falls through to the category tier")
        void nullSubcategoryFallsThroughRatherThanMatchingAnything() {
            givenEnabledRules(
                    rule(1, PutawayRuleMatchType.SUBCATEGORY, BATTERIES_SUBCATEGORY, SUBCATEGORY_BIN),
                    rule(2, PutawayRuleMatchType.CATEGORY, ELECTRICAL_CATEGORY, CATEGORY_BIN));
            givenCategories(
                    Map.of(BATTERY_PRODUCT, new SkuCategoryRef(ELECTRICAL_CATEGORY, "Electrical System", null, null)));

            assertThat(matcher.matchAll(List.of(BATTERY_PRODUCT)).get(BATTERY_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(CATEGORY_BIN);
        }

        @Test
        @DisplayName("with no rule set at all the matcher raises a configuration error, not a fake destination")
        void noRulesAtAllThrows() {
            givenEnabledRules();
            givenCategories(Map.of());
            List<UUID> lines = List.of(UNCATEGORISED_PRODUCT);

            assertThatThrownBy(() -> matcher.matchAll(lines))
                    .isInstanceOf(NoPutawayRuleMatchException.class)
                    .hasMessageContaining(UNCATEGORISED_PRODUCT.toString())
                    .hasMessageContaining("ANY rule");
        }

        @Test
        @DisplayName("a rule set with no ANY rule dead-ends an unmatched line loudly")
        void missingAnyRuleThrowsForAnUnmatchedLine() {
            givenEnabledRules(rule(1, PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY, CATEGORY_BIN));
            givenCategories(Map.of());
            List<UUID> lines = List.of(UNCATEGORISED_PRODUCT);

            assertThatThrownBy(() -> matcher.matchAll(lines)).isInstanceOf(NoPutawayRuleMatchException.class);
        }

        @Test
        @DisplayName("a disabled ANY rule is not a fallback: the repository never offers it")
        void disabledRulesAreNeverCandidates() {
            // findAllByIsEnabledTrueOrderByPriorityAsc filters at the query, so a disabled rule
            // simply is not in the candidate set. Asserted so that widening the query would fail.
            givenEnabledRules();
            givenCategories(Map.of());
            List<UUID> lines = List.of(UNCATEGORISED_PRODUCT);

            assertThatThrownBy(() -> matcher.matchAll(lines)).isInstanceOf(NoPutawayRuleMatchException.class);
        }
    }

    @Nested
    @DisplayName("match value handling")
    class MatchValueHandling {

        @Test
        @DisplayName("an unparseable match value matches nothing rather than everything")
        void unparseableMatchValueMatchesNothing() {
            PutawayRule broken = PutawayRule.builder()
                    .ruleId(UUID.randomUUID())
                    .priority(1)
                    .matchType(PutawayRuleMatchType.CATEGORY)
                    .matchValue("not-a-uuid")
                    .destinationLocationId(CATEGORY_BIN)
                    .isEnabled(true)
                    .build();
            givenEnabledRules(broken, rule(2, PutawayRuleMatchType.ANY, null, ANY_BIN));
            givenCategories(Map.of(TIRE_PRODUCT, tire()));

            assertThat(matcher.matchAll(List.of(TIRE_PRODUCT)).get(TIRE_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(ANY_BIN);
        }

        @Test
        @DisplayName("a match value with surrounding whitespace still matches its id")
        void matchValueIsTrimmedBeforeComparison() {
            PutawayRule padded = PutawayRule.builder()
                    .ruleId(UUID.randomUUID())
                    .priority(1)
                    .matchType(PutawayRuleMatchType.CATEGORY)
                    .matchValue("  " + TIRES_CATEGORY + "  ")
                    .destinationLocationId(CATEGORY_BIN)
                    .isEnabled(true)
                    .build();
            givenEnabledRules(padded, rule(2, PutawayRuleMatchType.ANY, null, ANY_BIN));
            givenCategories(Map.of(TIRE_PRODUCT, tire()));

            assertThat(matcher.matchAll(List.of(TIRE_PRODUCT)).get(TIRE_PRODUCT))
                    .extracting(PutawayRule::getDestinationLocationId)
                    .isEqualTo(CATEGORY_BIN);
        }
    }

    @Nested
    @DisplayName("single-line match")
    class SingleLineMatch {

        @Test
        @DisplayName("match(productId) applies the same precedence as the batch form")
        void singleLineUsesTheSamePrecedence() {
            givenEnabledRules(
                    rule(1, PutawayRuleMatchType.ANY, null, ANY_BIN),
                    rule(2, PutawayRuleMatchType.SUBCATEGORY, BATTERIES_SUBCATEGORY, SUBCATEGORY_BIN));
            when(skuCategoryLookup.categoryRefOf(BATTERY_PRODUCT.toString()))
                    .thenReturn(java.util.Optional.of(battery()));

            assertThat(matcher.match(BATTERY_PRODUCT).getDestinationLocationId())
                    .isEqualTo(SUBCATEGORY_BIN);
        }
    }
}
