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
 * The stated-tax plausibility stub (CAP:550 S32b, AW55): a bookkeeping control against typing errors on
 * a receipt, not a tax rule.
 * <p>
 * A stated amount is implausible ({@code 422 TAX_AMOUNT_IMPLAUSIBLE}) when it, or the sum of all of them,
 * reaches the receipt total {@code T}, or when it is above {@code T × r / (1 + r)} rounded up to the minor
 * unit plus {@code pos.tax.plausibility.tolerance-minor-units}. {@code r} is the rate of the one row of the
 * regime's tax types in effect for the region on the date, and {@code 0} for a regime the region's rows do
 * not levy. There is no combined bound. A region with no row on the date answers {@code RATE_UNAVAILABLE}
 * and only the total check applies.
 * <p>
 * The check is pure: it reads no tenant data, changes no state and emits no event. The supplier's number
 * is never echoed, logged or stored; the outcome counter {@code pos.tax.plausibility.outcome} is tagged by
 * outcome only.
 */
@Slf4j
@Service
public class TaxPlausibilityService {

    /** Outcome: every stated amount is within its bound. */
    public static final String PLAUSIBLE = "PLAUSIBLE";

    /** Outcome: the region has no rate row on the date, so only the total check applied. */
    public static final String RATE_UNAVAILABLE = "RATE_UNAVAILABLE";

    /** The refusal code, used as the counter tag of a refused check. */
    public static final String TAX_AMOUNT_IMPLAUSIBLE = "TAX_AMOUNT_IMPLAUSIBLE";

    /** Name of the Micrometer counter, tagged {@code outcome} only. */
    public static final String OUTCOME_COUNTER = "pos.tax.plausibility.outcome";

    private static final String SOURCE = "STUB";
    private static final String STATED_TAXES = "statedTaxes";

    private final TaxCountryProfiles profiles;
    private final RegistrationNumberShapes shapes;
    private final TaxEvidenceRules evidenceRules;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;
    private final int toleranceMinorUnits;

    public TaxPlausibilityService(
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

    /**
     * Checks the tax stated on a receipt.
     *
     * @param request the receipt, already shape-validated
     * @return the outcome, the rates and maximums used, and the supplier-number answers
     * @throws TaxRequestInvalidException    when the request does not fit the country's profile (400)
     * @throws TaxAmountImplausibleException when a stated amount is implausible (422)
     */
    @NonNull
    public PlausibilityCheckResponse check(@NonNull PlausibilityCheckRequest request) {
        CountryTaxProfile profile = profiles.profile(request.countryCode())
                .orElseThrow(() -> new TaxRequestInvalidException(List.of(
                        new ApiError.FieldError("countryCode", "no tax profile is configured for the country"))));
        List<StatedTax> stated = request.statedTaxes() == null ? List.of() : request.statedTaxes();
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
                    implausible.putIfAbsent(
                            amountField(i), "must not exceed the plausible maximum " + maximum.toPlainString());
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

    private static void validate(
            @NonNull PlausibilityCheckRequest request,
            @NonNull CountryTaxProfile profile,
            @NonNull List<StatedTax> stated) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        int exponent = profile.currencyExponent();
        if (!profile.currency().equals(request.currencyCode())) {
            errors.add(new ApiError.FieldError("currencyCode", "must be the country's currency " + profile.currency()));
        }
        if (scale(request.receiptTotal()) > exponent) {
            errors.add(new ApiError.FieldError("receiptTotal", "has more decimals than the currency allows"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < stated.size(); i++) {
            StatedTax tax = stated.get(i);
            if (scale(tax.amount()) > exponent) {
                errors.add(new ApiError.FieldError(amountField(i), "has more decimals than the currency allows"));
            }
            String regimeField = STATED_TAXES + "[" + i + "].regime";
            boolean declared =
                    profile.regimes().stream().anyMatch(entry -> entry.regime().equals(tax.regime()));
            if (!declared) {
                errors.add(new ApiError.FieldError(regimeField, "is not a regime declared for the country"));
            } else if (!seen.add(tax.regime())) {
                errors.add(new ApiError.FieldError(regimeField, "is stated more than once"));
            }
        }
        if (!errors.isEmpty()) {
            throw new TaxRequestInvalidException(errors);
        }
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
