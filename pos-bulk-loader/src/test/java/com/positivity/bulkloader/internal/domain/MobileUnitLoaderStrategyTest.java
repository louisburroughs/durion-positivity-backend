package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code location/mobile-units.csv} → the MOBILE_UNIT loader domain (issue #2267,
 * DECISION-LOCATION-029). {@code maxDutyClass} is a maximum only, the same bay's-axis rule
 * {@link BayLoaderStrategyTest} pins for bays: blank means unconstrained, and a present value must
 * be a whole GVWR class between 1 and 8. Resolving and sending the value on is
 * {@code MobileUnitBulkIngestController}'s job (pos-location); this strategy only shapes and
 * validates the fixture row. The optional identity fields DECISION-LOCATION-029 also adds are not
 * carried by this loader domain.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class MobileUnitLoaderStrategyTest {

    private final MobileUnitLoaderStrategy strategy = new MobileUnitLoaderStrategy();

    private static Map<String, String> row(String... kv) {
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put(kv[i], kv[i + 1]);
        }
        return row;
    }

    private static Map<String, String> fleetVanRow() {
        return row(
                "baseLocationCode", "CLT-MAIN-001",
                "name", "MU-CLT-MAIN-03",
                "status", "ACTIVE",
                "maxDutyClass", "5");
    }

    @Test
    @DisplayName("mapRow carries maxDutyClass onto the record; the domain is MOBILE_UNIT")
    void mapRowCarriesMaxDutyClass() {
        MobileUnitLoaderRecord record = strategy.mapRow(fleetVanRow());

        assertThat(record.getMaxDutyClass()).isEqualTo("5");
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.MOBILE_UNIT);
    }

    @Test
    @DisplayName("a blank maxDutyClass is unconstrained, not an error")
    void blankMaxDutyClassIsValid() {
        Map<String, String> lightVan = fleetVanRow();
        lightVan.put("maxDutyClass", "");
        lightVan.put("baseLocationId", "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10");

        MobileUnitLoaderRecord record = strategy.mapRow(lightVan);
        assertThat(record.getMaxDutyClass()).isEmpty();
        assertThat(strategy.validate(record)).isEmpty();
    }

    @Test
    @DisplayName("a maxDutyClass outside 1-8, or not a whole number, fails the row")
    void maxDutyClassMustBeInRange() {
        Map<String, String> tooHigh = fleetVanRow();
        tooHigh.put("maxDutyClass", "9");
        assertThat(strategy.validate(strategy.mapRow(tooHigh)))
                .anySatisfy(error -> assertThat(error).contains("maxDutyClass must be between 1 and 8"));

        Map<String, String> tooLow = fleetVanRow();
        tooLow.put("maxDutyClass", "0");
        assertThat(strategy.validate(strategy.mapRow(tooLow)))
                .anySatisfy(error -> assertThat(error).contains("maxDutyClass must be between 1 and 8"));

        Map<String, String> notANumber = fleetVanRow();
        notANumber.put("maxDutyClass", "heavy");
        assertThat(strategy.validate(strategy.mapRow(notANumber)))
                .anySatisfy(error -> assertThat(error).contains("maxDutyClass must be a whole number"));
    }
}
