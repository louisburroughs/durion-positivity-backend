package com.positivity.tax.internal.service;

import com.positivity.tax.internal.dto.EvidenceRulesResponse;
import com.positivity.tax.internal.enums.EvidenceDocumentType;
import com.positivity.tax.internal.enums.EvidenceRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The evidence-rule stub (CAP:550 S32b, AW53): from which amount a document type needs a piece of evidence,
 * per country. Every rule is configuration held for expert advice (OI-4); no country is named in code.
 */
public interface TaxEvidenceRules {

    /**
     * The evidence-rules read: the rules of {@code countryCode} in effect on {@code asOf}.
     *
     * @param countryCode an upper-case country code
     * @param asOf        the date; today (the application clock) when {@code null}
     * @return the rules, empty with a {@code null} currency for a country without a profile
     */
    @NonNull
    EvidenceRulesResponse read(@NonNull String countryCode, @Nullable LocalDate asOf);

    /**
     * The rules of {@code countryCode} in effect on {@code date}, in configured order.
     *
     * @param countryCode an upper-case country code
     * @param date        the date
     * @return the rules; empty for a country without any
     */
    @NonNull
    List<ConfiguredEvidenceRule> inEffect(@NonNull String countryCode, @NonNull LocalDate date);

    /**
     * Whether a document of {@code documentType} for {@code total} needs {@code evidence} on {@code date}: a
     * rule of that evidence applying to that type is in effect and the total reaches its amount.
     *
     * @param countryCode  an upper-case country code
     * @param evidence     the evidence
     * @param documentType the document type
     * @param total        the document total, tax included, in the profile's currency
     * @param date         the date
     * @return {@code true} when the evidence is required
     */
    boolean requires(
            @NonNull String countryCode,
            @NonNull EvidenceRule evidence,
            @NonNull EvidenceDocumentType documentType,
            @NonNull BigDecimal total,
            @NonNull LocalDate date);

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
