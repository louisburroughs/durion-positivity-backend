package com.positivity.tax.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents a tax jurisdiction with its applicable rates.
 * <p>
 * A jurisdiction can be a country, state/province, county, city, or special district.
 * Multiple jurisdictions can apply to a single transaction (e.g., state + county + city).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxJurisdiction {

    /**
     * Unique code identifying the jurisdiction (e.g., "CA-STATE", "LA-COUNTY", "LA-CITY").
     */
    private String jurisdictionCode;

    /**
     * Human-readable name of the jurisdiction (e.g., "California", "Los Angeles County").
     */
    private String jurisdictionName;

    /**
     * Tax rate as a percentage (e.g., 7.25 for 7.25%).
     */
    private BigDecimal taxRate;

    /**
     * Type of jurisdiction (e.g., "STATE", "COUNTY", "CITY", "DISTRICT").
     */
    private String jurisdictionType;

    /**
     * Tax amount calculated for this jurisdiction.
     */
    private BigDecimal taxAmount;
}
