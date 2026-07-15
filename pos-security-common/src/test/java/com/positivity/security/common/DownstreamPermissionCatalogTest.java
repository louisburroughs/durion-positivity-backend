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
    void authorityForBit_peopleContactBlock_matchesGatewayCatalogAssignments() {
        // Bits 351-359 were assigned to pos-people-contact in the gateway catalog
        // (ADR-0044 Phase 3 split #874) but were missing here, shifting every
        // later bit and causing 403s for people-contact endpoints.
        assertThat(DownstreamPermissionCatalog.authorityForBit(351)).isEqualTo("PERM_people-contact:person:view");
        assertThat(DownstreamPermissionCatalog.authorityForBit(352)).isEqualTo("PERM_people-contact:person:create");
        assertThat(DownstreamPermissionCatalog.authorityForBit(353)).isEqualTo("PERM_people-contact:person:edit");
        assertThat(DownstreamPermissionCatalog.authorityForBit(354)).isEqualTo("PERM_people-contact:person:delete");
        assertThat(DownstreamPermissionCatalog.authorityForBit(355)).isEqualTo("PERM_people-contact:role:view");
        assertThat(DownstreamPermissionCatalog.authorityForBit(356)).isEqualTo("PERM_people-contact:role:assign");
        assertThat(DownstreamPermissionCatalog.authorityForBit(357)).isEqualTo("PERM_people-contact:role:revoke");
        assertThat(DownstreamPermissionCatalog.authorityForBit(358)).isEqualTo("PERM_people-contact:userLink:view");
        assertThat(DownstreamPermissionCatalog.authorityForBit(359)).isEqualTo("PERM_people-contact:userLink:write");
        assertThat(DownstreamPermissionCatalog.authorityForBit(360)).isEqualTo("PERM_people:compliance:view");
        assertThat(DownstreamPermissionCatalog.authorityForBit(361)).isEqualTo("PERM_shop:technician:view");
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
