package com.positivity.customer.internal.domain;

import com.positivity.customer.internal.enums.SegmentOperator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Evaluates a validated predicate against one party's attribute snapshot (Story #1137).
 *
 * <p>Assumes {@link SegmentPredicateValidator} has already run: unknown attributes and
 * operator/operand mismatches cannot reach here, so evaluation has no error branches and a
 * malformed tree can never silently widen an audience.
 *
 * <p>Missing data evaluates to non-match rather than match. A party with no billing rules is
 * not "not on credit hold" — it is unknown, and sending to unknown is the failure that costs
 * money.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SegmentPredicateEvaluator {

    public static boolean matches(@NonNull SegmentPredicate predicate, @NonNull PartyAttributes party) {
        return switch (predicate) {
            case SegmentPredicate.Comparison comparison -> evaluate(comparison, party);
            case SegmentPredicate.And and -> and.nodes().stream().allMatch(node -> matches(node, party));
            case SegmentPredicate.Or or -> or.nodes().stream().anyMatch(node -> matches(node, party));
            case SegmentPredicate.Not not -> !matches(not.node(), party);
        };
    }

    private static boolean evaluate(SegmentPredicate.Comparison comparison, PartyAttributes party) {
        SegmentAttribute attribute =
                SegmentAttribute.fromWireName(comparison.attribute()).orElseThrow();
        SegmentOperator operator = comparison.operator();
        List<String> values = comparison.values();

        return switch (attribute) {
            case PARTY_TYPE -> textMatch(party.partyType(), operator, values);
            case ACCOUNT_TIER -> textMatch(party.accountTier(), operator, values);
            case ACCOUNT_STATUS -> textMatch(party.accountStatus(), operator, values);
            case HAS_PARENT_PARTY -> booleanMatch(party.hasParentParty(), operator);
            case EXTERNAL_IDENTIFIER -> {
                String key = SegmentAttribute.keyOf(comparison.attribute()).orElse("");
                yield textMatch(party.externalIdentifiers().get(key), operator, values);
            }
            case TAGS -> tagMatch(party.tagIds(), operator, values);
            case TAX_EXEMPT -> nullableBooleanMatch(party.taxExempt(), operator);
            case CREDIT_HOLD -> nullableBooleanMatch(party.creditHold(), operator);
            case PAYMENT_TERMS -> textMatch(party.paymentTerms(), operator, values);
            case MARKETING_EMAIL_CONSENT -> textMatch(party.marketingEmailConsent(), operator, values);
            case MARKETING_SMS_CONSENT -> textMatch(party.marketingSmsConsent(), operator, values);
            case VEHICLE_MAKE -> collectionTextMatch(party.vehicleMakes(), operator, values);
            case VEHICLE_MODEL -> collectionTextMatch(party.vehicleModels(), operator, values);
            case VEHICLE_YEAR -> yearMatch(party.vehicleYears(), operator, values);
            case HAS_ACTIVE_VEHICLE -> booleanMatch(party.hasActiveVehicle(), operator);
            case VEHICLE_COUNT -> numberMatch(party.vehicleCount(), operator, values);
            case MONTHS_SINCE_LAST_SERVICE -> nullableNumberMatch(party.monthsSinceLastService(), operator, values);
            case HAS_SERVICE_HISTORY -> booleanMatch(party.hasServiceHistory(), operator);
            case DAYS_SINCE_DECLINED_SERVICE -> nullableNumberMatch(party.daysSinceDeclinedService(), operator, values);
            case SERVICE_DUE -> booleanMatch(party.serviceDue(), operator);
            case ADDRESS_COUNTRY -> textMatch(party.addressCountry(), operator, values);
            case ADDRESS_REGION -> textMatch(party.addressRegion(), operator, values);
            case ADDRESS_CITY -> textMatch(party.addressCity(), operator, values);
            case ADDRESS_POSTAL_CODE -> textMatch(party.addressPostalCode(), operator, values);
        };
    }

    private static boolean textMatch(@Nullable String actual, SegmentOperator operator, List<String> values) {
        return switch (operator) {
            case IS_NULL -> actual == null;
            case IS_NOT_NULL -> actual != null;
            case EQUALS -> actual != null && equalsIgnoreCase(actual, values.get(0));
            case NOT_EQUALS -> actual == null || !equalsIgnoreCase(actual, values.get(0));
            case IN -> actual != null && values.stream().anyMatch(value -> equalsIgnoreCase(actual, value));
            case NOT_IN -> actual == null || values.stream().noneMatch(value -> equalsIgnoreCase(actual, value));
            default -> false;
        };
    }

    /** Any-vehicle semantics: the party matches if at least one of its vehicles does. */
    private static boolean collectionTextMatch(Set<String> actual, SegmentOperator operator, List<String> values) {
        return switch (operator) {
            case EQUALS, IN ->
                actual.stream().anyMatch(item -> values.stream().anyMatch(value -> equalsIgnoreCase(item, value)));
            case NOT_EQUALS, NOT_IN ->
                actual.stream().noneMatch(item -> values.stream().anyMatch(value -> equalsIgnoreCase(item, value)));
            default -> false;
        };
    }

    private static boolean yearMatch(Set<Integer> years, SegmentOperator operator, List<String> values) {
        if (years.isEmpty()) {
            return false;
        }
        long operand = Long.parseLong(values.get(0).trim());
        return years.stream().anyMatch(year -> compare(year.longValue(), operator, operand));
    }

    private static boolean numberMatch(long actual, SegmentOperator operator, List<String> values) {
        return compare(actual, operator, Long.parseLong(values.get(0).trim()));
    }

    /**
     * A null value is unknown, not zero: a party with no service history has not "had its last
     * service 0 months ago", so a "months since last service &gt; 6" predicate must not match it.
     * Missing data evaluates to non-match, consistent with the rest of the catalog.
     */
    private static boolean nullableNumberMatch(
            @Nullable Integer actual, SegmentOperator operator, List<String> values) {
        if (actual == null) {
            return false;
        }
        return compare(
                actual.longValue(), operator, Long.parseLong(values.get(0).trim()));
    }

    private static boolean compare(long actual, SegmentOperator operator, long operand) {
        return switch (operator) {
            case EQUALS -> actual == operand;
            case NOT_EQUALS -> actual != operand;
            case GREATER_THAN -> actual > operand;
            case GREATER_THAN_OR_EQUAL -> actual >= operand;
            case LESS_THAN -> actual < operand;
            case LESS_THAN_OR_EQUAL -> actual <= operand;
            default -> false;
        };
    }

    private static boolean booleanMatch(boolean actual, SegmentOperator operator) {
        return switch (operator) {
            case IS_TRUE -> actual;
            case IS_FALSE -> !actual;
            default -> false;
        };
    }

    /**
     * A null flag is unknown, not false: an account with no billing rules recorded has not been
     * established as off credit hold, and treating it as such is how a suppressed account gets
     * marketed to.
     */
    private static boolean nullableBooleanMatch(@Nullable Boolean actual, SegmentOperator operator) {
        if (actual == null) {
            return false;
        }
        return booleanMatch(actual, operator);
    }

    private static boolean tagMatch(Set<UUID> tagIds, SegmentOperator operator, List<String> values) {
        List<UUID> operands =
                values.stream().map(String::trim).map(UUID::fromString).toList();
        return switch (operator) {
            case CONTAINS_ANY -> operands.stream().anyMatch(tagIds::contains);
            case CONTAINS_ALL -> tagIds.containsAll(operands);
            default -> false;
        };
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left.trim().toLowerCase(Locale.ROOT).equals(right.trim().toLowerCase(Locale.ROOT));
    }
}
