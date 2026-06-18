package com.positivity.securityservice.internal.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
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
 * <li>Catalog contains exactly 285 permissions matching
 * {@code pos-security-service/docs/permissions-aggregate.yaml}.</li>
 * <li>Each bit index in the range [0, 284] is assigned exactly once (no gaps,
 * no reuse).</li>
 * <li>Each canonical code string is unique across all enum constants.</li>
 * <li>{@code CATALOG_VERSION = 8} constant is declared and accessible.</li>
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

    private static final int EXPECTED_PERMISSION_COUNT = 338;
    private static final int EXPECTED_CATALOG_VERSION = 11;

    // -------------------------------------------------------------------------
    // AC-1: Catalog size — 338 entries
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("catalog contains exactly 338 permissions")
    void catalogContainsExpectedPermissions() {
        assertThat(PermissionCode.values()).hasSize(EXPECTED_PERMISSION_COUNT);
    }

    // -------------------------------------------------------------------------
    // AC-3 / AC-6: Bit index uniqueness and sequential coverage [0, 284]
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all bit indexes are unique")
    void allBitIndexesAreUnique() {
        // Issue PERM-001: unique bit index invariant — set size must equal enum size
        Set<Integer> bitIndexes = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::bitIndex)
                .collect(Collectors.toSet());
        assertThat(bitIndexes).hasSize(EXPECTED_PERMISSION_COUNT);
    }

    @Test
    @DisplayName("bit indexes span from 0 to 337 with no gaps")
    void bitIndexesSpanContiguouslyWithNoGaps() {
        Set<Integer> bitIndexes = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::bitIndex)
                .collect(Collectors.toSet());
        // Issue PERM-001: every index 0..(count-1) must be present
        for (int i = 0; i < EXPECTED_PERMISSION_COUNT; i++) {
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
        assertThat(codes).hasSize(EXPECTED_PERMISSION_COUNT);
    }

    // -------------------------------------------------------------------------
    // AC-4: CATALOG_VERSION constant
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CATALOG_VERSION is declared and matches expected value")
    void catalogVersionIsCorrect() {
        assertThat(PermissionCode.CATALOG_VERSION).isEqualTo(EXPECTED_CATALOG_VERSION);
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
    @DisplayName("known permission 'people:userLink:write' has bit index 238")
    void knownLastPermissionHasBitIndex238() {
        Optional<PermissionCode> perm = PermissionCode.fromCode("people:userLink:write");
        assertThat(perm).isPresent();
        assertThat(perm.get().bitIndex()).isEqualTo(238);
    }

    @Test
    @DisplayName("people person delete permission is pinned to bit index 236")
    void peoplePersonDeletePermissionHasBitIndex236() {
        Optional<PermissionCode> perm = PermissionCode.fromCode("people:person:delete");
        assertThat(perm).isPresent();
        assertThat(perm.get().bitIndex()).isEqualTo(236);
    }

    // -------------------------------------------------------------------------
    // PERM-002: 23 previously-dark permissions now present (bits 262–284)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all previously-dark permissions now exist in PermissionCode")
    void allDarkPermissionsExistInPermissionCode() {
        List<String> darkPermissions = List.of(
                "accounting:ap:approve",
                "accounting:ap:reject",
                "accounting:coa:deactivate",
                "accounting:je:reverse",
                "accounting:mapping:view",
                "accounting:mapping:create",
                "accounting:mapping:edit",
                "accounting:mapping:deactivate",
                "accounting:posting_rules:view",
                "accounting:posting_rules:create",
                "accounting:posting_rules:publish",
                "accounting:posting_rules:archive",
                "timekeeping:work_session:create",
                "timekeeping:work_session:stop",
                "timekeeping:work_session:break_start",
                "timekeeping:work_session:break_stop",
                "timekeeping:overlap_override",
                "workorder:dashboard:view",
                "workorder:estimate:submit",
                "workorder:estimate:promote",
                "workorder:workorder:assign-technician",
                "workorder:workorder:generate_invoice",
                "workorder:start");

        Set<String> knownCodes =
                Arrays.stream(PermissionCode.values()).map(PermissionCode::code).collect(Collectors.toSet());

        assertThat(darkPermissions)
                .isNotEmpty()
                .allSatisfy(perm -> assertThat(knownCodes)
                        .as("PermissionCode missing entry for '%s'", perm)
                        .contains(perm));
    }
}
