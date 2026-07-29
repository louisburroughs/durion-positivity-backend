package com.positivity.customer.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.internal.enums.SegmentOperator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Story #1137: predicate evaluation against a party's attribute snapshot. */
class SegmentPredicateEvaluatorTest {

    private static final UUID PARTY = UUID.fromString("01960003-0000-7000-8000-000000000001");
    private static final UUID TAG_FLEET = UUID.fromString("01960003-0000-7000-8000-0000000000a1");
    private static final UUID TAG_VIP = UUID.fromString("01960003-0000-7000-8000-0000000000a2");

    private static PartyAttributes party(Boolean creditHold, Set<UUID> tags, long vehicleCount) {
        return new PartyAttributes(
                PARTY,
                "COMMERCIAL",
                "GOLD",
                "ACTIVE",
                false,
                Map.of("QBO", "CUST-77"),
                tags,
                Boolean.FALSE,
                creditHold,
                "NET30",
                "OPT_IN",
                "UNSET",
                Set.of("Ford"),
                Set.of("Transit"),
                Set.of(2019),
                true,
                vehicleCount,
                "Acme Towing",
                null,
                false,
                null);
    }

    private static PartyAttributes serviceParty(
            Integer monthsSinceLastService, boolean hasServiceHistory, Integer daysSinceDeclinedService) {
        return new PartyAttributes(
                PARTY,
                "COMMERCIAL",
                "GOLD",
                "ACTIVE",
                false,
                Map.of(),
                Set.of(),
                Boolean.FALSE,
                Boolean.FALSE,
                "NET30",
                "OPT_IN",
                "UNSET",
                Set.of(),
                Set.of(),
                Set.of(),
                true,
                1,
                "Acme Towing",
                monthsSinceLastService,
                hasServiceHistory,
                daysSinceDeclinedService);
    }

    @Test
    @DisplayName("months-since-last-service matches a numeric predicate; unknown never matches")
    void matchesServiceAgePredicate() {
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("service.monthsSinceLast", SegmentOperator.GREATER_THAN, "6"),
                        serviceParty(8, true, null)))
                .isTrue();
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("service.monthsSinceLast", SegmentOperator.GREATER_THAN, "6"),
                        serviceParty(3, true, null)))
                .isFalse();
        // No service history: unknown, not zero — a "> 6 months" win-back predicate must not match.
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("service.monthsSinceLast", SegmentOperator.GREATER_THAN, "6"),
                        serviceParty(null, false, null)))
                .isFalse();
    }

    @Test
    @DisplayName("has-service-history and declined-recency predicates evaluate")
    void matchesServiceHistoryAndDeclinePredicates() {
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("service.hasHistory", SegmentOperator.IS_TRUE), serviceParty(2, true, null)))
                .isTrue();
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("service.daysSinceDeclined", SegmentOperator.LESS_THAN_OR_EQUAL, "30"),
                        serviceParty(2, true, 10)))
                .isTrue();
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("service.daysSinceDeclined", SegmentOperator.LESS_THAN_OR_EQUAL, "30"),
                        serviceParty(2, true, null)))
                .isFalse();
    }

    private static SegmentPredicate.Comparison comparison(
            String attribute, SegmentOperator operator, String... values) {
        return new SegmentPredicate.Comparison(attribute, operator, List.of(values));
    }

    @Test
    @DisplayName("matches an enum attribute case-insensitively")
    void matchesEnumAttribute() {
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("party.accountTier", SegmentOperator.EQUALS, "gold"), party(false, Set.of(), 4)))
                .isTrue();
    }

    @Test
    @DisplayName("AND requires every branch, OR requires one")
    void combinesBranches() {
        SegmentPredicate both = new SegmentPredicate.And(List.of(
                comparison("party.accountTier", SegmentOperator.EQUALS, "GOLD"),
                comparison("vehicle.count", SegmentOperator.GREATER_THAN, "10")));
        SegmentPredicate either = new SegmentPredicate.Or(List.of(
                comparison("party.accountTier", SegmentOperator.EQUALS, "GOLD"),
                comparison("vehicle.count", SegmentOperator.GREATER_THAN, "10")));

        assertThat(SegmentPredicateEvaluator.matches(both, party(false, Set.of(), 4)))
                .isFalse();
        assertThat(SegmentPredicateEvaluator.matches(either, party(false, Set.of(), 4)))
                .isTrue();
    }

    @Test
    @DisplayName("CONTAINS_ALL needs every tag; CONTAINS_ANY needs one")
    void matchesTags() {
        PartyAttributes fleetOnly = party(false, Set.of(TAG_FLEET), 4);

        assertThat(SegmentPredicateEvaluator.matches(
                        comparison(
                                "party.tags", SegmentOperator.CONTAINS_ALL, TAG_FLEET.toString(), TAG_VIP.toString()),
                        fleetOnly))
                .isFalse();
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison(
                                "party.tags", SegmentOperator.CONTAINS_ANY, TAG_FLEET.toString(), TAG_VIP.toString()),
                        fleetOnly))
                .isTrue();
    }

    @Test
    @DisplayName("a null flag is unknown, not false — an unknown credit hold never matches IS_FALSE")
    void treatsNullFlagAsUnknown() {
        PartyAttributes unknown = party(null, Set.of(), 1);

        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("billing.creditHold", SegmentOperator.IS_FALSE), unknown))
                .isFalse();
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("billing.creditHold", SegmentOperator.IS_TRUE), unknown))
                .isFalse();
    }

    @Test
    @DisplayName("a keyed external identifier resolves by its key")
    void matchesKeyedAttribute() {
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("party.externalIdentifier[QBO]", SegmentOperator.EQUALS, "CUST-77"),
                        party(false, Set.of(), 1)))
                .isTrue();
        assertThat(SegmentPredicateEvaluator.matches(
                        comparison("party.externalIdentifier[SAP]", SegmentOperator.IS_NULL),
                        party(false, Set.of(), 1)))
                .isTrue();
    }
}
