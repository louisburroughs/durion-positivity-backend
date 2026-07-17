package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.rollup.LocationInventoryRollupResponse;
import com.positivity.inventory.internal.dto.rollup.RollupQuantities;
import com.positivity.inventory.internal.dto.rollup.SiteInventoryRollupResponse;
import com.positivity.inventory.internal.dto.rollup.StorageLocationRollupNode;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.RollupExpansionTooLargeException;
import com.positivity.inventory.internal.service.LocationInventoryRollupServiceImpl;
import com.positivity.inventory.internal.service.StorageLocationTopologyService;
import com.positivity.inventory.internal.service.StorageLocationTopologyService.LocationDescendant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LocationInventoryRollupServiceImpl} — CAP-218 #659.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocationInventoryRollupServiceImpl — CAP-218 #659 unit tests")
class LocationInventoryRollupServiceImplTest {

    private static final UUID BUILDING_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID SITE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SITE_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Mock
    private StorageLocationTopologyService topologyService;

    @Mock
    private SiteInventoryRollupService siteInventoryRollupService;

    private LocationInventoryRollupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LocationInventoryRollupServiceImpl(topologyService, siteInventoryRollupService, 25);
    }

    private static LocationDescendant descendant(UUID id, String name, int depth) {
        return new LocationDescendant(id, name, "CODE-" + name, "ACTIVE", BUILDING_ID, depth);
    }

    private static SiteInventoryRollupResponse siteRollup(UUID siteId, long onHand, long allocated) {
        StorageLocationRollupNode node = new StorageLocationRollupNode(
                UUID.randomUUID(),
                "Floor",
                "FLOOR",
                "ACTIVE",
                RollupQuantities.of(onHand, allocated),
                RollupQuantities.of(onHand, allocated),
                List.of());
        return new SiteInventoryRollupResponse(siteId, RollupQuantities.of(onHand, allocated), List.of(node));
    }

    @Test
    @DisplayName("grand total equals sum of site totals; summaries carry no trees by default")
    void rollup_summaries_grandTotalIsSumOfSites() {
        // Issue #659: per-site summaries plus grand total, nodes omitted without expand
        when(topologyService.fetchDescendants(BUILDING_ID, "PHYSICAL"))
                .thenReturn(List.of(descendant(SITE_A, "Site A", 1), descendant(SITE_B, "Site B", 1)));
        when(siteInventoryRollupService.getSiteInventoryRollup(SITE_A, null, null, false))
                .thenReturn(siteRollup(SITE_A, 100, 10));
        when(siteInventoryRollupService.getSiteInventoryRollup(SITE_B, null, null, false))
                .thenReturn(siteRollup(SITE_B, 50, 5));

        LocationInventoryRollupResponse response =
                service.getLocationInventoryRollup(BUILDING_ID, null, null, false, null, false);

        assertThat(response.locationId()).isEqualTo(BUILDING_ID);
        assertThat(response.parentType()).isEqualTo("PHYSICAL");
        assertThat(response.totals().onHand()).isEqualTo(150);
        assertThat(response.totals().allocated()).isEqualTo(15);
        assertThat(response.totals().available()).isEqualTo(135);
        assertThat(response.sites()).hasSize(2);
        assertThat(response.sites().getFirst().siteName()).isEqualTo("Site A");
        assertThat(response.sites().getFirst().nodes()).isNull();
    }

    @Test
    @DisplayName("expand=tree inlines per-site trees matching the site rollup output")
    void rollup_expandTree_inlinesSiteTrees() {
        // Issue #659: expand=tree carries full FR-1 trees per site
        when(topologyService.fetchDescendants(BUILDING_ID, "PHYSICAL"))
                .thenReturn(List.of(descendant(SITE_A, "Site A", 1)));
        SiteInventoryRollupResponse siteRollup = siteRollup(SITE_A, 100, 10);
        when(siteInventoryRollupService.getSiteInventoryRollup(SITE_A, "SKU-1", 3, true))
                .thenReturn(siteRollup);

        LocationInventoryRollupResponse response =
                service.getLocationInventoryRollup(BUILDING_ID, "PHYSICAL", "SKU-1", true, 3, true);

        assertThat(response.sites().getFirst().nodes()).isEqualTo(siteRollup.nodes());
    }

    @Test
    @DisplayName("expand=tree over the site cap → RollupExpansionTooLargeException, no site computation")
    void rollup_expandTreeOverCap_throws422() {
        // Issue #659: fan-out guard rejects before any per-site work
        List<LocationDescendant> many = IntStream.range(0, 26)
                .mapToObj(i -> descendant(UUID.randomUUID(), "Site " + i, 1))
                .toList();
        when(topologyService.fetchDescendants(BUILDING_ID, "PHYSICAL")).thenReturn(many);

        assertThatThrownBy(() -> service.getLocationInventoryRollup(BUILDING_ID, null, null, true, null, false))
                .isInstanceOf(RollupExpansionTooLargeException.class)
                .hasMessageContaining("26")
                .hasMessageContaining("25");
        verify(siteInventoryRollupService, never()).getSiteInventoryRollup(any(), any(), any(), eq(false));
    }

    @Test
    @DisplayName("over the cap WITHOUT expand still returns summaries")
    void rollup_overCapWithoutExpand_returnsSummaries() {
        // Issue #659: cap applies to expand=tree only
        List<LocationDescendant> many = IntStream.range(0, 26)
                .mapToObj(i -> descendant(UUID.randomUUID(), "Site " + i, 1))
                .toList();
        when(topologyService.fetchDescendants(BUILDING_ID, "PHYSICAL")).thenReturn(many);
        when(siteInventoryRollupService.getSiteInventoryRollup(any(), any(), any(), eq(false)))
                .thenAnswer(inv -> siteRollup(inv.getArgument(0), 1, 0));

        LocationInventoryRollupResponse response =
                service.getLocationInventoryRollup(BUILDING_ID, null, null, false, null, false);

        assertThat(response.sites()).hasSize(26);
        assertThat(response.totals().onHand()).isEqualTo(26);
    }

    @Test
    @DisplayName("unknown parentType → IllegalArgumentException (400); case-insensitive accepted values")
    void rollup_parentTypeValidation() {
        // Issue #659: parentType validated against the ParentType value set
        assertThatThrownBy(() -> service.getLocationInventoryRollup(BUILDING_ID, "BOGUS", null, false, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOGUS");

        when(topologyService.fetchDescendants(BUILDING_ID, "REGION")).thenReturn(List.of());
        LocationInventoryRollupResponse response =
                service.getLocationInventoryRollup(BUILDING_ID, "region", null, false, null, false);
        assertThat(response.parentType()).isEqualTo("REGION");
    }

    @Test
    @DisplayName("no descendants → 200 with empty sites and zero totals")
    void rollup_noDescendants_returnsEmpty() {
        // Issue #659: empty result, not an error
        when(topologyService.fetchDescendants(BUILDING_ID, "PHYSICAL")).thenReturn(List.of());

        LocationInventoryRollupResponse response =
                service.getLocationInventoryRollup(BUILDING_ID, null, null, false, null, false);

        assertThat(response.sites()).isEmpty();
        assertThat(response.totals()).isEqualTo(RollupQuantities.ZERO);
    }

    @Test
    @DisplayName("unknown location propagates LocationNotFoundException from the client")
    void rollup_unknownLocation_propagatesNotFound() {
        // Issue #659: pos-location 404 → 404 at the controller
        when(topologyService.fetchDescendants(BUILDING_ID, "PHYSICAL"))
                .thenThrow(new LocationNotFoundException(BUILDING_ID));

        assertThatThrownBy(() -> service.getLocationInventoryRollup(BUILDING_ID, null, null, false, null, false))
                .isInstanceOf(LocationNotFoundException.class);
    }
}
