package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.exception.UnbalancedRulesException;
import com.positivity.accounting.internal.exception.UnbalancedRulesException.RuleViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Publish-time validation of a posting rules definition: split-group
 * invariants (story E1, issue #945) and condition-predicate grammar
 * (story E2, issue #946).
 *
 * <p>Condition predicates: every {@code conditions[].condition} string must
 * either be a catch-all form (absent / {@code null} / blank / {@code "*"})
 * or parse under the {@link PredicateParser} whitelist grammar. A condition
 * that fails to parse — malformed syntax, trailing garbage, unbalanced
 * quotes, an unknown identifier, or an ordering operator
 * ({@code > >= < <=}) applied to a string literal — is reported as a
 * violation located at {@code conditions[i].condition}. Malformed
 * predicates therefore fail at publish time, never at evaluation time.
 *
 * <p>Split-group semantics (see the accounting domain
 * {@code POSTING_RULES_SCHEMA.md}): lines within one condition that carry the
 * same {@code splitGroup} value form a split group. Within a group:
 * <ul>
 * <li>every line must declare a {@code factorPercent} (decimal, 0–100, at
 * most 4 decimal places) and a non-blank {@code amountField};</li>
 * <li>all lines must share the same {@code amountField};</li>
 * <li>all lines must have the same {@code side} (mixed DEBIT/CREDIT groups
 * are rejected — a group decomposes a single-sided amount);</li>
 * <li>the factors must sum to exactly 100.</li>
 * </ul>
 * A line carrying {@code factorPercent} without {@code splitGroup} (or vice
 * versa) is invalid. Lines with neither field are untouched by this
 * validator — the schema addition is fully backward compatible.
 *
 * <p>All violations across all conditions are collected and reported in a
 * single {@link UnbalancedRulesException} (HTTP 422, {@code UNBALANCED_RULES}).
 * A rules definition that is not parseable as JSON is rejected with
 * {@link IllegalArgumentException} (HTTP 400, {@code VALIDATION_ERROR}),
 * matching the existing empty-definition publish guard.
 */
public final class PostingRuleDefinitionValidator {

    /** Maximum decimal places allowed for {@code factorPercent}. */
    static final int MAX_FACTOR_SCALE = 4;

    static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PostingRuleDefinitionValidator() {}

    /**
     * Validates the split-group invariants (E1) and condition-predicate
     * grammar (E2) of a rules definition prior to publishing.
     *
     * @param rulesDefinition the raw rules definition JSON (non-blank; the
     *                        caller enforces the non-empty guard)
     * @throws IllegalArgumentException if the definition is not valid JSON
     * @throws UnbalancedRulesException if any split-group or condition
     *                                  predicate invariant is violated
     */
    public static void validateForPublish(@NonNull String rulesDefinition) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(rulesDefinition);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot publish: rules definition is not valid JSON", e);
        }

        JsonNode conditions = root.get("conditions");
        if (conditions == null || !conditions.isArray()) {
            // Definitions without a conditions array carry no split lines to
            // validate; the evaluator handles them (as today) at runtime.
            return;
        }

        List<RuleViolation> violations = new ArrayList<>();
        for (int c = 0; c < conditions.size(); c++) {
            validateCondition(conditions.get(c), c, violations);
        }

        if (!violations.isEmpty()) {
            throw new UnbalancedRulesException(violations);
        }
    }

    private static void validateCondition(JsonNode conditionBlock, int conditionIndex, List<RuleViolation> violations) {
        validateConditionPredicate(conditionBlock.get("condition"), conditionIndex, violations);

        JsonNode linesNode = conditionBlock.get("lines");
        if (linesNode == null || !linesNode.isArray()) {
            return;
        }

        // splitGroup value -> member line descriptors, in rule order
        Map<String, List<SplitLine>> groups = new LinkedHashMap<>();

        for (int i = 0; i < linesNode.size(); i++) {
            JsonNode lineNode = linesNode.get(i);
            String lineField = "conditions[" + conditionIndex + "].lines[" + i + "]";

            String splitGroup = textOrNull(lineNode, "splitGroup");
            boolean hasFactor = lineNode.has("factorPercent");

            if (splitGroup == null && !hasFactor) {
                continue; // plain line — untouched by E1
            }

            if (lineNode.has("splitGroup") && (splitGroup == null || splitGroup.isBlank())) {
                violations.add(new RuleViolation(lineField + ".splitGroup", "splitGroup must be a non-blank string"));
                continue;
            }

            if (splitGroup == null) {
                violations.add(new RuleViolation(
                        lineField + ".factorPercent",
                        "factorPercent requires a splitGroup — a line cannot declare a split factor "
                                + "outside a split group"));
                continue;
            }

            if (!hasFactor) {
                violations.add(new RuleViolation(
                        lineField + ".factorPercent",
                        "line in split group '" + splitGroup + "' must declare a factorPercent"));
                groups.computeIfAbsent(splitGroup, g -> new ArrayList<>())
                        .add(new SplitLine(i, lineField, null, textOrNull(lineNode, "amountField"), sideOf(lineNode)));
                continue;
            }

            BigDecimal factor = parseFactor(lineNode.get("factorPercent"));
            if (factor == null) {
                violations.add(new RuleViolation(
                        lineField + ".factorPercent", "factorPercent must be a decimal number between 0 and 100"));
            } else if (factor.compareTo(BigDecimal.ZERO) < 0 || factor.compareTo(ONE_HUNDRED) > 0) {
                violations.add(new RuleViolation(
                        lineField + ".factorPercent",
                        "factorPercent must be between 0 and 100 (was " + factor.toPlainString() + ")"));
                factor = null;
            } else if (factor.stripTrailingZeros().scale() > MAX_FACTOR_SCALE) {
                violations.add(new RuleViolation(
                        lineField + ".factorPercent",
                        "factorPercent supports at most " + MAX_FACTOR_SCALE + " decimal places (was "
                                + lineNode.get("factorPercent").asString() + ")"));
                factor = null;
            }

            groups.computeIfAbsent(splitGroup, g -> new ArrayList<>())
                    .add(new SplitLine(i, lineField, factor, textOrNull(lineNode, "amountField"), sideOf(lineNode)));
        }

        for (Map.Entry<String, List<SplitLine>> entry : groups.entrySet()) {
            validateGroup(entry.getKey(), entry.getValue(), conditionIndex, violations);
        }
    }

    private static void validateGroup(
            String groupName, List<SplitLine> members, int conditionIndex, List<RuleViolation> violations) {
        String groupField = "conditions[" + conditionIndex + "].splitGroup[" + groupName + "]";

        // Shared, non-blank amountField
        String firstAmountField = null;
        boolean amountFieldsConsistent = true;
        for (SplitLine member : members) {
            if (member.amountField() == null || member.amountField().isBlank()) {
                violations.add(new RuleViolation(
                        member.lineField() + ".amountField",
                        "line in split group '" + groupName + "' must declare a non-blank amountField"));
                amountFieldsConsistent = false;
            } else if (firstAmountField == null) {
                firstAmountField = member.amountField();
            } else if (!firstAmountField.equals(member.amountField())) {
                amountFieldsConsistent = false;
            }
        }
        if (firstAmountField != null && !amountFieldsConsistent) {
            violations.add(new RuleViolation(
                    groupField, "all lines in split group '" + groupName + "' must share the same amountField"));
        }

        // Uniform side — a split group decomposes one single-sided amount
        String firstSide = members.get(0).side();
        boolean mixedSides = members.stream().anyMatch(member -> !member.side().equalsIgnoreCase(firstSide));
        if (mixedSides) {
            violations.add(new RuleViolation(
                    groupField,
                    "split group '" + groupName + "' mixes DEBIT and CREDIT lines — all lines in a "
                            + "split group must have the same side"));
        }

        // Factors sum to exactly 100 (only checked when every factor parsed
        // cleanly, to avoid cascading noise behind per-line violations)
        boolean allFactorsValid = members.stream().allMatch(member -> member.factorPercent() != null);
        if (allFactorsValid) {
            BigDecimal sum = members.stream().map(SplitLine::factorPercent).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(ONE_HUNDRED) != 0) {
                violations.add(new RuleViolation(
                        groupField,
                        "factorPercent values in split group '" + groupName + "' must sum to exactly 100 "
                                + "(lines " + memberIndexes(members) + " sum to "
                                + sum.stripTrailingZeros().toPlainString() + ")"));
            }
        }
    }

    /**
     * Validates one condition's predicate string (story E2, issue #946):
     * catch-all forms (absent / null / blank / {@code "*"}) are always
     * valid; anything else must parse under the {@link PredicateParser}
     * whitelist grammar.
     */
    private static void validateConditionPredicate(
            @Nullable JsonNode conditionNode, int conditionIndex, List<RuleViolation> violations) {
        String conditionField = "conditions[" + conditionIndex + "].condition";

        if (conditionNode == null || conditionNode.isNull()) {
            return; // absent condition → catch-all
        }
        if (!conditionNode.isString()) {
            violations.add(new RuleViolation(
                    conditionField, "condition must be a string predicate (or absent for a catch-all rule)"));
            return;
        }

        String expression = conditionNode.asString();
        if (expression.isBlank() || "*".equals(expression.trim())) {
            return; // blank / wildcard → catch-all
        }

        try {
            PredicateParser.parse(expression);
        } catch (PredicateParser.ParseException e) {
            violations.add(new RuleViolation(conditionField, "condition predicate is invalid: " + e.getMessage()));
        }
    }

    private static String memberIndexes(List<SplitLine> members) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(members.get(i).index());
        }
        return sb.append(']').toString();
    }

    @Nullable
    private static BigDecimal parseFactor(JsonNode factorNode) {
        try {
            return new BigDecimal(factorNode.asString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static String sideOf(JsonNode lineNode) {
        JsonNode side = lineNode.get("side");
        return side != null ? side.asString() : "DEBIT";
    }

    /**
     * Parsed view of one split-group member line.
     *
     * @param index         zero-based index within the condition's lines array
     * @param lineField     field locator prefix for violation reporting
     * @param factorPercent parsed factor, or {@code null} when missing/invalid
     * @param amountField   declared amount field, or {@code null} when missing
     * @param side          declared side (defaults to DEBIT, as the evaluator does)
     */
    private record SplitLine(
            int index,
            String lineField,
            @Nullable BigDecimal factorPercent,
            @Nullable String amountField,
            String side) {}
}
