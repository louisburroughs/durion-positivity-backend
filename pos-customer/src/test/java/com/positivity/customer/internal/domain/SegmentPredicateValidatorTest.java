package com.positivity.customer.internal.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.SegmentOperator;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Story #1137: predicates are validated at save time, not at resolve time. */
class SegmentPredicateValidatorTest {

    private static SegmentPredicate.Comparison comparison(
            String attribute, SegmentOperator operator, String... values) {
        return new SegmentPredicate.Comparison(attribute, operator, List.of(values));
    }

    @Test
    @DisplayName("accepts a well-formed predicate over the catalog")
    void acceptsValidPredicate() {
        SegmentPredicate predicate = new SegmentPredicate.And(List.of(
                comparison("party.accountTier", SegmentOperator.IN, "GOLD", "PLATINUM"),
                comparison("vehicle.count", SegmentOperator.GREATER_THAN_OR_EQUAL, "3")));

        assertThatCode(() -> SegmentPredicateValidator.validate(predicate, AudienceType.COMMERCIAL))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects an attribute outside the whitelisted catalog")
    void rejectsUnknownAttribute() {
        SegmentPredicate predicate = comparison("party.secretSauce", SegmentOperator.EQUALS, "yes");

        assertThatThrownBy(() -> SegmentPredicateValidator.validate(predicate, AudienceType.COMMERCIAL))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("Unknown segment attribute");
    }

    @Test
    @DisplayName("rejects an operator the attribute does not support")
    void rejectsUnsupportedOperator() {
        SegmentPredicate predicate = comparison("party.accountTier", SegmentOperator.GREATER_THAN, "GOLD");

        assertThatThrownBy(() -> SegmentPredicateValidator.validate(predicate, AudienceType.COMMERCIAL))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("is not valid for attribute");
    }

    @Test
    @DisplayName("rejects a commercial-only attribute on an individual audience")
    void rejectsCommercialAttributeForIndividuals() {
        SegmentPredicate predicate = comparison("billing.creditHold", SegmentOperator.IS_TRUE);

        assertThatThrownBy(() -> SegmentPredicateValidator.validate(predicate, AudienceType.INDIVIDUAL))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("applies only to commercial parties");
    }

    @Test
    @DisplayName("rejects a typo in an enum-backed value rather than silently matching nobody")
    void rejectsUnknownEnumValue() {
        SegmentPredicate predicate = comparison("party.accountTier", SegmentOperator.EQUALS, "PLATINIUM");

        assertThatThrownBy(() -> SegmentPredicateValidator.validate(predicate, AudienceType.COMMERCIAL))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("does not accept value");
    }

    @Test
    @DisplayName("rejects an empty AND, which would vacuously match everyone")
    void rejectsEmptyConjunction() {
        assertThatThrownBy(() -> SegmentPredicateValidator.validate(
                        new SegmentPredicate.And(List.of()), AudienceType.COMMERCIAL))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("at least one child predicate");
    }

    @Test
    @DisplayName("service-due validates as a boolean attribute on either audience (#1144)")
    void validatesServiceDueAttribute() {
        assertThatCode(() -> SegmentPredicateValidator.validate(
                        comparison("service.due", SegmentOperator.IS_TRUE), AudienceType.INDIVIDUAL))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> SegmentPredicateValidator.validate(
                        comparison("service.due", SegmentOperator.GREATER_THAN, "6"), AudienceType.COMMERCIAL))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("is not valid for attribute");
    }

    @Test
    @DisplayName("rejects a keyed attribute with no key")
    void rejectsKeyedAttributeWithoutKey() {
        SegmentPredicate predicate = comparison("party.externalIdentifier", SegmentOperator.IS_NOT_NULL);

        assertThatThrownBy(() -> SegmentPredicateValidator.validate(predicate, AudienceType.COMMERCIAL))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("requires a key");
    }

    @Test
    @DisplayName("rejects a tree nested past the depth ceiling")
    void rejectsExcessiveNesting() {
        SegmentPredicate predicate = comparison("party.accountTier", SegmentOperator.EQUALS, "GOLD");
        for (int i = 0; i < 12; i++) {
            predicate = new SegmentPredicate.Not(predicate);
        }
        SegmentPredicate deep = predicate;

        assertThatThrownBy(() -> SegmentPredicateValidator.validate(deep, AudienceType.COMMERCIAL))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("maximum depth");
    }
}
