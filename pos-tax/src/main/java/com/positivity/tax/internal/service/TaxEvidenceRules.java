package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.config.TaxProperties.CountryProfile;
import com.positivity.tax.internal.config.TaxProperties.EvidenceRuleRow;
import com.positivity.tax.internal.dto.EvidenceRulesResponse;
import com.positivity.tax.internal.dto.EvidenceRulesResponse.EvidenceRuleEntry;
import com.positivity.tax.internal.enums.EvidenceDocumentType;
import com.positivity.tax.internal.enums.EvidenceRule;
import com.positivity.tax.internal.service.TaxCountryProfiles.CountryTaxProfile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The evidence-rule stub (CAP:550 S32b, AW53): from which amount a document type needs a piece of
 * evidence, per country, answered from {@code pos.tax.countries.<country>.evidence-rules}.
 * <p>
 * The constructor is the startup check: an unknown {@code rule} or {@code applies-to} value, a
 * {@code from-amount} that is missing, not above zero or finer than the currency's minor unit, an empty
 * {@code applies-to}, an end before the start, or two rows of one rule and document type in effect on the
 * same date each fail startup, naming the property. Amounts are in the profile's currency. Every row is a
 * placeholder held for expert advice (OI-4); no country is named in code.
 */
@Component
public class TaxEvidenceRules {

    private static final String COUNTRIES = "pos.tax.countries.";

    private static final String SOURCE = "STUB";

    private final Map<String, List<ConfiguredEvidenceRule>> rules;
    private final TaxCountryProfiles profiles;
    private final Clock clock;

    public TaxEvidenceRules(
            @NonNull TaxProperties properties, @NonNull TaxCountryProfiles profiles, @NonNull Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
        Map<String, List<ConfiguredEvidenceRule>> built = new LinkedHashMap<>();
        properties.getCountries().forEach((country, source) -> {
            CountryTaxProfile profile = profiles.profile(country).orElseThrow();
            built.put(profile.countryCode(), validateCountry(country, source, profile));
        });
        this.rules = Map.copyOf(built);
    }

    /**
     * The evidence-rules read: the rules of {@code countryCode} in effect on {@code asOf}.
     *
     * @param countryCode an upper-case country code
     * @param asOf        the date; today (the application clock) when {@code null}
     * @return the rules, empty with a {@code null} currency for a country without a profile
     */
    @NonNull
    public EvidenceRulesResponse read(@NonNull String countryCode, @Nullable LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now(clock) : asOf;
        List<EvidenceRuleEntry> entries = inEffect(countryCode, date).stream()
                .map(rule -> new EvidenceRuleEntry(
                        rule.rule().name(),
                        rule.fromAmount(),
                        rule.appliesTo().stream().map(Enum::name).toList(),
                        rule.effectiveFrom(),
                        rule.effectiveTo()))
                .toList();
        String currency =
                profiles.profile(countryCode).map(CountryTaxProfile::currency).orElse(null);
        return new EvidenceRulesResponse(countryCode, date, currency, entries, SOURCE);
    }

    /**
     * The rules of {@code countryCode} in effect on {@code date}, in configured order.
     *
     * @param countryCode an upper-case country code
     * @param date        the date
     * @return the rules; empty for a country without any
     */
    @NonNull
    public List<ConfiguredEvidenceRule> inEffect(@NonNull String countryCode, @NonNull LocalDate date) {
        return rules.getOrDefault(countryCode, List.of()).stream()
                .filter(rule -> rule.inEffectOn(date))
                .toList();
    }

    /**
     * Whether a document of {@code documentType} for {@code total} needs {@code evidence} on {@code date}:
     * a rule of that evidence applying to that type is in effect and the total reaches its amount.
     *
     * @param countryCode  an upper-case country code
     * @param evidence     the evidence
     * @param documentType the document type
     * @param total        the document total, tax included, in the profile's currency
     * @param date         the date
     * @return {@code true} when the evidence is required
     */
    public boolean requires(
            @NonNull String countryCode,
            @NonNull EvidenceRule evidence,
            @NonNull EvidenceDocumentType documentType,
            @NonNull BigDecimal total,
            @NonNull LocalDate date) {
        return inEffect(countryCode, date).stream()
                .anyMatch(rule -> rule.rule() == evidence
                        && rule.appliesTo().contains(documentType)
                        && total.compareTo(rule.fromAmount()) >= 0);
    }

    // ---------------------------------------------------------------------------------------
    // Startup check
    // ---------------------------------------------------------------------------------------

    @NonNull
    private static List<ConfiguredEvidenceRule> validateCountry(
            @NonNull String country, @Nullable CountryProfile source, @NonNull CountryTaxProfile profile) {
        List<EvidenceRuleRow> rows = source == null ? List.of() : source.getEvidenceRules();
        String prefix = COUNTRIES + country + ".evidence-rules";
        List<ConfiguredEvidenceRule> validated = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String property = prefix + "[" + i + "]";
            EvidenceRuleRow row = rows.get(i) == null ? new EvidenceRuleRow() : rows.get(i);
            EvidenceRule rule = parse(EvidenceRule.class, property + ".rule", row.getRule());
            BigDecimal fromAmount = row.getFromAmount();
            if (fromAmount == null || fromAmount.signum() <= 0) {
                throw invalid(property + ".from-amount", "a from-amount above zero is required");
            }
            if (fromAmount.stripTrailingZeros().scale() > profile.currencyExponent()) {
                throw invalid(
                        property + ".from-amount",
                        "has more decimals than " + profile.currency() + " allows (" + profile.currencyExponent()
                                + ")");
            }
            List<String> appliesTo = row.getAppliesTo();
            if (appliesTo.isEmpty()) {
                throw invalid(property + ".applies-to", "at least one document type is required");
            }
            Set<EvidenceDocumentType> types = EnumSet.noneOf(EvidenceDocumentType.class);
            for (int t = 0; t < appliesTo.size(); t++) {
                types.add(parse(EvidenceDocumentType.class, property + ".applies-to[" + t + "]", appliesTo.get(t)));
            }
            if (row.getEffectiveFrom() != null
                    && row.getEffectiveTo() != null
                    && row.getEffectiveTo().isBefore(row.getEffectiveFrom())) {
                throw invalid(property + ".effective-to", "effective-to is before effective-from");
            }
            ConfiguredEvidenceRule candidate = new ConfiguredEvidenceRule(
                    i,
                    rule,
                    fromAmount.setScale(profile.currencyExponent()),
                    Collections.unmodifiableSet(types),
                    row.getEffectiveFrom(),
                    row.getEffectiveTo());
            rejectOverlap(prefix, validated, candidate);
            validated.add(candidate);
        }
        return List.copyOf(validated);
    }

    private static void rejectOverlap(
            @NonNull String prefix,
            @NonNull List<ConfiguredEvidenceRule> earlier,
            @NonNull ConfiguredEvidenceRule candidate) {
        for (ConfiguredEvidenceRule other : earlier) {
            if (other.rule() != candidate.rule() || !other.overlaps(candidate)) {
                continue;
            }
            for (EvidenceDocumentType type : candidate.appliesTo()) {
                if (other.appliesTo().contains(type)) {
                    throw invalid(
                            prefix + "[" + candidate.index() + "]",
                            "overlaps " + prefix + "[" + other.index() + "]: two rows of rule " + candidate.rule()
                                    + " for " + type + " are in effect on the same date");
                }
            }
        }
    }

    @NonNull
    private static <E extends Enum<E>> E parse(
            @NonNull Class<E> type, @NonNull String property, @Nullable String value) {
        if (value != null) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(value.trim())) {
                    return constant;
                }
            }
        }
        throw invalid(property, "'" + value + "' is not one of " + EnumSet.allOf(type));
    }

    @NonNull
    private static IllegalStateException invalid(@NonNull String property, @NonNull String reason) {
        return new IllegalStateException("Invalid tax configuration " + property + ": " + reason);
    }

    /**
     * One validated evidence rule.
     *
     * @param index         the row's index in configuration (for messages)
     * @param rule          the evidence required
     * @param fromAmount    the amount from which it applies, at the currency's exponent
     * @param appliesTo     the document types it applies to, iterated in declaration order
     * @param effectiveFrom inclusive first date; {@code null} for always
     * @param effectiveTo   inclusive last date; {@code null} for open-ended
     */
    public record ConfiguredEvidenceRule(
            int index,
            @NonNull EvidenceRule rule,
            @NonNull BigDecimal fromAmount,
            @NonNull Set<EvidenceDocumentType> appliesTo,
            @Nullable LocalDate effectiveFrom,
            @Nullable LocalDate effectiveTo) {

        /**
         * Whether the rule is in effect on {@code date} (both ends inclusive).
         *
         * @param date the date
         * @return {@code true} when in effect
         */
        public boolean inEffectOn(@NonNull LocalDate date) {
            return (effectiveFrom == null || !date.isBefore(effectiveFrom))
                    && (effectiveTo == null || !date.isAfter(effectiveTo));
        }

        boolean overlaps(@NonNull ConfiguredEvidenceRule other) {
            LocalDate thisStart = effectiveFrom == null ? LocalDate.MIN : effectiveFrom;
            LocalDate thisEnd = effectiveTo == null ? LocalDate.MAX : effectiveTo;
            LocalDate otherStart = other.effectiveFrom() == null ? LocalDate.MIN : other.effectiveFrom();
            LocalDate otherEnd = other.effectiveTo() == null ? LocalDate.MAX : other.effectiveTo();
            return !thisStart.isAfter(otherEnd) && !otherStart.isAfter(thisEnd);
        }
    }
}
