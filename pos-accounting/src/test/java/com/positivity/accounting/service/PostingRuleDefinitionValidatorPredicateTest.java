package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.accounting.internal.exception.UnbalancedRulesException;
import com.positivity.accounting.internal.exception.UnbalancedRulesException.RuleViolation;
import com.positivity.accounting.internal.service.PostingRuleDefinitionValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for publish-time condition-predicate validation (story E2,
 * issue #946): every non-catch-all condition string must parse under the
 * whitelist grammar or the publish is rejected with
 * {@link UnbalancedRulesException} carrying a
 * {@code conditions[i].condition} locator. Malformed predicates therefore
 * fail at publish time, never at evaluation time.
 */
@DisplayName("PostingRuleDefinitionValidator condition predicates (E2 publish validation)")
class PostingRuleDefinitionValidatorPredicateTest {

    private static final String ACCOUNT = "00000000-0000-0000-0000-0000000000a1";

    private static String lines() {
        return "[{\"glAccountId\":\"" + ACCOUNT + "\",\"side\":\"DEBIT\",\"amountField\":\"payload.amount\"},"
                + "{\"glAccountId\":\"" + ACCOUNT + "\",\"side\":\"CREDIT\",\"amountField\":\"payload.amount\"}]";
    }

    private static String rulesWithCondition(String conditionJsonValue) {
        return "{\"conditions\":[{\"condition\":" + conditionJsonValue + ",\"lines\":" + lines() + "}]}";
    }

    private static String rulesWithConditions(String... conditionJsonValues) {
        StringBuilder sb = new StringBuilder("{\"conditions\":[");
        for (int i = 0; i < conditionJsonValues.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"condition\":")
                    .append(conditionJsonValues[i])
                    .append(",\"lines\":")
                    .append(lines())
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    private static UnbalancedRulesException validateExpectingViolation(String rulesDefinition) {
        try {
            PostingRuleDefinitionValidator.validateForPublish(rulesDefinition);
        } catch (UnbalancedRulesException e) {
            return e;
        }
        throw new AssertionError("Expected UnbalancedRulesException");
    }

    // ── Valid predicates ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Valid predicates pass publish validation")
    class ValidPredicates {

        @Test
        @DisplayName("pre-E2 eventType equality predicate passes unchanged")
        void legacyEventTypePredicatePasses() {
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(
                            rulesWithCondition("\"eventType == 'billing.invoicePosted'\"")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("catch-all forms pass: absent, null, blank, wildcard")
        void catchAllFormsPass() {
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(
                            "{\"conditions\":[{\"lines\":" + lines() + "}]}"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(rulesWithCondition("null")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(rulesWithCondition("\"\"")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(rulesWithCondition("\"  \"")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(rulesWithCondition("\"*\"")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("payload predicates with all whitelist ops and conjunction pass")
        void newGrammarPredicatesPass() {
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(
                            rulesWithCondition("\"payload.paymentMethod == 'CASH'\"")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(
                            rulesWithCondition("\"payload.amount >= 100.00 && eventType == 'PAYMENT_RECEIVED'\"")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(
                            rulesWithCondition("\"payload.totals.tax < -0.5 && payload.qty != 3\"")))
                    .doesNotThrowAnyException();
        }
    }

    // ── Rejections ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Malformed predicates are rejected with a conditions[i].condition locator")
    class Rejections {

        @Test
        @DisplayName("syntactically malformed predicate is rejected")
        void malformedPredicateRejected() {
            UnbalancedRulesException e = validateExpectingViolation(rulesWithCondition("\"this is not a predicate\""));

            assertThat(e.getViolations()).singleElement().satisfies(violation -> {
                assertThat(violation.field()).isEqualTo("conditions[0].condition");
                assertThat(violation.message()).contains("condition predicate is invalid");
                assertThat(violation.message()).contains("at position");
            });
        }

        @Test
        @DisplayName("ordering operator with a string literal is rejected")
        void orderingOpWithStringLiteralRejected() {
            UnbalancedRulesException e = validateExpectingViolation(rulesWithCondition("\"payload.amount > 'high'\""));

            assertThat(e.getViolations()).singleElement().satisfies(violation -> {
                assertThat(violation.field()).isEqualTo("conditions[0].condition");
                assertThat(violation.message()).contains("numeric literal");
            });
        }

        @Test
        @DisplayName("trailing garbage after a valid clause is rejected")
        void trailingGarbageRejected() {
            UnbalancedRulesException e = validateExpectingViolation(rulesWithCondition("\"eventType == 'x' bogus\""));

            assertThat(e.getViolations()).singleElement().satisfies(violation -> {
                assertThat(violation.field()).isEqualTo("conditions[0].condition");
                assertThat(violation.message()).contains("trailing");
            });
        }

        @Test
        @DisplayName("unbalanced quote is rejected")
        void unbalancedQuoteRejected() {
            UnbalancedRulesException e = validateExpectingViolation(rulesWithCondition("\"payload.method == 'CASH\""));

            assertThat(e.getViolations()).singleElement().satisfies(violation -> {
                assertThat(violation.field()).isEqualTo("conditions[0].condition");
                assertThat(violation.message()).contains("unterminated string literal");
            });
        }

        @Test
        @DisplayName("non-string condition node is rejected")
        void nonStringConditionRejected() {
            UnbalancedRulesException e = validateExpectingViolation(rulesWithCondition("42"));

            assertThat(e.getViolations()).singleElement().satisfies(violation -> {
                assertThat(violation.field()).isEqualTo("conditions[0].condition");
                assertThat(violation.message()).contains("must be a string predicate");
            });
        }

        @Test
        @DisplayName("violations aggregate across conditions with correct indexes")
        void violationsAggregateAcrossConditions() {
            UnbalancedRulesException e = validateExpectingViolation(rulesWithConditions(
                    "\"payload.amount > 'high'\"", "\"eventType == 'valid.event'\"", "\"&& broken\""));

            assertThat(e.getViolations())
                    .extracting(RuleViolation::field)
                    .containsExactly("conditions[0].condition", "conditions[2].condition");
        }

        @Test
        @DisplayName("predicate violations aggregate with E1 split-group violations in one exception")
        void predicateAndSplitViolationsAggregate() {
            String splitLines = "[{\"glAccountId\":\"" + ACCOUNT + "\",\"side\":\"DEBIT\","
                    + "\"amountField\":\"payload.amount\",\"factorPercent\":60,\"splitGroup\":\"g\"},"
                    + "{\"glAccountId\":\"" + ACCOUNT + "\",\"side\":\"DEBIT\","
                    + "\"amountField\":\"payload.amount\",\"factorPercent\":30,\"splitGroup\":\"g\"}]";
            String rules = "{\"conditions\":[{\"condition\":\"payload.x <= 'oops'\",\"lines\":" + splitLines + "}]}";

            UnbalancedRulesException e = validateExpectingViolation(rules);

            assertThat(e.getViolations())
                    .extracting(RuleViolation::field)
                    .containsExactly("conditions[0].condition", "conditions[0].splitGroup[g]");
        }
    }
}
