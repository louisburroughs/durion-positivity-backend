package com.positivity.vehicle.internal.util;

import static org.junit.jupiter.api.Assertions.*;

import com.positivity.vehicle.internal.exception.VehicleValidationException;
import org.junit.jupiter.api.Test;

/**
 * Tests for VIN validation and normalization utility.
 */
class VinUtilsTest {

    @Test
    void testNormalize() {
        assertEquals("1HGCM82633A004352", VinUtils.normalize("1hgcm82633a004352"));
        assertEquals("1HGCM82633A004352", VinUtils.normalize("1HG CM826 33A00 4352"));
        assertEquals("1HGCM82633A004352", VinUtils.normalize("  1HGCM82633A004352  "));
    }

    @Test
    void testIsValid() {
        assertTrue(VinUtils.isValid("1HGCM82633A004352"));
        assertFalse(VinUtils.isValid("1HGCM82633A00435")); // Too short
        assertFalse(VinUtils.isValid("1HGCM82633A0043521")); // Too long
        assertFalse(VinUtils.isValid("1HGCM82633I004352")); // Contains I
        assertFalse(VinUtils.isValid("1HGCM82633O004352")); // Contains O
        assertFalse(VinUtils.isValid("1HGCM82633Q004352")); // Contains Q
    }

    @Test
    void testValidateAndNormalize() {
        String result = VinUtils.validateAndNormalize("1hgcm82633a004352");
        assertEquals("1HGCM82633A004352", result);

        assertThrows(VehicleValidationException.class, () -> {
            VinUtils.validateAndNormalize("INVALID");
        });

        assertThrows(VehicleValidationException.class, () -> {
            VinUtils.validateAndNormalize("1HGCM82633I004352");
        });
    }

    // normalize()'s own null/blank check is defensive (issue #1694): both current call sites
    // already reject a null/blank VIN via bean validation before calling normalize(), so it stays
    // a bare IllegalArgumentException rather than a retyped, advice-mapped exception.
    @Test
    void testNormalizeNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            VinUtils.normalize(null);
        });
    }

    @Test
    void testNormalizeBlank() {
        assertThrows(IllegalArgumentException.class, () -> {
            VinUtils.normalize("   ");
        });
    }
}
