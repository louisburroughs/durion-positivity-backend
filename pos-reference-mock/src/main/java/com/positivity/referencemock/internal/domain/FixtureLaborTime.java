package com.positivity.referencemock.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One vehicle-keyed published labor time in the fixture file.
 *
 * <p>Vehicle fields that are {@code null} are wildcards: the row applies to any value of that
 * field. A row with all five vehicle fields {@code null} (e.g. the diagnostic block time) applies
 * to every vehicle.
 *
 * @param providerOperationCode vendor operation code the time belongs to
 * @param vehicleYear model year as a string, or {@code null} for any year
 * @param make vehicle make, or {@code null} for any make
 * @param model vehicle model, or {@code null} for any model
 * @param submodel vehicle submodel/trim, or {@code null} for any submodel
 * @param engineCode engine code, or {@code null} for any engine
 * @param hours decimal hours in tenths (industry flat-rate convention)
 * @param timeType RETAIL_FLAT_RATE, OEM_WARRANTY or MANUFACTURER_INSTALL
 * @param overlapGroup lines sharing a group share setup time (e.g. {@code WHEEL-OFF}), or null
 * @param includedOperations DURION operation codes whose time is already included in this one
 * @param publishedAt guide publication date for this row
 * @param notes optional vendor commentary, or null
 */
public record FixtureLaborTime(
        String providerOperationCode,
        String vehicleYear,
        String make,
        String model,
        String submodel,
        String engineCode,
        BigDecimal hours,
        String timeType,
        String overlapGroup,
        List<String> includedOperations,
        LocalDate publishedAt,
        String notes) {

    public FixtureLaborTime {
        includedOperations = includedOperations == null ? List.of() : List.copyOf(includedOperations);
    }

    /** Number of non-wildcard vehicle fields; higher means a more specific row. */
    public int specificity() {
        int score = 0;
        if (vehicleYear != null) {
            score++;
        }
        if (make != null) {
            score++;
        }
        if (model != null) {
            score++;
        }
        if (submodel != null) {
            score++;
        }
        if (engineCode != null) {
            score++;
        }
        return score;
    }
}
