package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.location.internal.exception.InvalidFieldException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * DECISION-LOCATION-028: distances travel at the edge as {@code {value, unit}} and are stored in
 * canonical kilometres.
 */
class DistanceUnitsTest {

    @Test
    @DisplayName("30 MI converts to 48.28 km (1 mi = 1.609344 km exactly, rounded half-up to 2dp)")
    void milesToKmConversion() {
        BigDecimal km = DistanceUnits.parseOptionalKm(Map.of("value", 30, "unit", "MI"), "maxDistance");

        assertThat(km).isEqualByComparingTo("48.28");
    }

    @Test
    @DisplayName("48.28 km converts back to 30.00 MI for display")
    void kmToMilesRoundTrip() {
        BigDecimal displayed = DistanceUnits.fromKm(new BigDecimal("48.28"), "MI");

        assertThat(displayed).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("a KM value passes through unchanged, rounded to 2dp")
    void kmPassesThroughUnchanged() {
        BigDecimal km = DistanceUnits.parseOptionalKm(Map.of("value", 25, "unit", "km"), "maxDistance");

        assertThat(km).isEqualByComparingTo("25.00");
        assertThat(DistanceUnits.fromKm(km, "KM")).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("a bare number is refused")
    void bareNumberIsRefused() {
        assertThatThrownBy(() -> DistanceUnits.parseOptionalKm(30, "maxDistance"))
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getField()).isEqualTo("maxDistance");
                });
    }

    @Test
    @DisplayName("a bare string is refused")
    void bareStringIsRefused() {
        assertThatThrownBy(() -> DistanceUnits.parseOptionalKm("30", "maxDistance"))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("maxDistance"));
    }

    @Test
    @DisplayName("a distance with no unit is refused, naming the unit field")
    void missingUnitIsRefused() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("value", 30);

        assertThatThrownBy(() -> DistanceUnits.parseOptionalKm(raw, "maxDistance"))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("maxDistance.unit"));
    }

    @Test
    @DisplayName("an unknown unit is refused, naming the unit field")
    void unknownUnitIsRefused() {
        assertThatThrownBy(() -> DistanceUnits.parseOptionalKm(Map.of("value", 30, "unit", "YARDS"), "maxDistance"))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("maxDistance.unit"));
    }

    @Test
    @DisplayName("a missing or non-numeric value is refused, naming the value field")
    void missingValueIsRefused() {
        assertThatThrownBy(() -> DistanceUnits.parseOptionalKm(Map.of("unit", "KM"), "maxDistance"))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("maxDistance.value"));
    }

    @Test
    @DisplayName("a negative value is refused")
    void negativeValueIsRefused() {
        assertThatThrownBy(() -> DistanceUnits.parseOptionalKm(Map.of("value", -5, "unit", "KM"), "maxDistance"))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("maxDistance.value"));
    }

    @Test
    @DisplayName("null is a valid absence, not an error")
    void nullIsAbsence() {
        assertThat(DistanceUnits.parseOptionalKm(null, "maxDistance")).isNull();
    }

    @Test
    @DisplayName("a required distance that is absent is refused")
    void requiredDistanceMustBePresent() {
        assertThatThrownBy(() -> DistanceUnits.parseRequiredKm(null, "distanceValue"))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class,
                        e -> assertThat(e.getField()).isEqualTo("distanceValue"));
    }

    @Test
    @DisplayName("normalize accepts KM/MI case-insensitively and rejects anything else")
    void normalizeAcceptsKnownUnitsOnly() {
        assertThat(DistanceUnits.normalize("km")).isEqualTo("KM");
        assertThat(DistanceUnits.normalize(" mi ")).isEqualTo("MI");
        assertThat(DistanceUnits.normalize("YARDS")).isNull();
        assertThat(DistanceUnits.normalize(null)).isNull();
    }
}
