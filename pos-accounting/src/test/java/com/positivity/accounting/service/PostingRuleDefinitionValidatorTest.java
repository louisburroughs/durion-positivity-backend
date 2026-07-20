package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.exception.UnbalancedRulesException;
import com.positivity.accounting.internal.service.PostingRuleDefinitionValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for publish-time split-group validation (story E1, issue #945).
 *
 * <p>
 * Every invariant violation must be rejected with
 * {@link UnbalancedRulesException} carrying a violation entry that locates
 * the offending group or line; valid definitions (including pre-E1
 * definitions without split fields) must pass untouched.
 * </p>
 */
@DisplayName("PostingRuleDefinitionValidator (E1 publish validation)")
class PostingRuleDefinitionValidatorTest {

    private static final String ACCOUNT = "00000000-0000-0000-0000-0000000000a1";

    private static String rules(String... lines) {
        return "{\"conditions\":[{\"condition\":\"eventType == 'x'\",\"lines\":[" + String.join(",", lines) + "]}]}";
    }

    private static String line(String extraJson) {
        return "{\"glAccountId\":\"" + ACCOUNT + "\",\"side\":\"DEBIT\",\"amountField\":\"payload.amount\""
                + (extraJson.isEmpty() ? "" : "," + extraJson) + "}";
    }

    private static String splitLine(String factor, String group) {
        return line("\"factorPercent\":" + factor + ",\"splitGroup\":\"" + group + "\"");
    }

    private static UnbalancedRulesException validateExpectingViolation(String rulesDefinition) {
        try {
            PostingRuleDefinitionValidator.validateForPublish(rulesDefinition);
        } catch (UnbalancedRulesException e) {
            return e;
        }
        throw new AssertionError("Expected UnbalancedRulesException");
    }

    // ── Valid definitions ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Valid definitions")
    class ValidDefinitions {

        @Test
        @DisplayName("pre-E1 definition without split fields passes")
        void plainDefinitionPasses() {
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(rules(line(""), line(""))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("empty conditions array passes (existing stub rule sets)")
        void emptyConditionsPasses() {
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish("{\"conditions\":[]}"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("valid split group with 4dp factors summing to 100 passes")
        void validSplitGroupPasses() {
            assertThatCode(() -> PostingRuleDefinitionValidator.validateForPublish(
                            rules(splitLine("33.3333", "g1"), splitLine("33.3333", "g1"), splitLine("33.3334", "g1"))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("single-line split group with factor exactly 100 passes")
        void singleLineGroupWithFactorHundredPasses() {
            assertThatCode(() ->
                            PostingRuleDefinitionValidator.validateForPublish(rules(splitLine("100", "g1"), line(""))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("not-JSON definition is rejected as IllegalArgumentException (400), not UNBALANCED_RULES")
        void malformedJsonIsIllegalArgument() {
            assertThatThrownBy(() -> PostingRuleDefinitionValidator.validateForPublish("this is not json{{"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not valid JSON");
        }
    }

    // ── Invariant violations ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Invariant violations")
    class InvariantViolations {

        @Test
        @DisplayName("factors that do not sum to 100 are rejected, naming the group and actual sum")
        void factorSumMismatch() {
            UnbalancedRulesException ex =
                    validateExpectingViolation(rules(splitLine("60", "g1"), splitLine("30", "g1")));

            assertThat(ex.getViolations()).hasSize(1);
            assertThat(ex.getViolations().get(0).field()).isEqualTo("conditions[0].splitGroup[g1]");
            assertThat(ex.getViolations().get(0).message())
                    .contains("sum to exactly 100")
                    .contains("90");
            assertThat(ex.getMessage()).contains("conditions[0].splitGroup[g1]");
        }

        @Test
        @DisplayName("single-line split group with factor != 100 is rejected")
        void singleLineGroupWithWrongFactor() {
            UnbalancedRulesException ex = validateExpectingViolation(rules(splitLine("60", "g1")));

            assertThat(ex.getViolations()).hasSize(1);
            assertThat(ex.getViolations().get(0).field()).isEqualTo("conditions[0].splitGroup[g1]");
        }

        @Test
        @DisplayName("factorPercent without splitGroup (orphan) is rejected")
        void orphanFactorPercent() {
            UnbalancedRulesException ex = validateExpectingViolation(rules(line("\"factorPercent\":60")));

            assertThat(ex.getViolations()).hasSize(1);
            assertThat(ex.getViolations().get(0).field()).isEqualTo("conditions[0].lines[0].factorPercent");
            assertThat(ex.getViolations().get(0).message()).contains("requires a splitGroup");
        }

        @Test
        @DisplayName("splitGroup member without factorPercent is rejected")
        void splitGroupMemberWithoutFactor() {
            UnbalancedRulesException ex =
                    validateExpectingViolation(rules(splitLine("100", "g1"), line("\"splitGroup\":\"g1\"")));

            assertThat(ex.getViolations())
                    .anySatisfy(violation -> assertThat(violation.message()).contains("must declare a factorPercent"));
        }

        @Test
        @DisplayName("factorPercent outside 0-100 is rejected")
        void factorOutOfRange() {
            UnbalancedRulesException ex =
                    validateExpectingViolation(rules(splitLine("150", "g1"), splitLine("-50", "g1")));

            assertThat(ex.getViolations()).hasSize(2);
            assertThat(ex.getViolations().get(0).message()).contains("between 0 and 100");
            assertThat(ex.getViolations().get(1).message()).contains("between 0 and 100");
        }

        @Test
        @DisplayName("factorPercent with more than 4 decimal places is rejected")
        void factorTooPrecise() {
            UnbalancedRulesException ex =
                    validateExpectingViolation(rules(splitLine("33.33333", "g1"), splitLine("66.66667", "g1")));

            assertThat(ex.getViolations()).hasSize(2);
            assertThat(ex.getViolations().get(0).message()).contains("at most 4 decimal places");
        }

        @Test
        @DisplayName("non-numeric factorPercent is rejected")
        void factorNotNumeric() {
            UnbalancedRulesException ex =
                    validateExpectingViolation(rules(splitLine("\"sixty\"", "g1"), splitLine("40", "g1")));

            assertThat(ex.getViolations())
                    .anySatisfy(violation -> assertThat(violation.message()).contains("must be a decimal number"));
        }

        @Test
        @DisplayName("split-group lines with different amountFields are rejected")
        void mismatchedAmountFields() {
            String other = "{\"glAccountId\":\"" + ACCOUNT + "\",\"side\":\"DEBIT\","
                    + "\"amountField\":\"payload.other\",\"factorPercent\":40,\"splitGroup\":\"g1\"}";
            UnbalancedRulesException ex = validateExpectingViolation(rules(splitLine("60", "g1"), other));

            assertThat(ex.getViolations())
                    .anySatisfy(
                            violation -> assertThat(violation.message()).contains("must share the same amountField"));
        }

        @Test
        @DisplayName("split-group line without amountField is rejected")
        void missingAmountField() {
            String noField = "{\"glAccountId\":\"" + ACCOUNT + "\",\"side\":\"DEBIT\","
                    + "\"factorPercent\":100,\"splitGroup\":\"g1\"}";
            UnbalancedRulesException ex = validateExpectingViolation(rules(noField));

            assertThat(ex.getViolations())
                    .anySatisfy(violation -> assertThat(violation.message()).contains("non-blank amountField"));
        }

        @Test
        @DisplayName("mixed DEBIT/CREDIT split group is rejected")
        void mixedSides() {
            String creditLine = "{\"glAccountId\":\"" + ACCOUNT + "\",\"side\":\"CREDIT\","
                    + "\"amountField\":\"payload.amount\",\"factorPercent\":40,\"splitGroup\":\"g1\"}";
            UnbalancedRulesException ex = validateExpectingViolation(rules(splitLine("60", "g1"), creditLine));

            assertThat(ex.getViolations())
                    .anySatisfy(violation -> assertThat(violation.message()).contains("mixes DEBIT and CREDIT"));
        }

        @Test
        @DisplayName("blank splitGroup is rejected")
        void blankSplitGroup() {
            UnbalancedRulesException ex = validateExpectingViolation(rules(splitLine("100", " ")));

            assertThat(ex.getViolations())
                    .anySatisfy(violation -> assertThat(violation.message()).contains("non-blank string"));
        }

        @Test
        @DisplayName("all violations across conditions and groups are reported in one exception")
        void aggregatesAllViolations() {
            String condition1 = "{\"condition\":\"eventType == 'x'\",\"lines\":[" + splitLine("60", "g1") + ","
                    + splitLine("30", "g1") + "]}";
            String condition2 = "{\"condition\":\"eventType == 'y'\",\"lines\":[" + line("\"factorPercent\":10") + "]}";
            String definition = "{\"conditions\":[" + condition1 + "," + condition2 + "]}";

            UnbalancedRulesException ex = validateExpectingViolation(definition);

            assertThat(ex.getViolations()).hasSize(2);
            assertThat(ex.getViolations().get(0).field()).startsWith("conditions[0]");
            assertThat(ex.getViolations().get(1).field()).startsWith("conditions[1]");
        }
    }
}
