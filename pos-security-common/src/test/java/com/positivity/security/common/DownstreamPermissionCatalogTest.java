package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class DownstreamPermissionCatalogTest {

    @Test
    void authorityForBit_knownIndex_returnsExpectedString() {
        // Bit 27 = crm:party:view
        assertThat(DownstreamPermissionCatalog.authorityForBit(27)).isEqualTo("PERM_crm:party:view");
    }

    @Test
    void authorityForBit_negativeIndex_returnsNull() {
        assertThat(DownstreamPermissionCatalog.authorityForBit(-1)).isNull();
    }

    @Test
    void authorityForBit_outOfRangeIndex_returnsNull() {
        assertThat(DownstreamPermissionCatalog.authorityForBit(100_000)).isNull();
    }

    @Test
    void authoritiesFromBitSet_givenSetBits_returnsMatchingPermissions() {
        BitSet bits = new BitSet();
        bits.set(27); // PERM_crm:party:view
        bits.set(28); // PERM_crm:party:search

        List<String> result = DownstreamPermissionCatalog.authoritiesFromBitSet(bits);

        assertThat(result).containsExactly("PERM_crm:party:view", "PERM_crm:party:search");
    }

    @Test
    void authoritiesFromBitSet_emptyBitSet_returnsEmptyList() {
        assertThat(DownstreamPermissionCatalog.authoritiesFromBitSet(new BitSet()))
                .isEmpty();
    }

    @Test
    void authoritiesFromBitSet_unknownBit_silentlySkipsIt() {
        BitSet bits = new BitSet();
        bits.set(99_999); // beyond catalog range

        assertThat(DownstreamPermissionCatalog.authoritiesFromBitSet(bits)).isEmpty();
    }

    @Test
    void catalogVersion_isPositive() {
        assertThat(DownstreamPermissionCatalog.CATALOG_VERSION).isGreaterThan(0);
    }

    @Test
    void authorityForBit_exactBoundaryIndex_returnsNull() {
        assertThat(DownstreamPermissionCatalog.authorityForBit(DownstreamPermissionCatalog.AUTHORITY_BY_BIT.length))
                .isNull();
    }
}
