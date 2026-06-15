package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.client.StorageLocationTopologyClient;
import com.positivity.inventory.internal.client.StorageLocationTopologyClient.StorageLocationNode;
import com.positivity.inventory.internal.dto.rollup.SiteInventoryRollupResponse;
import com.positivity.inventory.internal.dto.rollup.StorageLocationRollupNode;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.LocationServiceUnavailableException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.SiteInventoryQuantityLoader;
import com.positivity.inventory.internal.service.SiteInventoryRollupServiceImpl;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SiteInventoryRollupServiceImpl} — CAP-218 #658.
 *
 * <p>Topology client is mocked; quantities come through a real
 * {@link SiteInventoryQuantityLoader} backed by a mocked repository so the
 * loader's sku/no-sku branching is exercised too.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SiteInventoryRollupServiceImpl — CAP-218 #658 unit tests")
class SiteInventoryRollupServiceImplTest {

    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FLOOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID SHELF_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID BIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    private StorageLocationTopologyClient topologyClient;

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    private SiteInventoryRollupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SiteInventoryRollupServiceImpl(topologyClient, new SiteInventoryQuantityLoader(ledgerRepository));
    }

    private static List<StorageLocationNode> threeLevelTopology() {
        return List.of(
                new StorageLocationNode(FLOOR_ID, "Floor A", "FLOOR", "ACTIVE", null),
                new StorageLocationNode(SHELF_ID, "Shelf A-1", "SHELF", "ACTIVE", FLOOR_ID),
                new StorageLocationNode(BIN_ID, "Bin A-1-1", "BIN", "ACTIVE", SHELF_ID));
    }

    @Test
    @DisplayName("three-level tree: own vs rolledUp math and site totals are correct")
    void rollup_threeLevelTree_correctOwnAndRolledUp() {
        // Issue #658: rolledUp = own + sum of descendants' own, depth-first
        when(topologyClient.fetchSiteTopology(SITE_ID)).thenReturn(threeLevelTopology());
        when(ledgerRepository.sumQuantityByLocationChunked(any(), any()))
                .thenReturn(Map.of(SHELF_ID, 120L, BIN_ID, 360L));
        when(ledgerRepository.calculateOutstandingAllocationsByLocation(any()))
                .thenReturn(Map.of(SHELF_ID, 10L, BIN_ID, 20L));

        SiteInventoryRollupResponse response = service.getSiteInventoryRollup(SITE_ID, null, null, true);

        assertThat(response.siteId()).isEqualTo(SITE_ID);
        assertThat(response.totals().onHand()).isEqualTo(480);
        assertThat(response.totals().allocated()).isEqualTo(30);
        assertThat(response.totals().available()).isEqualTo(450);

        StorageLocationRollupNode floor = response.nodes().getFirst();
        assertThat(floor.storageLocationId()).isEqualTo(FLOOR_ID);
        assertThat(floor.own().onHand()).isZero();
        assertThat(floor.rolledUp().onHand()).isEqualTo(480);
        assertThat(floor.rolledUp().available()).isEqualTo(450);

        StorageLocationRollupNode shelf = floor.children().getFirst();
        assertThat(shelf.own().onHand()).isEqualTo(120);
        assertThat(shelf.rolledUp().onHand()).isEqualTo(480);

        StorageLocationRollupNode bin = shelf.children().getFirst();
        assertThat(bin.own().onHand()).isEqualTo(360);
        assertThat(bin.rolledUp().onHand()).isEqualTo(360);
        assertThat(bin.children()).isEmpty();
    }

    @Test
    @DisplayName("sku filter routes through SKU-scoped queries including CREATED-RELEASED allocation math")
    void rollup_skuFilter_usesSkuScopedQueries() {
        // Issue #658: when sku given, all quantities are for that SKU only
        when(topologyClient.fetchSiteTopology(SITE_ID)).thenReturn(threeLevelTopology());
        when(ledgerRepository.sumQuantityByLocationForSkuChunked(eq("SKU-1"), any(), any()))
                .thenAnswer(invocation -> {
                    Collection<InventoryLedgerEventType> types = invocation.getArgument(2);
                    if (types.contains(InventoryLedgerEventType.ALLOCATION_CREATED)) {
                        return Map.of(BIN_ID, 7L);
                    }
                    if (types.contains(InventoryLedgerEventType.ALLOCATION_RELEASED)) {
                        return Map.of(BIN_ID, 2L);
                    }
                    return Map.of(BIN_ID, 50L); // on-hand types
                });

        SiteInventoryRollupResponse response = service.getSiteInventoryRollup(SITE_ID, "SKU-1", null, true);

        assertThat(response.totals().onHand()).isEqualTo(50);
        assertThat(response.totals().allocated()).isEqualTo(5);
        assertThat(response.totals().available()).isEqualTo(45);
    }

    @Test
    @DisplayName("orphan parent id attaches node to root")
    void rollup_orphanParentId_attachesToRoot() {
        // Issue #658: node whose parent id is missing from the site set becomes a root
        UUID ghostParent = UUID.fromString("00000000-0000-0000-0000-00000000dead");
        when(topologyClient.fetchSiteTopology(SITE_ID))
                .thenReturn(List.of(
                        new StorageLocationNode(FLOOR_ID, "Floor A", "FLOOR", "ACTIVE", null),
                        new StorageLocationNode(SHELF_ID, "Orphan Shelf", "SHELF", "ACTIVE", ghostParent)));
        when(ledgerRepository.sumQuantityByLocationChunked(any(), any())).thenReturn(Map.of(SHELF_ID, 5L));
        when(ledgerRepository.calculateOutstandingAllocationsByLocation(any())).thenReturn(Map.of());

        SiteInventoryRollupResponse response = service.getSiteInventoryRollup(SITE_ID, null, null, true);

        assertThat(response.nodes()).hasSize(2);
        assertThat(response.totals().onHand()).isEqualTo(5);
    }

    @Test
    @DisplayName("negative available passes through unclamped")
    void rollup_overAllocated_negativeAvailableUnclamped() {
        // Issue #658: available may be negative — real over-allocation signal
        when(topologyClient.fetchSiteTopology(SITE_ID))
                .thenReturn(List.of(new StorageLocationNode(BIN_ID, "Bin", "BIN", "ACTIVE", null)));
        when(ledgerRepository.sumQuantityByLocationChunked(any(), any())).thenReturn(Map.of(BIN_ID, 3L));
        when(ledgerRepository.calculateOutstandingAllocationsByLocation(any())).thenReturn(Map.of(BIN_ID, 8L));

        SiteInventoryRollupResponse response = service.getSiteInventoryRollup(SITE_ID, null, null, true);

        assertThat(response.totals().available()).isEqualTo(-5);
    }

    @Test
    @DisplayName("includeEmpty=false prunes all-zero nodes; parents of non-empty children survive")
    void rollup_includeEmptyFalse_prunesZeroNodes() {
        // Issue #658: empty BIN pruned; FLOOR kept (rolledUp nonzero via SHELF)
        UUID emptyBin = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        when(topologyClient.fetchSiteTopology(SITE_ID))
                .thenReturn(List.of(
                        new StorageLocationNode(FLOOR_ID, "Floor A", "FLOOR", "ACTIVE", null),
                        new StorageLocationNode(SHELF_ID, "Shelf A-1", "SHELF", "ACTIVE", FLOOR_ID),
                        new StorageLocationNode(emptyBin, "Empty Bin", "BIN", "ACTIVE", FLOOR_ID)));
        when(ledgerRepository.sumQuantityByLocationChunked(any(), any())).thenReturn(Map.of(SHELF_ID, 9L));
        when(ledgerRepository.calculateOutstandingAllocationsByLocation(any())).thenReturn(Map.of());

        SiteInventoryRollupResponse response = service.getSiteInventoryRollup(SITE_ID, null, null, false);

        StorageLocationRollupNode floor = response.nodes().getFirst();
        assertThat(response.nodes()).hasSize(1);
        assertThat(floor.children()).hasSize(1);
        assertThat(floor.children().getFirst().storageLocationId()).isEqualTo(SHELF_ID);
    }

    @Test
    @DisplayName("depth=1 returns roots only; totals remain full-tree")
    void rollup_depthOne_truncatesChildrenButKeepsFullTotals() {
        // Issue #658: depth truncates the returned tree, not the math
        when(topologyClient.fetchSiteTopology(SITE_ID)).thenReturn(threeLevelTopology());
        when(ledgerRepository.sumQuantityByLocationChunked(any(), any())).thenReturn(Map.of(BIN_ID, 360L));
        when(ledgerRepository.calculateOutstandingAllocationsByLocation(any())).thenReturn(Map.of());

        SiteInventoryRollupResponse response = service.getSiteInventoryRollup(SITE_ID, null, 1, true);

        StorageLocationRollupNode floor = response.nodes().getFirst();
        assertThat(floor.children()).isEmpty();
        assertThat(floor.rolledUp().onHand()).isEqualTo(360);
        assertThat(response.totals().onHand()).isEqualTo(360);
    }

    @Test
    @DisplayName("site with no storage locations returns empty nodes and zero totals")
    void rollup_emptySite_returnsEmptyResponse() {
        // Issue #658: 200 with empty nodes, not 404
        when(topologyClient.fetchSiteTopology(SITE_ID)).thenReturn(List.of());

        SiteInventoryRollupResponse response = service.getSiteInventoryRollup(SITE_ID, null, null, false);

        assertThat(response.nodes()).isEmpty();
        assertThat(response.totals().onHand()).isZero();
        assertThat(response.totals().allocated()).isZero();
    }

    @Test
    @DisplayName("unknown site propagates LocationNotFoundException from the topology client")
    void rollup_unknownSite_propagatesNotFound() {
        // Issue #658: pos-location 404 → 404 ProblemDetail at the controller
        when(topologyClient.fetchSiteTopology(SITE_ID)).thenThrow(new LocationNotFoundException(SITE_ID));

        assertThatThrownBy(() -> service.getSiteInventoryRollup(SITE_ID, null, null, false))
                .isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    @DisplayName("pos-location outage propagates LocationServiceUnavailableException")
    void rollup_locationOutage_propagatesUnavailable() {
        // Issue #658: never fabricate partial topology; surface 503
        when(topologyClient.fetchSiteTopology(SITE_ID))
                .thenThrow(new LocationServiceUnavailableException("down", null));

        assertThatThrownBy(() -> service.getSiteInventoryRollup(SITE_ID, null, null, false))
                .isInstanceOf(LocationServiceUnavailableException.class);
    }
}
