package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxRateComponent;
import com.positivity.tax.common.dto.TaxRateLookupResponse;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.exception.TaxRateLookupUnsupportedException;
import com.positivity.tax.service.TaxProviderClient;
import com.positivity.tax.service.TaxRateLookupService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link TaxRateLookupService} (issue #1522).
 * <p>
 * Test mode resolves rates via {@link TestModeRateResolver}, the same rule-matching logic
 * {@link TestModeTaxCalculator} uses for {@code /calculate} — extracted so both share it rather
 * than duplicating the jurisdiction/schedule/default resolution. Every other configured
 * provider (Avalara, the legacy external stub) has no rate-only call implemented today, so
 * lookup fails loudly with {@link TaxRateLookupUnsupportedException} instead of synthesizing an
 * estimate.
 */
@Slf4j
@Service
public class TaxRateLookupServiceImpl implements TaxRateLookupService {

    private final TaxProperties properties;
    private final TaxProviderSelector providerSelector;
    private final TestModeRateResolver rateResolver;
    private final Clock clock;

    public TaxRateLookupServiceImpl(
            TaxProperties properties,
            TaxProviderSelector providerSelector,
            TestModeRateResolver rateResolver,
            Clock clock) {
        this.properties = properties;
        this.providerSelector = providerSelector;
        this.rateResolver = rateResolver;
        this.clock = clock;
    }

    @Override
    @NonNull
    public TaxRateLookupResponse lookupRates(
            @NonNull String countryCode,
            @Nullable String regionCode,
            @Nullable String city,
            @NonNull String postalCode,
            @Nullable LocalDate asOf) {
        TaxProviderClient provider = providerSelector.select();
        if (!properties.getTestMode().isEnabled()) {
            throw new TaxRateLookupUnsupportedException(
                    "Jurisdiction rate lookup is not supported by the active tax provider ("
                            + provider.providerName()
                            + "); it only supports full transaction tax calculation today.");
        }

        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now(clock);
        log.info("Resolving jurisdiction rates in TEST MODE for postal code(mask): {}", maskForLog(postalCode));

        TestModeRateResolver.ResolvedRates resolvedRates =
                rateResolver.resolveRates(regionCode, city, postalCode, effectiveAsOf);
        List<TestModeRateResolver.JurisdictionSpec> specs =
                rateResolver.determineJurisdictionSpecs(resolvedRates.rates());

        List<TaxRateComponent> components = specs.stream()
                .map(spec -> new TaxRateComponent(spec.type(), spec.rate()))
                .toList();
        BigDecimal combinedRate =
                components.stream().map(TaxRateComponent::rate).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new TaxRateLookupResponse(
                countryCode,
                regionCode,
                city,
                postalCode,
                effectiveAsOf,
                components,
                combinedRate,
                provider.providerName());
    }

    private static String maskForLog(String value) {
        if (value == null) {
            return "null";
        }
        String sanitized = value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
