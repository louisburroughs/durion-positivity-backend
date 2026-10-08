package com.positivity.tax.internal.service;

import com.positivity.shared.error.ApiError;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.PlausibilityCheckRequest;
import com.positivity.tax.internal.dto.PlausibilityCheckRequest.StatedTax;
import com.positivity.tax.internal.dto.PlausibilityCheckResponse;
import com.positivity.tax.internal.dto.PlausibilityCheckResponse.RateUsed;
import com.positivity.tax.internal.dto.PlausibilityCheckResponse.RegimeMaximum;
import com.positivity.tax.internal.enums.EvidenceDocumentType;
import com.positivity.tax.internal.enums.EvidenceRule;
import com.positivity.tax.internal.exception.TaxAmountImplausibleException;
import com.positivity.tax.internal.exception.TaxRequestInvalidException;
import com.positivity.tax.internal.exception.TaxRequestUnprocessableException;
import com.positivity.tax.internal.service.TaxCountryProfiles.ConfiguredRate;
import com.positivity.tax.internal.service.TaxCountryProfiles.CountryTaxProfile;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The configuration-backed {@link TaxPlausibilityService} (CAP:550 S32b, AW55).
 * <p>
 * Refusals come in ADR-0017 order: a repeated regime is request shape (400 {@code VALIDATION_ERROR}); a
 * country without a profile (422 {@code TAX_JURISDICTION_NOT_CONFIGURED}), another currency (422
 * {@code CURRENCY_NOT_SUPPORTED}), an amount finer than the currency (422
 * {@code AMOUNT_PRECISION_EXCEEDS_CURRENCY}, ADR-0067 PC-6) and an undeclared regime (422
 * {@code TAX_REGIME_NOT_DECLARED}) are states of the referenced configuration; an implausible amount is 422
 * {@code TAX_AMOUNT_IMPLAUSIBLE}. The outcome counter {@code pos.tax.plausibility.outcome} is tagged by
 * outcome only, and the supplier's number is never echoed, logged or stored.
 */
@Slf4j
@Service
public class TaxPlausibilityServiceImpl implements TaxPlausibilityService {

    private static final String SOURCE = "STUB";
    private static final String STATED_TAXES = "statedTaxes";

    private final TaxCountryProfiles profiles;
    private final RegistrationNumberShapes shapes;
    private final TaxEvidenceRules evidenceRules;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;
    private final int toleranceMinorUnits;

    public TaxPlausibilityServiceImpl(
            @NonNull TaxProperties properties,
            @NonNull TaxCountryProfiles profiles,
            @NonNull RegistrationNumberShapes shapes,
            @NonNull TaxEvidenceRules evidenceRules,
            @NonNull Clock clock,
            @NonNull ObjectProvider<MeterRegistry> meterRegistry) {
        Integer tolerance = properties.getPlausibility().getToleranceMinorUnits();
        if (tolerance == null || tolerance < 0) {
            throw new IllegalStateException("Invalid tax configuration pos.tax.plausibility.tolerance-minor-units: "
                    + "a tolerance of zero or more minor units is required");
        }
        this.toleranceMinorUnits = tolerance;
        this.profiles = profiles;
        this.shapes = shapes;
        this.evidenceRules = evidenceRules;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @Override
    @NonNull
    public PlausibilityCheckResponse check(@NonNull PlausibilityCheckRequest request) {
        List<StatedTax> stated = request.statedTaxes() == null ? List.of() : request.statedTaxes();
        rejectRepeatedRegimes(stated);
        CountryTaxProfile profile = profiles.profile(request.countryCode())
                .orElseThrow(() -> new TaxRequestUnprocessableException(
                        TaxRequestUnprocessableException.JURISDICTION_NOT_CONFIGURED,
                        "No tax profile is configured for the country",
                        List.of(new ApiError.FieldError(
                                "countryCode", "no tax profile is configured for the country"))));
        validate(request, profile, stated);

        BigDecimal total = request.receiptTotal();
        LocalDate asOf = request.asOf() == null ? LocalDate.now(clock) : request.asOf();
        List<ConfiguredRate> rows = profile.ratesInEffect(request.regionCode(), asOf);
        boolean rateAvailable = !rows.isEmpty();

        Map<String, String> implausible = new LinkedHashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < stated.size(); i++) {
            BigDecimal amount = stated.get(i).amount();
            sum = sum.add(amount);
            if (amount.compareTo(total) >= 0) {
                implausible.put(amountField(i), "must be less than the receipt total " + total.toPlainString());
            }
        }
        if (stated.size() > 1 && sum.compareTo(total) >= 0) {
            implausible.put(
                    STATED_TAXES,
                    "the stated amounts together must be less than the receipt total " + total.toPlainString());
        }

        List<RateUsed> ratesUsed = new ArrayList<>();
        List<RegimeMaximum> maximums = new ArrayList<>();
        if (rateAvailable) {
            for (int i = 0; i < stated.size(); i++) {
                StatedTax tax = stated.get(i);
                Optional<ConfiguredRate> row = rows.stream()
                        .filter(rate -> tax.regime().equals(rate.regime()))
                        .findFirst();
                BigDecimal rate = row.map(ConfiguredRate::rate).orElse(BigDecimal.ZERO);
                row.ifPresent(r -> ratesUsed.add(new RateUsed(tax.regime(), r.taxType(), r.rate())));
                BigDecimal maximum = maximum(total, rate, profile.currencyExponent());
                maximums.add(new RegimeMaximum(tax.regime(), maximum));
                if (tax.amount().compareTo(maximum) > 0) {
                    // Overwrites the total check's message: with a rate, every refused amount carries its maximum.
                    implausible.put(amountField(i), "must not exceed the plausible maximum " + maximum.toPlainString());
                }
            }
        }

        if (!implausible.isEmpty()) {
            count(TAX_AMOUNT_IMPLAUSIBLE);
            throw new TaxAmountImplausibleException(implausible.entrySet().stream()
                    .map(entry -> new ApiError.FieldError(entry.getKey(), entry.getValue()))
                    .toList());
        }

        String outcome = rateAvailable ? PLAUSIBLE : RATE_UNAVAILABLE;
        count(outcome);
        log.debug("Plausibility check answered: country={} outcome={}", profile.countryCode(), outcome);
        return new PlausibilityCheckResponse(
                outcome,
                List.copyOf(ratesUsed),
                List.copyOf(maximums),
                evidenceRules.requires(
                        profile.countryCode(),
                        EvidenceRule.SUPPLIER_REGISTRATION_NUMBER,
                        EvidenceDocumentType.DRAWER_RECEIPT,
                        total,
                        asOf),
                supplierNumberWellFormed(profile.countryCode(), request.supplierRegistrationNumber()),
                asOf,
                SOURCE);
    }

    /**
     * The plausible maximum of one stated amount: {@code T × r / (1 + r)} rounded up to the minor unit,
     * plus the tolerance.
     */
    @NonNull
    private BigDecimal maximum(@NonNull BigDecimal total, @NonNull BigDecimal rate, int exponent) {
        BigDecimal included = total.multiply(rate).divide(BigDecimal.ONE.add(rate), exponent, RoundingMode.CEILING);
        return included.add(BigDecimal.valueOf(toleranceMinorUnits).movePointLeft(exponent));
    }

    @Nullable
    private Boolean supplierNumberWellFormed(@NonNull String countryCode, @Nullable String number) {
        if (number == null) {
            return null;
        }
        return shapes.supplierRegime(countryCode)
                .map(regime -> shapes.wellFormed(regime, number))
                .orElse(null);
    }

    /** A regime stated twice is request shape: 400 {@code VALIDATION_ERROR}, before any configuration is read. */
    private static void rejectRepeatedRegimes(@NonNull List<StatedTax> stated) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < stated.size(); i++) {
            if (!seen.add(stated.get(i).regime())) {
                errors.add(new ApiError.FieldError(STATED_TAXES + "[" + i + "].regime", "is stated more than once"));
            }
        }
        if (!errors.isEmpty()) {
            throw new TaxRequestInvalidException(errors);
        }
    }

    /**
     * The 422 checks against the country's profile, in order: the currency (ADR-0067 PC-9), each amount's
     * precision (ADR-0067 PC-6), then each regime. Each refusal names every offending field of its kind.
     */
    private static void validate(
            @NonNull PlausibilityCheckRequest request,
            @NonNull CountryTaxProfile profile,
            @NonNull List<StatedTax> stated) {
        if (!profile.currency().equals(request.currencyCode())) {
            throw new TaxRequestUnprocessableException(
                    TaxRequestUnprocessableException.CURRENCY_NOT_SUPPORTED,
                    "currencyCode is not the country's configured currency",
                    List.of(new ApiError.FieldError(
                            "currencyCode", "must be the country's currency " + profile.currency())));
        }
        int exponent = profile.currencyExponent();
        List<ApiError.FieldError> precision = new ArrayList<>();
        if (scale(request.receiptTotal()) > exponent) {
            precision.add(new ApiError.FieldError("receiptTotal", precisionMessage(profile)));
        }
        for (int i = 0; i < stated.size(); i++) {
            if (scale(stated.get(i).amount()) > exponent) {
                precision.add(new ApiError.FieldError(amountField(i), precisionMessage(profile)));
            }
        }
        if (!precision.isEmpty()) {
            throw new TaxRequestUnprocessableException(
                    TaxRequestUnprocessableException.PRECISION_EXCEEDS_CURRENCY,
                    "An amount has more decimal places than the currency allows",
                    precision);
        }
        List<ApiError.FieldError> undeclared = new ArrayList<>();
        for (int i = 0; i < stated.size(); i++) {
            String regime = stated.get(i).regime();
            if (profile.regimes().stream().noneMatch(entry -> entry.regime().equals(regime))) {
                undeclared.add(new ApiError.FieldError(
                        STATED_TAXES + "[" + i + "].regime", "is not a regime declared for the country"));
            }
        }
        if (!undeclared.isEmpty()) {
            throw new TaxRequestUnprocessableException(
                    TaxRequestUnprocessableException.REGIME_NOT_DECLARED,
                    "A stated regime is not declared for the country",
                    undeclared);
        }
    }

    @NonNull
    private static String precisionMessage(@NonNull CountryTaxProfile profile) {
        return "has more decimal places than " + profile.currency() + " allows (" + profile.currencyExponent() + ")";
    }

    private static int scale(@NonNull BigDecimal amount) {
        return Math.max(0, amount.stripTrailingZeros().scale());
    }

    @NonNull
    private static String amountField(int index) {
        return STATED_TAXES + "[" + index + "].amount";
    }

    private void count(@NonNull String outcome) {
        if (meterRegistry != null) {
            Counter.builder(OUTCOME_COUNTER)
                    .description("Stated-tax plausibility checks, by outcome")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
