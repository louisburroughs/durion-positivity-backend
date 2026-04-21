package com.positivity.securityservice.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.securityservice.internal.enums.PermissionCode;
import java.util.Base64;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PermissionBitsetCodec} (PERM-003).
 * <p>
 * Verifies the round-trip contract for encoding and decoding permission sets
 * as compact Base64URL bitsets, including edge cases (empty set, malformed
 * input, out-of-range bits) and the {@code hasPermission} convenience method.
 * <p>
 * No Spring context required — this is a pure JUnit 5 unit test.
 *
 * Issue: PERM-003
 */
@DisplayName("PermissionBitsetCodec contract (PERM-003)")
class PermissionBitsetCodecTest {

    // -------------------------------------------------------------------------
    // AC-1: encode — produces Base64URL without padding
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("encode empty set produces non-null, non-throwing result")
    void encodeEmptySetProducesEmptyOrZeroString() {
        // Issue PERM-003: empty-set encode must not throw and must return non-null
        String encoded = PermissionBitsetCodec.encode(Set.of());
        assertThat(encoded).isNotNull();
    }

    @Test
    @DisplayName("encode single permission produces Base64URL without padding")
    void encodeSinglePermissionProducesBase64UrlWithoutPadding() {
        // Issue PERM-003: output must be valid Base64URL and have no '=' padding chars
        String encoded = PermissionBitsetCodec.encode(Set.of(PermissionCode.ACCOUNTING__JE__VIEW));
        assertThat(encoded).isNotNull().doesNotContain("=").matches("[A-Za-z0-9_-]*");
    }

    @Test
    @DisplayName("encode a permission set spanning multiple bytes produces Base64URL without padding")
    void encodeProducesBase64UrlWithoutPadding() {
        // Issue PERM-003: bit 0 (first) + bit 226 (last) — spans catalog bounds
        Set<PermissionCode> perms = EnumSet.of(
                PermissionCode.ACCOUNTING__JE__VIEW, // bit 0
                PermissionCode.MCP__CHAT__EXECUTE // bit 226
        );
        String encoded = PermissionBitsetCodec.encode(perms);
        assertThat(encoded).doesNotContain("=").matches("[A-Za-z0-9_-]*");
    }

    // -------------------------------------------------------------------------
    // AC-2: decode — returns correct BitSet
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("decode 'AQ' (bit 0 set) returns BitSet with bit 0 true")
    void decodeReturnsCorrectBitSetForSingleBit0() {
        // Issue PERM-003: BitSet.toByteArray() for {bit 0} = byte[] {0x01} → Base64URL
        // "AQ"
        BitSet decoded = PermissionBitsetCodec.decode("AQ");
        assertThat(decoded.get(0)).isTrue();
    }

    @Test
    @DisplayName("decode empty string returns empty BitSet")
    void decodeHandlesEmptyString() {
        // Issue PERM-003: empty input must resolve to an empty (all-clear) BitSet
        BitSet result = PermissionBitsetCodec.decode("");
        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("decode malformed Base64 input throws IllegalArgumentException")
    void decodeThrowsIllegalArgumentForMalformedBase64() {
        // Issue PERM-003: codec must surface invalid input clearly to callers
        assertThatThrownBy(() -> PermissionBitsetCodec.decode("!@#$%")).isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // AC-3 + AC-4: decodeToPermissions — resolves known bits, round-trips
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("round-trip all 227 permissions returns identical set")
    void roundTripAllPermissions() {
        // Issue PERM-003: encode → decodeToPermissions must be lossless for full
        // catalog
        Set<PermissionCode> all = EnumSet.allOf(PermissionCode.class);
        String encoded = PermissionBitsetCodec.encode(all);
        Set<PermissionCode> decoded = PermissionBitsetCodec.decodeToPermissions(encoded,
                PermissionCode.CATALOG_VERSION);
        assertThat(decoded).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    @DisplayName("round-trip subset of permissions across multiple modules returns identical subset")
    void roundTripSubsetOfPermissions() {
        // Issue PERM-003: scattered bits across security (138, 143) and workorder (188)
        Set<PermissionCode> subset = EnumSet.of(
                PermissionCode.SECURITY__ROLE__VIEW, // bit 138
                PermissionCode.SECURITY__PERMISSION__VIEW, // bit 143
                PermissionCode.WORKORDER__ESTIMATE__APPROVE // bit 188
        );
        String encoded = PermissionBitsetCodec.encode(subset);
        Set<PermissionCode> decoded = PermissionBitsetCodec.decodeToPermissions(encoded,
                PermissionCode.CATALOG_VERSION);
        assertThat(decoded).containsExactlyInAnyOrderElementsOf(subset);
    }

    @Test
    @DisplayName("round-trip single permission returns identical single-element set")
    void roundTripSinglePermission() {
        // Issue PERM-003: bit 11 (catalog:product:view)
        Set<PermissionCode> single = Set.of(PermissionCode.CATALOG__PRODUCT__VIEW);
        String encoded = PermissionBitsetCodec.encode(single);
        Set<PermissionCode> decoded = PermissionBitsetCodec.decodeToPermissions(encoded,
                PermissionCode.CATALOG_VERSION);
        assertThat(decoded).containsExactlyInAnyOrderElementsOf(single);
    }

    // -------------------------------------------------------------------------
    // AC-3 (edge case): decodeToPermissions ignores bits outside catalog range
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("decodeToPermissions correctly decodes bit 227 (CATALOG__SUPPLIER_COST__READ) and ignores bits beyond catalog max")
    void decodeToPermissionsIgnoresUnknownBitsGracefully() {
        // Construct a byte array with bit 227 set (the first bit beyond the catalog).
        // BitSet.valueOf uses little-endian within bytes, so bit 227 = byte[28] | 0x08
        // (227 / 8 = 28, 227 % 8 = 3 → bit 3 of byte 28 → 0x08)
        // Also set bit 0 (accounting:je:view) to have a known valid permission present.
        byte[] bytes = new byte[29]; // 29 bytes covers bits 0..227
        bytes[0] = (byte) 0x01; // bit 0
        bytes[28] = (byte) 0x08; // bit 227 = CATALOG__SUPPLIER_COST__READ
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Set<PermissionCode> result = PermissionBitsetCodec.decodeToPermissions(encoded, PermissionCode.CATALOG_VERSION);

        // Both known permissions should be present; truly out-of-range bits beyond the catalog max would be silently ignored
        assertThat(result).containsExactly(PermissionCode.ACCOUNTING__JE__VIEW,
                PermissionCode.CATALOG__SUPPLIER_COST__READ);
    }

    // -------------------------------------------------------------------------
    // AC-3 (edge case): decodeToPermissions — catalog version mismatch rejected
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("decodeToPermissions with unsupported perm_ver throws IllegalArgumentException")
    void decodeToPermissionsWithFutureVersionThrows() {
        // Issue PERM-003: decode must be version-gated to the local catalog version.
        Set<PermissionCode> perms = EnumSet.of(
                PermissionCode.ACCOUNTING__JE__VIEW, // bit 0
                PermissionCode.CATALOG__PRODUCT__VIEW // bit 11
        );
        String encoded = PermissionBitsetCodec.encode(perms);
        assertThatThrownBy(() -> PermissionBitsetCodec.decodeToPermissions(encoded, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported permission catalog version");
    }

    // -------------------------------------------------------------------------
    // AC-3 (edge case): hasPermission
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hasPermission returns true when permission bit is set")
    void hasPermissionReturnsTrueWhenBitIsSet() {
        // Issue PERM-003: bit for ACCOUNTING__JE__VIEW (bit 0) must be found
        String encoded = PermissionBitsetCodec.encode(Set.of(PermissionCode.ACCOUNTING__JE__VIEW));
        assertThat(PermissionBitsetCodec.hasPermission(encoded, PermissionCode.ACCOUNTING__JE__VIEW))
                .isTrue();
    }

    @Test
    @DisplayName("hasPermission returns false when permission bit is not set")
    void hasPermissionReturnsFalseWhenBitIsNotSet() {
        // Issue PERM-003: only bit 0 is set; bit 1 (ACCOUNTING__JE__CREATE) must not
        // match
        String encoded = PermissionBitsetCodec.encode(Set.of(PermissionCode.ACCOUNTING__JE__VIEW));
        assertThat(PermissionBitsetCodec.hasPermission(encoded, PermissionCode.ACCOUNTING__JE__CREATE))
                .isFalse();
    }

    @Test
    @DisplayName("hasPermission correctly handles the last bit (bit 226)")
    void hasPermissionWorksForLastBit() {
        // Issue PERM-003: boundary check — bit 226 is the highest defined catalog bit
        String encoded = PermissionBitsetCodec.encode(Set.of(PermissionCode.MCP__CHAT__EXECUTE)); // bit 226
        assertThat(PermissionBitsetCodec.hasPermission(encoded, PermissionCode.MCP__CHAT__EXECUTE))
                .isTrue();
        assertThat(PermissionBitsetCodec.hasPermission(encoded, PermissionCode.ACCOUNTING__JE__VIEW))
                .isFalse();
    }
}
