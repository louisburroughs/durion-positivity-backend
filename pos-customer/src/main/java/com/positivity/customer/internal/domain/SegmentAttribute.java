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
 * <p>Service-history attributes (FI-3, #1133; A-follow, #1144) are fed by the workorder fact
 * feed via the CRM service-history read model. Geography attributes (FI-4, #1135) are fed by
 * the structured postal address pos-people-contact owns: individuals via the
 * ext_people_contact_person replica, commercial parties via ext_organization_postal_address.
 *
 * <p>The catalog is discoverable: {@code GET /v1/crm/segments/attributes} serves every entry
 * with its operand kind, allowed operators, and description (#1144).
 */
public enum SegmentAttribute {

    // --- Party core ---
    PARTY_TYPE("party.partyType", OperandKind.ENUM_TEXT, equality(), "Party classification (PERSON or COMMERCIAL)"),
    ACCOUNT_TIER("party.accountTier", OperandKind.ENUM_TEXT, equality(), "Account tier, e.g. GOLD or PLATINUM"),
    ACCOUNT_STATUS("party.accountStatus", OperandKind.ENUM_TEXT, equality(), "Account lifecycle status"),
    HAS_PARENT_PARTY(
            "party.hasParentParty",
            OperandKind.BOOLEAN,
            booleanOps(),
            "Whether the commercial party belongs to a parent account"),
    /** Keyed attribute: the key names the external system, e.g. {@code party.externalIdentifier[QBO]}. */
    EXTERNAL_IDENTIFIER(
            "party.externalIdentifier",
            OperandKind.KEYED_TEXT,
            presenceAndEquality(),
            "External system identifier; the bracketed key names the system, e.g. party.externalIdentifier[QBO]"),
    TAGS(
            "party.tags",
            OperandKind.UUID_LIST,
            EnumSet.of(SegmentOperator.CONTAINS_ANY, SegmentOperator.CONTAINS_ALL),
            "CRM tags attached to the party, by tag id"),

    // --- Billing rules (commercial only; an individual party has no billing rules) ---
    TAX_EXEMPT("billing.taxExempt", OperandKind.BOOLEAN, booleanOps(), "Billing rule: the account is tax exempt"),
    CREDIT_HOLD("billing.creditHold", OperandKind.BOOLEAN, booleanOps(), "Billing rule: the account is on credit hold"),
    PAYMENT_TERMS(
            "billing.paymentTerms",
            OperandKind.TEXT,
            presenceAndEquality(),
            "Billing rule: payment terms code, e.g. NET30"),

    // --- Marketing consent ---
    MARKETING_EMAIL_CONSENT(
            "consent.marketingEmail",
            OperandKind.ENUM_TEXT,
            equality(),
            "Marketing consent for email (OPT_IN, OPT_OUT, UNSET)"),
    MARKETING_SMS_CONSENT(
            "consent.marketingSms",
            OperandKind.ENUM_TEXT,
            equality(),
            "Marketing consent for SMS (OPT_IN, OPT_OUT, UNSET)"),

    // --- Vehicle, from the ext_vehicle replica ---
    VEHICLE_MAKE(
            "vehicle.make",
            OperandKind.TEXT,
            equality(),
            "Vehicle make; matches when any of the party's vehicles matches"),
    VEHICLE_MODEL(
            "vehicle.model",
            OperandKind.TEXT,
            equality(),
            "Vehicle model; matches when any of the party's vehicles matches"),
    VEHICLE_YEAR(
            "vehicle.year",
            OperandKind.NUMBER,
            numeric(),
            "Vehicle model year; matches when any of the party's vehicles matches"),
    HAS_ACTIVE_VEHICLE(
            "vehicle.hasActive",
            OperandKind.BOOLEAN,
            booleanOps(),
            "Whether the party has at least one active vehicle"),
    VEHICLE_COUNT("vehicle.count", OperandKind.NUMBER, numeric(), "Number of vehicles on the account (fleet size)"),

    // --- Service history, from the workorder fact feed (FI-3, #1133; A-follow #1144) ---
    /** Whole months since the party's most recent completed service; unset when no history. */
    MONTHS_SINCE_LAST_SERVICE(
            "service.monthsSinceLast",
            OperandKind.NUMBER,
            numeric(),
            "Whole months since the most recent completed service; unknown when the party has no history (win-back)"),
    /** Whether the party has any completed-service history at all. */
    HAS_SERVICE_HISTORY(
            "service.hasHistory",
            OperandKind.BOOLEAN,
            booleanOps(),
            "Whether the party has any completed-service history"),
    /** Whole days since the party's most recent declined recommendation; unset when none. */
    DAYS_SINCE_DECLINED_SERVICE(
            "service.daysSinceDeclined",
            OperandKind.NUMBER,
            numeric(),
            "Whole days since the most recent declined service recommendation; unknown when none"),
    /**
     * The care interval has elapsed since the last completed service (#1144). Until per-vehicle
     * care-preference intervals are replicated from pos-vehicle-inventory, the interval is the
     * module-wide {@code pos.customer.crm.service-due-months} setting. A party with no service
     * history is not due — it is unknown, and win-back targets it via
     * {@link #HAS_SERVICE_HISTORY} / {@link #MONTHS_SINCE_LAST_SERVICE} instead.
     */
    SERVICE_DUE(
            "service.due",
            OperandKind.BOOLEAN,
            booleanOps(),
            "Whether the service-due interval has elapsed since the last completed service"),

    // --- Geography, from the structured postal address (FI-4, #1135) ---
    /** ISO 3166-1 alpha-2 country code of the structured address; unset when no address. */
    ADDRESS_COUNTRY(
            "address.country",
            OperandKind.TEXT,
            presenceAndEquality(),
            "ISO 3166-1 alpha-2 country code of the structured address"),
    /** Free-text administrative area (state, province, prefecture) — matched case-insensitively. */
    ADDRESS_REGION(
            "address.region",
            OperandKind.TEXT,
            presenceAndEquality(),
            "Administrative area (state, province, prefecture) of the structured address"),
    /** City/locality of the structured address. */
    ADDRESS_CITY("address.city", OperandKind.TEXT, presenceAndEquality(), "City or locality of the structured address"),
    /** Postal code; also unset for countries without one, so IN-lists mimic service-area sets. */
    ADDRESS_POSTAL_CODE(
            "address.postalCode",
            OperandKind.TEXT,
            presenceAndEquality(),
            "Postal code of the structured address; unset for countries without one");

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
    private final String description;

    SegmentAttribute(
            String wireName, OperandKind operandKind, Set<SegmentOperator> allowedOperators, String description) {
        this.wireName = wireName;
        this.operandKind = operandKind;
        this.allowedOperators = allowedOperators;
        this.description = description;
    }

    public String wireName() {
        return wireName;
    }

    /** Marketer-facing description served by the attribute-catalog endpoint (#1144). */
    public String description() {
        return description;
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
