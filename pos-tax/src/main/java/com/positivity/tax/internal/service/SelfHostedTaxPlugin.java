package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxCalculationResponse.JurisdictionTax;
import com.positivity.tax.common.dto.TaxCalculationResponse.LineItemTax;
import com.positivity.tax.common.dto.TaxJurisdiction;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.dto.TaxRateComponent;
import com.positivity.tax.common.dto.TaxRateLookupResponse;
import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.exception.TaxCurrencyNotSupportedException;
import com.positivity.tax.internal.exception.TaxJurisdictionNotConfiguredException;
import com.positivity.tax.internal.service.TaxCountryProfiles.ConfiguredRate;
import com.positivity.tax.internal.service.TaxCountryProfiles.CountryTaxProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The configuration-driven self-hosted tax plug-in (ADR-0071 §2; CAP:550 S32a), one instance per
 * profiled country, id {@code <country>_SELF}.
 * <p>
 * A stub (spec AW48): it prices from the country's configured rate rows and decides no tax law.
 * <ul>
 *   <li><b>Calculation</b> prices every line from the rows of the destination region in effect on
 *       the transaction date, one typed jurisdiction row per tax type, HALF_UP at the currency's
 *       minor-unit exponent per row; line and total tax are the sums of the rounded rows. With no
 *       row it refuses with {@link TaxJurisdictionNotConfiguredException}, never another
 *       country's rates. {@code REFUND} uses the same rows.</li>
 *   <li><b>Currency</b>: the request's {@code currencyCode} must be the profile currency, otherwise
 *       {@link TaxCurrencyNotSupportedException} (422 {@code CURRENCY_NOT_SUPPORTED}, ADR-0067 PC-9).</li>
 *   <li><b>Exemptions</b>: a line that claims one (a reason code or certificate id) is taxed and
 *       flagged as denied; which exemptions apply is held for expert advice (OI-4). A bare
 *       {@code taxExempt} line, the caller's own declaration, is taxed zero with zero-amount rows.</li>
 *   <li><b>Commit and void</b> are logged no-ops that always succeed; the lifecycle log row is
 *       written by {@link TaxProviderLifecycleService}.</li>
 * </ul>
 */
@Slf4j
public class SelfHostedTaxPlugin implements TaxProviderClient {

    /** {@code source} of every rate this plug-in answers: a placeholder, not tax law. */
    static final String SOURCE = "STUB";

    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal PERCENT_FACTOR = BigDecimal.valueOf(100);

    private final CountryTaxProfile profile;
    private final String providerName;
    private final Clock clock;

    SelfHostedTaxPlugin(@NonNull CountryTaxProfile profile, @NonNull Clock clock) {
        this.profile = profile;
        this.providerName = TaxCountryProfiles.selfPluginId(profile.countryCode());
        this.clock = clock;
    }

    @Override
    @NonNull
    public String providerName() {
        return providerName;
    }

    /**
     * The country this plug-in serves.
     *
     * @return the upper-case country code
     */
    @NonNull
    public String countryCode() {
        return profile.countryCode();
    }

    @Override
    @NonNull
    public TaxCalculationResponse estimate(@NonNull TaxCalculationRequest request) {
        requireProfileCurrency(request.getCurrencyCode());
        LocalDate date = TaxTransactionDates.resolve(request.getTransactionDate(), clock);
        List<ConfiguredRate> rows = rowsFor(request.getStateCode(), date);
        int scale = profile.currencyExponent();

        List<LineItemTax> lineTaxes = new ArrayList<>();
        BigDecimal[] jurisdictionTotals = zeros(rows.size(), scale);
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxBase = BigDecimal.ZERO;
        for (TaxLineItem item : request.getLineItems()) {
            BigDecimal itemSubtotal = item.getSubtotal();
            subtotal = subtotal.add(itemSubtotal);
            ExemptionReasonCode claimedReason = claimedReason(request, item);
            boolean claim = claimedReason != null || claimsCertificate(request, item);
            // A claim is never honoured here (OI-4): it is taxed and flagged. A bare taxExempt
            // flag is the caller's own non-taxable declaration and is taxed zero.
            boolean exempt = item.isTaxExempt() && !claim;
            if (!exempt) {
                taxBase = taxBase.add(itemSubtotal);
            }
            lineTaxes.add(priceLine(item, rows, exempt, claim, claimedReason, jurisdictionTotals));
        }

        BigDecimal totalTax = lineTaxes.stream()
                .map(LineItemTax::getTaxAmount)
                .reduce(BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP), BigDecimal::add);
        BigDecimal effectiveTaxRate = taxBase.signum() > 0
                ? totalTax.divide(taxBase, 4, RoundingMode.HALF_UP)
                        .multiply(PERCENT_FACTOR)
                        .setScale(PERCENT_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        TaxCalculationType calculationType =
                request.getCalculationType() != null ? request.getCalculationType() : TaxCalculationType.SALE;

        return TaxCalculationResponse.builder()
                .subtotal(subtotal)
                .totalTax(totalTax)
                .total(subtotal.add(totalTax))
                .effectiveTaxRate(effectiveTaxRate)
                .jurisdictions(jurisdictions(request, rows, jurisdictionTotals))
                .lineItemTaxes(lineTaxes)
                .testMode(true)
                .calculatedAt(Instant.now(clock))
                .referenceId(request.getReferenceId())
                .referenceType(request.getReferenceType())
                .calculationType(calculationType)
                .originalReferenceId(
                        calculationType == TaxCalculationType.REFUND ? request.getOriginalReferenceId() : null)
                .build();
    }

    @Override
    @NonNull
    public TaxCalculationResponse refund(@NonNull TaxCalculationRequest request, @NonNull UUID originalReferenceId) {
        return estimate(request);
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult commit(@NonNull UUID referenceId) {
        log.info("Tax plug-in {} commit for reference {}: no-op", providerName, referenceId);
        return new TaxProviderTransactionResult(
                referenceId, TaxProviderTransactionStatus.COMMITTED, null, providerName + " commit (no-op)");
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId) {
        log.info("Tax plug-in {} void for reference {}: no-op", providerName, referenceId);
        return new TaxProviderTransactionResult(
                referenceId, TaxProviderTransactionStatus.VOIDED, null, providerName + " void (no-op)");
    }

    /**
     * Rate-only lookup: one typed component per tax type in effect for the region on {@code asOf}.
     *
     * @param regionCode the region; may be {@code null}
     * @param city       echoed
     * @param postalCode echoed
     * @param asOf       the effective date
     * @return the typed components, {@code source = STUB}
     * @throws TaxJurisdictionNotConfiguredException when no row is in effect
     */
    @NonNull
    public TaxRateLookupResponse lookupRates(
            @Nullable String regionCode, @Nullable String city, @NonNull String postalCode, @NonNull LocalDate asOf) {
        List<TaxRateComponent> components = rowsFor(regionCode, asOf).stream()
                .map(row -> new TaxRateComponent(
                        row.definition().jurisdictionType(),
                        row.rate(),
                        row.taxType(),
                        row.definition().inputTaxRecoverable()))
                .toList();
        BigDecimal combinedRate =
                components.stream().map(TaxRateComponent::rate).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TaxRateLookupResponse(
                profile.countryCode(), regionCode, city, postalCode, asOf, components, combinedRate, SOURCE);
    }

    /**
     * Rows are rounded at the profile currency's exponent and never converted, so a request in any
     * other currency is refused (ADR-0067 PC-9); there is no implicit currency (R-2).
     */
    private void requireProfileCurrency(@Nullable String currencyCode) {
        if (currencyCode == null || !profile.currency().equalsIgnoreCase(currencyCode.trim())) {
            throw new TaxCurrencyNotSupportedException("Tax for country " + profile.countryCode() + " is calculated in "
                    + profile.currency() + "; the request states " + currencyCode);
        }
    }

    @NonNull
    private List<ConfiguredRate> rowsFor(@Nullable String regionCode, @NonNull LocalDate date) {
        List<ConfiguredRate> rows = profile.ratesInEffect(regionCode, date);
        if (rows.isEmpty()) {
            throw new TaxJurisdictionNotConfiguredException("No tax rate is configured for country "
                    + profile.countryCode() + ", region " + (regionCode == null ? "(none)" : regionCode)
                    + ", on " + date);
        }
        return rows;
    }

    @NonNull
    private LineItemTax priceLine(
            @NonNull TaxLineItem item,
            @NonNull List<ConfiguredRate> rows,
            boolean exempt,
            boolean denied,
            @Nullable ExemptionReasonCode claimed,
            @NonNull BigDecimal[] jurisdictionTotals) {
        int scale = profile.currencyExponent();
        BigDecimal itemSubtotal = item.getSubtotal();
        BigDecimal lineTax = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        List<JurisdictionTax> cells = new ArrayList<>(rows.size());
        for (int j = 0; j < rows.size(); j++) {
            ConfiguredRate row = rows.get(j);
            BigDecimal amount = exempt
                    ? BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
                    : itemSubtotal.multiply(row.rate()).setScale(scale, RoundingMode.HALF_UP);
            lineTax = lineTax.add(amount);
            jurisdictionTotals[j] = jurisdictionTotals[j].add(amount);
            cells.add(JurisdictionTax.builder()
                    .jurisdictionType(row.definition().jurisdictionType())
                    .code(jurisdictionCode(row))
                    .rate(row.rate())
                    .amount(amount)
                    .exempt(exempt)
                    .taxType(row.taxType())
                    .inputTaxRecoverable(row.definition().inputTaxRecoverable())
                    .build());
        }
        return LineItemTax.builder()
                .lineItemId(item.getLineItemId())
                .subtotal(itemSubtotal)
                .taxAmount(lineTax)
                .total(itemSubtotal.add(lineTax))
                .taxExempt(exempt)
                .exemptionDenied(denied)
                .exemptionReasonCode(denied ? claimed : null)
                .jurisdictions(cells)
                .build();
    }

    @NonNull
    private List<TaxJurisdiction> jurisdictions(
            @NonNull TaxCalculationRequest request,
            @NonNull List<ConfiguredRate> rows,
            @NonNull BigDecimal[] jurisdictionTotals) {
        List<TaxJurisdiction> result = new ArrayList<>(rows.size());
        for (int j = 0; j < rows.size(); j++) {
            ConfiguredRate row = rows.get(j);
            result.add(TaxJurisdiction.builder()
                    .countryCode(request.getCountryCode())
                    .regionCode(request.getStateCode())
                    .city(request.getCity())
                    .postalCode(request.getPostalCode())
                    .line1(request.getAddress())
                    .jurisdictionType(row.definition().jurisdictionType())
                    .taxRate(row.rate().multiply(PERCENT_FACTOR))
                    .taxAmount(jurisdictionTotals[j])
                    .build());
        }
        return result;
    }

    /**
     * The taxing-authority code of a row: the country for a country-level tax type, otherwise the
     * row's region.
     */
    @NonNull
    private String jurisdictionCode(@NonNull ConfiguredRate row) {
        return row.definition().jurisdictionType() == TaxJurisdictionType.COUNTRY
                ? profile.countryCode()
                : row.regionCode();
    }

    @Nullable
    private static ExemptionReasonCode claimedReason(
            @NonNull TaxCalculationRequest request, @NonNull TaxLineItem item) {
        if (item.getExemptionReasonCode() != null) {
            return item.getExemptionReasonCode();
        }
        TaxCalculationRequest.CustomerExemption customer = request.getCustomerExemption();
        return customer == null ? null : customer.getReasonCode();
    }

    private static boolean claimsCertificate(@NonNull TaxCalculationRequest request, @NonNull TaxLineItem item) {
        TaxCalculationRequest.CustomerExemption customer = request.getCustomerExemption();
        return item.getExemptionCertificateId() != null || (customer != null && customer.getCertificateId() != null);
    }

    @NonNull
    private static BigDecimal[] zeros(int size, int scale) {
        BigDecimal[] values = new BigDecimal[size];
        Arrays.fill(values, BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP));
        return values;
    }
}
