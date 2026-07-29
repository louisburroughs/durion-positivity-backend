package com.positivity.customer.internal.domain;

import com.positivity.customer.internal.enums.SegmentOperator;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The whitelisted catalog of attributes a segment predicate may reference (Story #1137).
 *
 * <p>Predicates are stored data authored by marketers, so they are deliberately <em>not</em>
 * free SQL. Every attribute a predicate can name appears here, with the operand kind and the
 * operators it accepts; anything outside the enum is rejected at save time. That keeps the
 * resolver's query surface fixed and auditable no matter what a stored predicate contains.
 *
 * <p>Service-history attributes (FI-3, #1133) are fed by the workorder fact feed via the CRM
 * service-history read model. Geography attributes remain absent by design: they need structured
 * addresses (FI-4), which are not yet available.
 */
public enum SegmentAttribute {

    // --- Party core ---
    PARTY_TYPE("party.partyType", OperandKind.ENUM_TEXT, equality()),
    ACCOUNT_TIER("party.accountTier", OperandKind.ENUM_TEXT, equality()),
    ACCOUNT_STATUS("party.accountStatus", OperandKind.ENUM_TEXT, equality()),
    HAS_PARENT_PARTY("party.hasParentParty", OperandKind.BOOLEAN, booleanOps()),
    /** Keyed attribute: the key names the external system, e.g. {@code party.externalIdentifier[QBO]}. */
    EXTERNAL_IDENTIFIER("party.externalIdentifier", OperandKind.KEYED_TEXT, presenceAndEquality()),
    TAGS("party.tags", OperandKind.UUID_LIST, EnumSet.of(SegmentOperator.CONTAINS_ANY, SegmentOperator.CONTAINS_ALL)),

    // --- Billing rules (commercial only; an individual party has no billing rules) ---
    TAX_EXEMPT("billing.taxExempt", OperandKind.BOOLEAN, booleanOps()),
    CREDIT_HOLD("billing.creditHold", OperandKind.BOOLEAN, booleanOps()),
    PAYMENT_TERMS("billing.paymentTerms", OperandKind.TEXT, presenceAndEquality()),

    // --- Marketing consent ---
    MARKETING_EMAIL_CONSENT("consent.marketingEmail", OperandKind.ENUM_TEXT, equality()),
    MARKETING_SMS_CONSENT("consent.marketingSms", OperandKind.ENUM_TEXT, equality()),

    // --- Vehicle, from the ext_vehicle replica ---
    VEHICLE_MAKE("vehicle.make", OperandKind.TEXT, equality()),
    VEHICLE_MODEL("vehicle.model", OperandKind.TEXT, equality()),
    VEHICLE_YEAR("vehicle.year", OperandKind.NUMBER, numeric()),
    HAS_ACTIVE_VEHICLE("vehicle.hasActive", OperandKind.BOOLEAN, booleanOps()),
    VEHICLE_COUNT("vehicle.count", OperandKind.NUMBER, numeric()),

    // --- Service history, from the workorder fact feed (FI-3, #1133) ---
    /** Whole months since the party's most recent completed service; unset when no history. */
    MONTHS_SINCE_LAST_SERVICE("service.monthsSinceLast", OperandKind.NUMBER, numeric()),
    /** Whether the party has any completed-service history at all. */
    HAS_SERVICE_HISTORY("service.hasHistory", OperandKind.BOOLEAN, booleanOps()),
    /** Whole days since the party's most recent declined recommendation; unset when none. */
    DAYS_SINCE_DECLINED_SERVICE("service.daysSinceDeclined", OperandKind.NUMBER, numeric());

    /** What shape of operand the attribute compares against. */
    public enum OperandKind {
        TEXT,
        ENUM_TEXT,
        NUMBER,
        BOOLEAN,
        UUID_LIST,
        KEYED_TEXT
    }

    private final String wireName;
    private final OperandKind operandKind;
    private final Set<SegmentOperator> allowedOperators;

    SegmentAttribute(String wireName, OperandKind operandKind, Set<SegmentOperator> allowedOperators) {
        this.wireName = wireName;
        this.operandKind = operandKind;
        this.allowedOperators = allowedOperators;
    }

    public String wireName() {
        return wireName;
    }

    public OperandKind operandKind() {
        return operandKind;
    }

    public Set<SegmentOperator> allowedOperators() {
        return allowedOperators;
    }

    public boolean supports(SegmentOperator operator) {
        return allowedOperators.contains(operator);
    }

    /** Attributes that only exist on a commercial party, so an INDIVIDUAL segment cannot use them. */
    public boolean isCommercialOnly() {
        return this == TAX_EXEMPT || this == CREDIT_HOLD || this == PAYMENT_TERMS || this == HAS_PARENT_PARTY;
    }

    /**
     * Resolve a wire name to its catalog entry. Keyed attributes arrive as
     * {@code party.externalIdentifier[QBO]}, so the bracketed key is stripped first.
     */
    public static Optional<SegmentAttribute> fromWireName(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return Optional.empty();
        }
        String base = baseName(wireName);
        return Arrays.stream(values())
                .filter(attribute -> attribute.wireName.equalsIgnoreCase(base))
                .findFirst();
    }

    /** The bracketed key of a keyed attribute, e.g. {@code QBO}; empty for plain attributes. */
    public static Optional<String> keyOf(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        int open = wireName.indexOf('[');
        int close = wireName.lastIndexOf(']');
        if (open < 0 || close <= open + 1) {
            return Optional.empty();
        }
        return Optional.of(wireName.substring(open + 1, close).trim());
    }

    private static String baseName(String wireName) {
        int open = wireName.indexOf('[');
        return (open < 0 ? wireName : wireName.substring(0, open)).trim().toLowerCase(Locale.ROOT);
    }

    private static Set<SegmentOperator> equality() {
        return EnumSet.of(
                SegmentOperator.EQUALS, SegmentOperator.NOT_EQUALS, SegmentOperator.IN, SegmentOperator.NOT_IN);
    }

    private static Set<SegmentOperator> presenceAndEquality() {
        return EnumSet.of(
                SegmentOperator.EQUALS,
                SegmentOperator.NOT_EQUALS,
                SegmentOperator.IN,
                SegmentOperator.NOT_IN,
                SegmentOperator.IS_NULL,
                SegmentOperator.IS_NOT_NULL);
    }

    private static Set<SegmentOperator> numeric() {
        return EnumSet.of(
                SegmentOperator.EQUALS,
                SegmentOperator.NOT_EQUALS,
                SegmentOperator.GREATER_THAN,
                SegmentOperator.GREATER_THAN_OR_EQUAL,
                SegmentOperator.LESS_THAN,
                SegmentOperator.LESS_THAN_OR_EQUAL);
    }

    private static Set<SegmentOperator> booleanOps() {
        return EnumSet.of(SegmentOperator.IS_TRUE, SegmentOperator.IS_FALSE);
    }
}
