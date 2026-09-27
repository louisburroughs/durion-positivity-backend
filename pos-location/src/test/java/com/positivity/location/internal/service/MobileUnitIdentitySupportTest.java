package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.location.internal.exception.InvalidFieldException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link MobileUnitIdentitySupport} (DECISION-LOCATION-029, issue #2267).
 */
class MobileUnitIdentitySupportTest {

    @Test
    @DisplayName("maxDutyClass: null passes through; 1 and 8 (the boundaries) are accepted")
    void maxDutyClassAcceptsNullAndBoundaries() {
        assertThat(MobileUnitIdentitySupport.requireMaxDutyClass(null)).isNull();
        assertThat(MobileUnitIdentitySupport.requireMaxDutyClass(1)).isEqualTo(1);
        assertThat(MobileUnitIdentitySupport.requireMaxDutyClass(8)).isEqualTo(8);
    }

    @Test
    @DisplayName("maxDutyClass: 0 and 9 are refused with 400 naming the field")
    void maxDutyClassRejectsOutOfRange() {
        assertThatThrownBy(() -> MobileUnitIdentitySupport.requireMaxDutyClass(0))
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo(InvalidFieldException.VALIDATION_ERROR);
                    assertThat(e.getField()).isEqualTo("maxDutyClass");
                });
        assertThatThrownBy(() -> MobileUnitIdentitySupport.requireMaxDutyClass(9))
                .isInstanceOf(InvalidFieldException.class);
    }

    @Test
    @DisplayName("unitNumber: null/blank clears; text within 32 characters passes through trimmed")
    void unitNumberNormalizes() {
        assertThat(MobileUnitIdentitySupport.normalizeUnitNumber(null)).isNull();
        assertThat(MobileUnitIdentitySupport.normalizeUnitNumber("  ")).isNull();
        assertThat(MobileUnitIdentitySupport.normalizeUnitNumber(" Fleet-107 ")).isEqualTo("Fleet-107");
    }

    @Test
    @DisplayName("unitNumber: over 32 characters is refused with 400")
    void unitNumberRejectsTooLong() {
        String tooLong = "X".repeat(33);
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizeUnitNumber(tooLong))
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getField()).isEqualTo("unitNumber");
                });
    }

    @Test
    @DisplayName("vin: null/blank clears; a valid 17-character VIN is upper-cased")
    void vinNormalizesToUpperCase() {
        assertThat(MobileUnitIdentitySupport.normalizeVin(null)).isNull();
        assertThat(MobileUnitIdentitySupport.normalizeVin("  ")).isNull();
        assertThat(MobileUnitIdentitySupport.normalizeVin(" 1hgcm82633a004352 "))
                .isEqualTo("1HGCM82633A004352");
    }

    @Test
    @DisplayName("vin: not exactly 17 characters is refused with 400")
    void vinRejectsWrongLength() {
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizeVin("SHORT123"))
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getField()).isEqualTo("vin");
                });
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizeVin("1HGCM82633A0043521"))
                .isInstanceOf(InvalidFieldException.class);
    }

    @Test
    @DisplayName("vin: containing I, O, or Q is refused with 400, even after upper-casing")
    void vinRejectsExcludedLetters() {
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizeVin("1HGCMI2633A004352"))
                .isInstanceOf(InvalidFieldException.class);
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizeVin("1HGCMO2633A004352"))
                .isInstanceOf(InvalidFieldException.class);
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizeVin("1HGCMQ2633A004352"))
                .isInstanceOf(InvalidFieldException.class);
        // lower-case i/o/q normalize to upper case first, and are refused just the same.
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizeVin("1hgcmi2633a004352"))
                .isInstanceOf(InvalidFieldException.class);
    }

    @Test
    @DisplayName("licensePlate: null/blank clears; text within 16 characters passes through trimmed")
    void licensePlateNormalizes() {
        assertThat(MobileUnitIdentitySupport.normalizeLicensePlate(null)).isNull();
        assertThat(MobileUnitIdentitySupport.normalizeLicensePlate("  ")).isNull();
        assertThat(MobileUnitIdentitySupport.normalizeLicensePlate(" ABC-1234 "))
                .isEqualTo("ABC-1234");
    }

    @Test
    @DisplayName("licensePlate: over 16 characters is refused with 400")
    void licensePlateRejectsTooLong() {
        String tooLong = "X".repeat(17);
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizeLicensePlate(tooLong))
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getField()).isEqualTo("licensePlate");
                });
    }

    @Test
    @DisplayName("plateRegion: null/blank clears; a valid ISO 3166-2 code is upper-cased")
    void plateRegionNormalizesToUpperCase() {
        assertThat(MobileUnitIdentitySupport.normalizePlateRegion(null)).isNull();
        assertThat(MobileUnitIdentitySupport.normalizePlateRegion("  ")).isNull();
        assertThat(MobileUnitIdentitySupport.normalizePlateRegion(" us-nc ")).isEqualTo("US-NC");
    }

    @Test
    @DisplayName("plateRegion: not matching ISO 3166-2 (CC-SSS) is refused with 400")
    void plateRegionRejectsMalformed() {
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizePlateRegion("NC"))
                .isInstanceOfSatisfying(InvalidFieldException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getField()).isEqualTo("plateRegion");
                });
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizePlateRegion("USA-NORTHCAROLINA"))
                .isInstanceOf(InvalidFieldException.class);
        assertThatThrownBy(() -> MobileUnitIdentitySupport.normalizePlateRegion("US_NC"))
                .isInstanceOf(InvalidFieldException.class);
    }
}
