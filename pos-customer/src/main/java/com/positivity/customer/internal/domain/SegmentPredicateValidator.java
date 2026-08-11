package com.positivity.customer.internal.domain;

import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.SegmentOperator;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Rejects predicates that would be meaningless or unsafe before they are ever stored
 * (Story #1137).
 *
 * <p>Validating on save rather than on resolve matters: a segment is authored once and
 * resolved thousands of times, often by a scheduled campaign with nobody watching. A
 * predicate that only fails at send time fails silently and late.
 *
 * <p>Every failure raises {@link CrmUnprocessableEntityException} ({@code 422}) — the request
 * is well-formed JSON, but its content is not a predicate this catalog can express.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SegmentPredicateValidator {

    /**
     * Bounded to keep resolution cost predictable; a tree deeper than this is far more likely
     * to be a serialization accident than a real audience definition.
     */
    private static final int MAX_DEPTH = 10;

    private static final int MAX_NODES = 100;

    public static void validate(@NonNull SegmentPredicate predicate, @NonNull AudienceType audienceType) {
        int nodes = validateNode(predicate, audienceType, 1);
        if (nodes > MAX_NODES) {
            throw new CrmUnprocessableEntityException(
                    "Predicate has " + nodes + " nodes, exceeding the maximum of " + MAX_NODES);
        }
    }

    private static int validateNode(SegmentPredicate predicate, AudienceType audienceType, int depth) {
        if (depth > MAX_DEPTH) {
            throw new CrmUnprocessableEntityException("Predicate nesting exceeds the maximum depth of " + MAX_DEPTH);
        }
        return switch (predicate) {
            case SegmentPredicate.Comparison comparison -> {
                validateComparison(comparison, audienceType);
                yield 1;
            }
            case SegmentPredicate.And and -> 1 + validateChildren(and.nodes(), "AND", audienceType, depth);
            case SegmentPredicate.Or or -> 1 + validateChildren(or.nodes(), "OR", audienceType, depth);
            case SegmentPredicate.Not not -> {
                if (not.node() == null) {
                    throw new CrmUnprocessableEntityException("NOT node requires a child predicate");
                }
                yield 1 + validateNode(not.node(), audienceType, depth + 1);
            }
        };
    }

    private static int validateChildren(
            List<SegmentPredicate> nodes, String label, AudienceType audienceType, int depth) {
        if (nodes == null || nodes.isEmpty()) {
            // An empty AND is vacuously true and an empty OR vacuously false. Either silently
            // changes the audience to "everyone" or "nobody", so refuse rather than guess.
            throw new CrmUnprocessableEntityException(label + " node requires at least one child predicate");
        }
        int count = 0;
        for (SegmentPredicate node : nodes) {
            count += validateNode(node, audienceType, depth + 1);
        }
        return count;
    }

    private static void validateComparison(SegmentPredicate.Comparison comparison, AudienceType audienceType) {
        String wireName = comparison.attribute();
        SegmentAttribute attribute = SegmentAttribute.fromWireName(wireName)
                .orElseThrow(() -> new CrmUnprocessableEntityException("Unknown segment attribute: " + wireName));

        if (attribute.isCommercialOnly() && audienceType == AudienceType.INDIVIDUAL) {
            throw new CrmUnprocessableEntityException(
                    "Attribute " + attribute.wireName() + " applies only to commercial parties");
        }

        SegmentOperator operator = comparison.operator();
        if (operator == null) {
            throw new CrmUnprocessableEntityException("Comparison on " + wireName + " requires an operator");
        }
        if (!attribute.supports(operator)) {
            throw new CrmUnprocessableEntityException("Operator " + operator + " is not valid for attribute "
                    + attribute.wireName() + "; allowed: " + attribute.allowedOperators());
        }

        if (attribute.operandKind() == SegmentAttribute.OperandKind.KEYED_TEXT
                && SegmentAttribute.keyOf(wireName)
                        .filter(key -> !key.isBlank())
                        .isEmpty()) {
            throw new CrmUnprocessableEntityException(
                    "Attribute " + attribute.wireName() + " requires a key, e.g. " + attribute.wireName() + "[QBO]");
        }

        validateOperands(attribute, operator, comparison.values());
    }

    private static void validateOperands(SegmentAttribute attribute, SegmentOperator operator, List<String> values) {
        int count = values == null ? 0 : values.size();
        switch (operator) {
            case IS_TRUE, IS_FALSE, IS_NULL, IS_NOT_NULL -> requireOperandCount(attribute, count, 0, 0);
            case IN, NOT_IN, CONTAINS_ANY, CONTAINS_ALL -> requireOperandCount(attribute, count, 1, Integer.MAX_VALUE);
            default -> requireOperandCount(attribute, count, 1, 1);
        }
        if (values == null) {
            return;
        }
        for (String value : values) {
            requireParsable(attribute, value);
        }
    }

    private static void requireOperandCount(SegmentAttribute attribute, int actual, int min, int max) {
        if (actual < min || actual > max) {
            throw new CrmUnprocessableEntityException("Attribute " + attribute.wireName() + " expects "
                    + (min == max ? String.valueOf(min) : "at least " + min) + " operand(s) but got " + actual);
        }
    }

    private static void requireParsable(SegmentAttribute attribute, String value) {
        if (value == null) {
            throw new CrmUnprocessableEntityException("Attribute " + attribute.wireName() + " has a null operand");
        }
        switch (attribute.operandKind()) {
            case NUMBER -> {
                try {
                    Long.parseLong(value.trim());
                } catch (NumberFormatException ex) {
                    throw new CrmUnprocessableEntityException(
                            "Attribute " + attribute.wireName() + " expects a number but got: " + value);
                }
            }
            case UUID_LIST -> {
                try {
                    UUID.fromString(value.trim());
                } catch (IllegalArgumentException ex) {
                    throw new CrmUnprocessableEntityException(
                            "Attribute " + attribute.wireName() + " expects a UUID but got: " + value);
                }
            }
            case ENUM_TEXT -> requireEnumValue(attribute, value);
            default -> {
                if (value.isBlank()) {
                    throw new CrmUnprocessableEntityException(
                            "Attribute " + attribute.wireName() + " has a blank operand");
                }
            }
        }
    }

    /**
     * Enum-backed attributes are checked against the actual enum so a typo like
     * {@code PLATINIUM} fails at authoring time rather than quietly matching nobody.
     */
    private static void requireEnumValue(SegmentAttribute attribute, String value) {
        Optional<Class<? extends Enum<?>>> enumType = enumTypeFor(attribute);
        if (enumType.isEmpty()) {
            return;
        }
        boolean known = java.util.Arrays.stream(enumType.get().getEnumConstants())
                .anyMatch(constant -> constant.name().equalsIgnoreCase(value.trim()));
        if (!known) {
            throw new CrmUnprocessableEntityException("Attribute " + attribute.wireName()
                    + " does not accept value '" + value + "'; allowed: "
                    + java.util.Arrays.toString(enumType.get().getEnumConstants()));
        }
    }

    private static Optional<Class<? extends Enum<?>>> enumTypeFor(SegmentAttribute attribute) {
        return switch (attribute) {
            case PARTY_TYPE -> Optional.of(com.positivity.customer.internal.enums.PartyType.class);
            case ACCOUNT_TIER -> Optional.of(com.positivity.customer.internal.enums.AccountTier.class);
            case ACCOUNT_STATUS -> Optional.of(com.positivity.customer.internal.enums.AccountStatus.class);
            case MARKETING_EMAIL_CONSENT, MARKETING_SMS_CONSENT ->
                Optional.of(com.positivity.customer.internal.enums.MarketingConsent.class);
            default -> Optional.empty();
        };
    }
}
