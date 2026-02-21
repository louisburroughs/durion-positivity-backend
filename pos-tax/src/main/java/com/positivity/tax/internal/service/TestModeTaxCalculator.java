package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.TaxCalculationRequest;
import com.positivity.tax.internal.dto.TaxCalculationResponse;
import com.positivity.tax.internal.dto.TaxJurisdiction;
import com.positivity.tax.internal.dto.TaxLineItem;
import com.positivity.tax.internal.enums.TaxJurisdictionType;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    private final TaxProperties properties;

    public TestModeTaxCalculator(TaxProperties properties) {
        this.properties = properties;
    }

    /**
     * Calculate tax in test mode using configured default rates.
     *
     * @param request the tax calculation request
     * @return calculated tax response
     */
    @NonNull
    public TaxCalculationResponse calculate(@NonNull TaxCalculationRequest request) {
        log.info("Calculating tax in TEST MODE for postal code: {}", request.getPostalCode());

        // Calculate subtotal from all line items
        BigDecimal subtotal = request.getLineItems().stream()
                .map(TaxLineItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Get applicable jurisdictions based on location
        List<TaxJurisdiction> jurisdictions = determineJurisdictions(request);

        // Calculate total tax across all jurisdictions
        BigDecimal totalTax = jurisdictions.stream()
                .map(TaxJurisdiction::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate per-line-item tax breakdown
        List<TaxCalculationResponse.LineItemTax> lineItemTaxes = calculateLineItemTaxes(
                request.getLineItems(),
                jurisdictions);

        // Calculate effective tax rate
        BigDecimal effectiveTaxRate = subtotal.compareTo(BigDecimal.ZERO) > 0
                ? totalTax.divide(subtotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        return TaxCalculationResponse.builder()
                .subtotal(subtotal)
                .totalTax(totalTax)
                .total(subtotal.add(totalTax))
                .effectiveTaxRate(effectiveTaxRate)
                .jurisdictions(jurisdictions)
                .lineItemTaxes(lineItemTaxes)
                .testMode(true)
                .calculatedAt(Instant.now())
                .referenceId(request.getReferenceId() != null ? request.getReferenceId() : null)
                .referenceType(request.getReferenceType() != null ? request.getReferenceType() : null)
                .build();
    }

    /**
     * Determine applicable tax jurisdictions based on location.
     *
     * @param request the tax calculation request
     * @return list of applicable jurisdictions with calculated tax amounts
     */
    @NonNull
    private List<TaxJurisdiction> determineJurisdictions(@NonNull TaxCalculationRequest request) {
        List<TaxJurisdiction> jurisdictions = new ArrayList<>();

        // Get configured default rates
        var defaultRates = properties.getTestMode().getDefaultRates();

        // Calculate subtotal (tax base)
        BigDecimal taxBase = request.getLineItems().stream()
                .filter(item -> !item.isTaxExempt())
                .map(TaxLineItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Apply state tax if configured
        BigDecimal stateRate = defaultRates.getOrDefault(TaxJurisdictionType.STATE.code(), BigDecimal.ZERO);
        if (stateRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal stateTax = taxBase.multiply(stateRate)
                    .setScale(2, RoundingMode.HALF_UP);
            jurisdictions.add(TaxJurisdiction.builder()
                    .countryCode(request.getCountryCode())
                    .regionCode(request.getStateCode())
                    .city(request.getCity())
                    .postalCode(request.getPostalCode())
                    .line1(request.getAddress())
                    .jurisdictionType(TaxJurisdictionType.STATE)
                    .taxRate(stateRate.multiply(BigDecimal.valueOf(100)))
                    .taxAmount(stateTax)
                    .build());
        }

        // Apply county tax if configured
        BigDecimal countyRate = defaultRates.getOrDefault(TaxJurisdictionType.COUNTY.code(), BigDecimal.ZERO);
        if (countyRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal countyTax = taxBase.multiply(countyRate)
                    .setScale(2, RoundingMode.HALF_UP);
            jurisdictions.add(TaxJurisdiction.builder()
                    .countryCode(request.getCountryCode())
                    .regionCode(request.getStateCode())
                    .city(request.getCity())
                    .postalCode(request.getPostalCode())
                    .line1(request.getAddress())
                    .jurisdictionType(TaxJurisdictionType.COUNTY)
                    .taxRate(countyRate.multiply(BigDecimal.valueOf(100)))
                    .taxAmount(countyTax)
                    .build());
        }

        // Apply city tax if configured
        BigDecimal cityRate = defaultRates.getOrDefault(TaxJurisdictionType.CITY.code(), BigDecimal.ZERO);
        if (cityRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal cityTax = taxBase.multiply(cityRate)
                    .setScale(2, RoundingMode.HALF_UP);
            jurisdictions.add(TaxJurisdiction.builder()
                    .countryCode(request.getCountryCode())
                    .regionCode(request.getStateCode())
                    .city(request.getCity())
                    .postalCode(request.getPostalCode())
                    .line1(request.getAddress())
                    .jurisdictionType(TaxJurisdictionType.CITY)
                    .taxRate(cityRate.multiply(BigDecimal.valueOf(100)))
                    .taxAmount(cityTax)
                    .build());
        }

        return jurisdictions;
    }

    /**
     * Calculate per-line-item tax breakdown.
     *
     * @param lineItems     the line items
     * @param jurisdictions the applicable jurisdictions
     * @return list of line item tax details
     */
    @NonNull
    private List<TaxCalculationResponse.LineItemTax> calculateLineItemTaxes(
            @NonNull List<TaxLineItem> lineItems,
            @NonNull List<TaxJurisdiction> jurisdictions) {
        // Calculate combined tax rate from all jurisdictions
        BigDecimal combinedRate = jurisdictions.stream()
                .map(j -> j.getTaxRate().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<TaxCalculationResponse.LineItemTax> result = new ArrayList<>();

        for (TaxLineItem item : lineItems) {
            BigDecimal itemSubtotal = item.getSubtotal();
            BigDecimal itemTax = item.isTaxExempt()
                    ? BigDecimal.ZERO
                    : itemSubtotal.multiply(combinedRate).setScale(2, RoundingMode.HALF_UP);

            result.add(TaxCalculationResponse.LineItemTax.builder()
                    .lineItemId(item.getLineItemId())
                    .subtotal(itemSubtotal)
                    .taxAmount(itemTax)
                    .total(itemSubtotal.add(itemTax))
                    .taxExempt(item.isTaxExempt())
                    .build());
        }

        return result;
    }
}
