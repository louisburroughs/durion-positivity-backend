package com.positivity.securityservice.internal.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link PermissionCode} enum catalog contract (PERM-001).
 * <p>
 * Verifies:
 * <ul>
 *   <li>Catalog contains exactly 215 permissions matching {@code scripts/permissions-aggregate.yaml}.</li>
 *   <li>Each bit index in the range [0, 214] is assigned exactly once (no gaps, no reuse).</li>
 *   <li>Each canonical code string is unique across all enum constants.</li>
 *   <li>{@code CATALOG_VERSION = 1} constant is declared and accessible.</li>
 *   <li>{@code fromCode(String)} provides a safe O(1) round-trip lookup.</li>
 * </ul>
 *
 * <p>No Spring context is required; this is a pure JUnit 5 unit test.
 *
 * Issue: PERM-001
 */
@DisplayName("PermissionCode catalog contract (PERM-001)")
class PermissionCodeTest {

    // -------------------------------------------------------------------------
    // AC-1: Catalog size — 215 entries
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("catalog contains exactly 215 permissions")
    void catalogContainsExactly215Permissions() {
        assertThat(PermissionCode.values()).hasSize(215);
    }

    // -------------------------------------------------------------------------
    // AC-3 / AC-6: Bit index uniqueness and sequential coverage [0, 214]
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all bit indexes are unique")
    void allBitIndexesAreUnique() {
        // Issue PERM-001: unique bit index invariant — set size must equal enum size
        Set<Integer> bitIndexes = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::bitIndex)
                .collect(Collectors.toSet());
        assertThat(bitIndexes).hasSize(215);
    }

    @Test
    @DisplayName("bit indexes span from 0 to 214 with no gaps")
    void bitIndexesSpanFrom0To214WithNoGaps() {
        Set<Integer> bitIndexes = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::bitIndex)
                .collect(Collectors.toSet());
        // Issue PERM-001: every index 0..214 must be present
        for (int i = 0; i < 215; i++) {
            assertThat(bitIndexes)
                    .as("bit index %d must be assigned", i)
                    .contains(i);
        }
    }

    // -------------------------------------------------------------------------
    // AC-6: Code string uniqueness
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all code strings are unique")
    void allCodeStringsAreUnique() {
        Set<String> codes = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::code)
                .collect(Collectors.toSet());
        // Issue PERM-001: no two enum constants may share a canonical code string
        assertThat(codes).hasSize(215);
    }

    // -------------------------------------------------------------------------
    // AC-4: CATALOG_VERSION constant
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CATALOG_VERSION is 1")
    void catalogVersionIsOne() {
        assertThat(PermissionCode.CATALOG_VERSION).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC-5: fromCode() round-trip — unknown and null inputs
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fromCode returns empty for unknown code")
    void fromCodeReturnsEmptyForUnknownCode() {
        assertThat(PermissionCode.fromCode("unknown:permission:code")).isEmpty();
    }

    @Test
    @DisplayName("fromCode returns empty for null")
    void fromCodeReturnsEmptyForNull() {
        assertThat(PermissionCode.fromCode(null)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC-2 / AC-5: fromCode() pinned round-trips for first and last catalog entries
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("known first permission 'accounting:je:view' has bit index 0")
    void knownFirstPermissionHasBitIndex0() {
        // Issue PERM-001: accounting:je:view must be first entry assigned bit index 0
        Optional<PermissionCode> perm = PermissionCode.fromCode("accounting:je:view");
        assertThat(perm).isPresent();
        assertThat(perm.get().bitIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("known last permission 'workorder:wip:view_all_locations' has bit index 214")
    void knownLastPermissionHasBitIndex214() {
        // Issue PERM-001: workorder:wip:view_all_locations must be last entry assigned bit index 214
        Optional<PermissionCode> perm = PermissionCode.fromCode("workorder:wip:view_all_locations");
        assertThat(perm).isPresent();
        assertThat(perm.get().bitIndex()).isEqualTo(214);
    }
}
