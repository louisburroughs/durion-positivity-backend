package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code location/bays.csv} → the BAY loader domain (issue #2262). {@code maxDutyClass} is a
 * maximum only (spec D13): blank means unconstrained, and a present value must be a whole GVWR
 * class between 1 and 8. Resolving the value against the location the bay belongs to, and sending
 * it on, is {@code BayBulkIngestController}'s job (pos-location); this strategy only shapes and
 * validates the fixture row.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class BayLoaderStrategyTest {

    private static final String LOCATION_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10";

    private final BayLoaderStrategy strategy = new BayLoaderStrategy();

    private static Map<String, String> row(String... kv) {
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put(kv[i], kv[i + 1]);
        }
        return row;
    }

    private static Map<String, String> heavyDutyBayRow() {
        return row(
                "locationCode", "CLT-MAIN-001",
                "locationId", LOCATION_ID,
                "name", "Bay 06",
                "bayType", "HEAVY_DUTY",
                "maxConcurrentVehicles", "1",
                "maxDutyClass", "8");
    }

    @Test
    @DisplayName("mapRow carries maxDutyClass onto the record; the domain is BAY")
    void mapRowCarriesMaxDutyClass() {
        BayLoaderRecord record = strategy.mapRow(heavyDutyBayRow());

        assertThat(record.getMaxDutyClass()).isEqualTo("8");
        assertThat(strategy.validate(record)).isEmpty();
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.BAY);
    }

    @Test
    @DisplayName("a blank maxDutyClass is unconstrained, not an error (WASH_DETAIL has no lift)")
    void blankMaxDutyClassIsValid() {
        Map<String, String> washBay = heavyDutyBayRow();
        washBay.put("bayType", "WASH_DETAIL");
        washBay.put("maxDutyClass", "");

        BayLoaderRecord record = strategy.mapRow(washBay);
        assertThat(record.getMaxDutyClass()).isEmpty();
        assertThat(strategy.validate(record)).isEmpty();
    }

    @Test
    @DisplayName("a maxDutyClass outside 1-8, or not a whole number, fails the row")
    void maxDutyClassMustBeInRange() {
        Map<String, String> tooHigh = heavyDutyBayRow();
        tooHigh.put("maxDutyClass", "9");
        assertThat(strategy.validate(strategy.mapRow(tooHigh)))
                .singleElement()
                .asString()
                .contains("maxDutyClass must be between 1 and 8");

        Map<String, String> tooLow = heavyDutyBayRow();
        tooLow.put("maxDutyClass", "0");
        assertThat(strategy.validate(strategy.mapRow(tooLow)))
                .singleElement()
                .asString()
                .contains("maxDutyClass must be between 1 and 8");

        Map<String, String> notANumber = heavyDutyBayRow();
        notANumber.put("maxDutyClass", "heavy");
        assertThat(strategy.validate(strategy.mapRow(notANumber)))
                .singleElement()
                .asString()
                .contains("maxDutyClass must be a whole number");
    }
}
