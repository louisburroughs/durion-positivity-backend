package com.positivity.securityservice.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link PermissionCode} enum catalog contract (PERM-001).
 * <p>
 * Verifies:
 * <ul>
 * <li>Catalog contains exactly 227 permissions matching
 * {@code scripts/permissions-aggregate.yaml}.</li>
 * <li>Each bit index in the range [0, 226] is assigned exactly once (no gaps,
 * no reuse).</li>
 * <li>Each canonical code string is unique across all enum constants.</li>
 * <li>{@code CATALOG_VERSION = 3} constant is declared and accessible.</li>
 * <li>{@code fromCode(String)} provides a safe O(1) round-trip lookup.</li>
 * </ul>
 *
 * <p>
 * No Spring context is required; this is a pure JUnit 5 unit test.
 *
 * Issue: PERM-001
 */
@DisplayName("PermissionCode catalog contract (PERM-001)")
class PermissionCodeTest {

    // -------------------------------------------------------------------------
    // AC-1: Catalog size — 227 entries
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("catalog contains exactly 227 permissions")
    void catalogContainsExactly227Permissions() {
        assertThat(PermissionCode.values()).hasSize(227);
    }

    // -------------------------------------------------------------------------
    // AC-3 / AC-6: Bit index uniqueness and sequential coverage [0, 220]
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all bit indexes are unique")
    void allBitIndexesAreUnique() {
        // Issue PERM-001: unique bit index invariant — set size must equal enum size
        Set<Integer> bitIndexes = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::bitIndex)
                .collect(Collectors.toSet());
        assertThat(bitIndexes).hasSize(227);
    }

    @Test
    @DisplayName("bit indexes span from 0 to 226 with no gaps")
    void bitIndexesSpanFrom0To226WithNoGaps() {
        Set<Integer> bitIndexes = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::bitIndex)
                .collect(Collectors.toSet());
        // Issue PERM-001: every index 0..226 must be present
        for (int i = 0; i < 227; i++) {
            assertThat(bitIndexes).as("bit index %d must be assigned", i).contains(i);
        }
    }

    // -------------------------------------------------------------------------
    // AC-6: Code string uniqueness
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all code strings are unique")
    void allCodeStringsAreUnique() {
        Set<String> codes =
                Arrays.stream(PermissionCode.values()).map(PermissionCode::code).collect(Collectors.toSet());
        // Issue PERM-001: no two enum constants may share a canonical code string
        assertThat(codes).hasSize(227);
    }

    // -------------------------------------------------------------------------
    // AC-4: CATALOG_VERSION constant
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CATALOG_VERSION is 3")
    void catalogVersionIsThree() {
        assertThat(PermissionCode.CATALOG_VERSION).isEqualTo(3);
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
        assertThat(perm.get().bitIndex()).isZero();
    }

    @Test
    @DisplayName("known last permission 'mcp:chat:execute' has bit index 226")
    void knownLastPermissionHasBitIndex226() {
        // Issue PERM-001: mcp:chat:execute must be last entry assigned
        // bit index 226
        Optional<PermissionCode> perm = PermissionCode.fromCode("mcp:chat:execute");
        assertThat(perm).isPresent();
        assertThat(perm.get().bitIndex()).isEqualTo(226);
    }
}
