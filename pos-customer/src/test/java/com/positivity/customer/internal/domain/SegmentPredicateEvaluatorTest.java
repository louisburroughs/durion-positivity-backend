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
                "Acme Towing");
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
