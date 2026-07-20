package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxJurisdiction;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.internal.config.TaxProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Test mode tax calculator using configurable rates.
 * <p>
 * This calculator is used when test mode is enabled. It applies simple
 * percentage-based tax calculations using rates configured in application
 * properties.
 */
@Slf4j
@Component
public class TestModeTaxCalculator {

    /** Currency scale for all monetary amounts. */
    private static final int MONEY_SCALE = 2;

    /** Percentage-point conversion factor for decimal-fraction rates. */
    private static final BigDecimal PERCENT_FACTOR = BigDecimal.valueOf(100);

    private final Clock clock;

    private final TaxProperties properties;

    /** Stateless, deterministic single-stage rounding reconciler. */
    private final TaxTotalsReconciler reconciler = new TaxTotalsReconciler();

    public TestModeTaxCalculator(TaxProperties properties, Clock clock) {
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Calculate tax in test mode using configured default rates.
     * <p>
     * A single rounding stage is applied over the raw (line x jurisdiction)
     * matrix by {@link TaxTotalsReconciler}, guaranteeing
     * {@code Sum(lineItemTaxes.taxAmount) == totalTax == Sum(jurisdictions.taxAmount)}.
     *
     * @param request the tax calculation request
     * @return calculated tax response
     */
    @NonNull
    public TaxCalculationResponse calculate(@NonNull TaxCalculationRequest request) {
        log.info("Calculating tax in TEST MODE for postal code: {}", request.getPostalCode());

        List<TaxLineItem> lineItems = request.getLineItems();

        // Subtotal includes ALL line items (exempt and non-exempt).
        BigDecimal subtotal =
                lineItems.stream().map(TaxLineItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Resolve the effective transaction date (defaulting to today via the shared
        // Clock) and the rates map effective on that date.
        LocalDate transactionDate = resolveTransactionDate(request.getTransactionDate());
        Map<String, BigDecimal> effectiveRates = resolveEffectiveRates(transactionDate);

        // Applicable jurisdictions (type + decimal-fraction rate); rate order is stable.
        List<JurisdictionSpec> specs = determineJurisdictionSpecs(effectiveRates);

        // Non-exempt lines form the taxable rows of the matrix (row order preserved).
        List<TaxLineItem> taxableLines = lineItems.stream()
                .filter(item -> !item.isTaxExempt())
                .toList();
        List<BigDecimal> lineTaxableAmounts =
                taxableLines.stream().map(TaxLineItem::getSubtotal).toList();
        List<BigDecimal> jurisdictionRates =
                specs.stream().map(JurisdictionSpec::rate).toList();

        // Exempt-filtered taxable base: the denominator for the effective rate.
        BigDecimal taxBase =
                lineTaxableAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // Single rounding stage: all totals reconciled to the same grand total.
        TaxTotalsReconciler.ReconciledTax reconciled = reconciler.reconcile(lineTaxableAmounts, jurisdictionRates);

        BigDecimal totalTax = reconciled.totalTax();
        List<TaxJurisdiction> jurisdictions = buildJurisdictions(request, specs, reconciled.jurisdictionAmounts());
        List<TaxCalculationResponse.LineItemTax> lineItemTaxes =
                buildLineItemTaxes(lineItems, taxableLines, specs, reconciled);

        // Effective tax rate = totalTax / exempt-filtered taxable base (ADR-0042),
        // NOT totalTax / raw subtotal. Guard an all-exempt / zero base.
        BigDecimal effectiveTaxRate = taxBase.signum() > 0
                ? totalTax.divide(taxBase, 4, RoundingMode.HALF_UP)
                        .multiply(PERCENT_FACTOR)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return TaxCalculationResponse.builder()
                .subtotal(subtotal)
                .totalTax(totalTax)
                .total(subtotal.add(totalTax))
                .effectiveTaxRate(effectiveTaxRate)
                .jurisdictions(jurisdictions)
                .lineItemTaxes(lineItemTaxes)
                .testMode(true)
                .calculatedAt(Instant.now(clock))
                .referenceId(request.getReferenceId())
                .referenceType(request.getReferenceType())
                .build();
    }

    /**
     * Determine applicable tax jurisdictions (type and decimal-fraction rate)
     * from the supplied effective rates. Emits STATE, then COUNTY, then CITY for
     * any rate configured greater than zero; the order is stable so the
     * reconciler's tie-breaking is deterministic.
     *
     * @param rates the effective rates map by jurisdiction type code
     * @return ordered list of jurisdiction specs
     */
    @NonNull
    private List<JurisdictionSpec> determineJurisdictionSpecs(@NonNull Map<String, BigDecimal> rates) {
        List<JurisdictionSpec> specs = new ArrayList<>();
        for (TaxJurisdictionType type :
                List.of(TaxJurisdictionType.STATE, TaxJurisdictionType.COUNTY, TaxJurisdictionType.CITY)) {
            BigDecimal rate = rates.getOrDefault(type.code(), BigDecimal.ZERO);
            if (rate.compareTo(BigDecimal.ZERO) > 0) {
                specs.add(new JurisdictionSpec(type, rate));
            }
        }
        return specs;
    }

    /**
     * Resolve the transaction date used for effective-dated rate selection.
     * <p>
     * When {@code raw} is {@code null} or blank, the current date is used, derived
     * from the shared {@link Clock} so behavior stays deterministic and consistent
     * with {@code calculatedAt}. Otherwise the value is parsed as an ISO-8601 date
     * ({@code 2026-02-21}) or date-time ({@code 2026-02-21T09:30:00Z}); the calendar
     * date component is used as written, without timezone conversion.
     *
     * @param raw the optional ISO-8601 transaction date string from the request
     * @return the resolved transaction date
     * @throws IllegalArgumentException if {@code raw} is present but not valid ISO-8601
     */
    @NonNull
    private LocalDate resolveTransactionDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(clock);
        }
        String value = raw.trim();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException dateOnly) {
            try {
                return LocalDate.from(DateTimeFormatter.ISO_DATE_TIME.parse(value));
            } catch (DateTimeParseException dateTime) {
                throw new IllegalArgumentException(
                        "transactionDate must be a valid ISO-8601 date or date-time: " + value);
            }
        }
    }

    /**
     * Select the rates map effective on the given transaction date.
     * <p>
     * The chosen entry is the one whose {@code effectiveFrom} is the greatest value
     * not after {@code transactionDate}. On ties (equal {@code effectiveFrom}) the
     * first such entry in configured order wins, keeping selection deterministic.
     * When the schedule is empty, or no entry is effective on or before the
     * transaction date, the flat {@code defaultRates} map is returned, preserving
     * prior behavior.
     *
     * @param transactionDate the date to resolve rates for
     * @return the effective rates map by jurisdiction type code
     */
    @NonNull
    private Map<String, BigDecimal> resolveEffectiveRates(@NonNull LocalDate transactionDate) {
        var testMode = properties.getTestMode();
        Map<String, BigDecimal> selected = null;
        LocalDate selectedFrom = null;
        for (TaxProperties.RateScheduleEntry entry : testMode.getRateSchedule()) {
            LocalDate from = entry.getEffectiveFrom();
            if (from == null || entry.getRates() == null) {
                continue;
            }
            // from <= transactionDate
            if (!from.isAfter(transactionDate) && (selectedFrom == null || from.isAfter(selectedFrom))) {
                selectedFrom = from;
                selected = entry.getRates();
            }
        }
        return selected != null ? selected : testMode.getDefaultRates();
    }

    /**
     * Build the top-level jurisdiction rows from the reconciler's column totals.
     */
    @NonNull
    private List<TaxJurisdiction> buildJurisdictions(
            @NonNull TaxCalculationRequest request,
            @NonNull List<JurisdictionSpec> specs,
            @NonNull List<BigDecimal> jurisdictionAmounts) {
        List<TaxJurisdiction> jurisdictions = new ArrayList<>(specs.size());
        for (int j = 0; j < specs.size(); j++) {
            JurisdictionSpec spec = specs.get(j);
            jurisdictions.add(TaxJurisdiction.builder()
                    .countryCode(request.getCountryCode())
                    .regionCode(request.getStateCode())
                    .city(request.getCity())
                    .postalCode(request.getPostalCode())
                    .line1(request.getAddress())
                    .jurisdictionType(spec.type())
                    .taxRate(spec.rate().multiply(PERCENT_FACTOR))
                    .taxAmount(jurisdictionAmounts.get(j))
                    .build());
        }
        return jurisdictions;
    }

    /**
     * Build the per-line tax breakdown, pulling reconciled line totals and
     * per-jurisdiction cell amounts for non-exempt lines. Exempt lines carry zero
     * tax and an empty jurisdiction breakdown (zero-rate rows are deferred to T3).
     */
    @NonNull
    private List<TaxCalculationResponse.LineItemTax> buildLineItemTaxes(
            @NonNull List<TaxLineItem> lineItems,
            @NonNull List<TaxLineItem> taxableLines,
            @NonNull List<JurisdictionSpec> specs,
            TaxTotalsReconciler.ReconciledTax reconciled) {
        List<TaxCalculationResponse.LineItemTax> result = new ArrayList<>(lineItems.size());
        int taxableIndex = 0;
        for (TaxLineItem item : lineItems) {
            BigDecimal itemSubtotal = item.getSubtotal();
            if (item.isTaxExempt()) {
                result.add(TaxCalculationResponse.LineItemTax.builder()
                        .lineItemId(item.getLineItemId())
                        .subtotal(itemSubtotal)
                        .taxAmount(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                        .total(itemSubtotal)
                        .taxExempt(true)
                        .jurisdictions(new ArrayList<>())
                        .build());
                continue;
            }

            int i = taxableIndex++;
            BigDecimal lineTax = reconciled.lineAmounts().get(i);
            List<BigDecimal> cellAmounts = reconciled.cellAmounts().get(i);
            List<TaxCalculationResponse.JurisdictionTax> cells = new ArrayList<>(specs.size());
            for (int j = 0; j < specs.size(); j++) {
                JurisdictionSpec spec = specs.get(j);
                cells.add(TaxCalculationResponse.JurisdictionTax.builder()
                        .jurisdictionType(spec.type())
                        .code(spec.type().code())
                        .rate(spec.rate())
                        .amount(cellAmounts.get(j))
                        .build());
            }

            result.add(TaxCalculationResponse.LineItemTax.builder()
                    .lineItemId(item.getLineItemId())
                    .subtotal(itemSubtotal)
                    .taxAmount(lineTax)
                    .total(itemSubtotal.add(lineTax))
                    .taxExempt(false)
                    .jurisdictions(cells)
                    .build());
        }
        return result;
    }

    /**
     * An applicable jurisdiction with its decimal-fraction rate (e.g. 0.0725 for
     * 7.25%). Location fields are sourced from the request at build time.
     *
     * @param type the jurisdiction type
     * @param rate the tax rate as a decimal fraction
     */
    private record JurisdictionSpec(@NonNull TaxJurisdictionType type, @NonNull BigDecimal rate) {}
}
