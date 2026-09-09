package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.ExtLocationParentReplica;
import com.positivity.catalog.internal.entity.ExtLocationReplica;
import com.positivity.catalog.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.catalog.internal.repository.ExtLocationReplicaRepository;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The materialised location-scope ancestor sets this module answers scope checks from
 * (ADR-0061 §2, #1885). The closure itself is pinned in pos-domain-events'
 * {@code LocationAncestryTest}; what matters here is that this module feeds it the right edges,
 * writes the result back where {@link LocationHierarchyService#ancestorsOf} reads it, and answers
 * {@link AncestorSets#EMPTY} — the value the check reads as deny — for a location it does not hold.
 *
 * <p>The repositories are in-memory stand-ins rather than a database: {@code ext_location} and
 * {@code ext_location_parent} are plain key-value tables here, and the walk is the behaviour under
 * test.
 */
@DisplayName("pos-catalog LocationHierarchyService — materialised scope ancestor sets")
class LocationHierarchyServiceTest {

    private static final UUID HQ = id("a1");
    private static final UUID HQ_B = id("a2");
    private static final UUID REGION = id("b1");
    private static final UUID SHOP = id("d1");
    private static final UUID FIN_ROOT = id("f1");
    private static final UUID FIN_MID = id("f2");
    private static final UUID UNKNOWN = id("ff");

    private static UUID id(String suffix) {
        return UUID.fromString("018f0000-0000-7000-8000-0000000000" + suffix);
    }

    private final Map<UUID, ExtLocationReplica> locations = new HashMap<>();
    private final List<ExtLocationParentReplica> edges = new ArrayList<>();

    private LocationHierarchyService service;

    @BeforeEach
    void setUp() {
        ExtLocationReplicaRepository locationRepository = mock(ExtLocationReplicaRepository.class);
        ExtLocationParentReplicaRepository parentRepository = mock(ExtLocationParentReplicaRepository.class);

        when(locationRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(locations.get(i.getArgument(0))));
        when(locationRepository.existsById(any())).thenAnswer(i -> locations.containsKey(i.getArgument(0)));
        when(locationRepository.save(any())).thenAnswer(i -> {
            ExtLocationReplica row = i.getArgument(0);
            locations.put(row.getLocationId(), row);
            return row;
        });
        when(parentRepository.findByChildId(any()))
                .thenAnswer(i -> edges.stream()
                        .filter(e -> e.getChildId().equals(i.getArgument(0)))
                        .toList());
        when(parentRepository.findByParentId(any()))
                .thenAnswer(i -> edges.stream()
                        .filter(e -> e.getParentId().equals(i.getArgument(0)))
                        .toList());

        service = new LocationHierarchyService(locationRepository, parentRepository);
    }

    /** Replicates a location, replaces its edges ({@code "TYPE"} per parent) and recomputes. */
    private void replicate(UUID locationId, Map<UUID, String> parents) {
        locations.computeIfAbsent(
                locationId,
                key -> ExtLocationReplica.builder()
                        .locationId(key)
                        .name("Loc " + key)
                        .active(true)
                        .aggregateVersion(1L)
                        .updatedAt(Instant.EPOCH)
                        .build());
        edges.removeIf(e -> e.getChildId().equals(locationId));
        parents.forEach((parentId, parentType) -> edges.add(ExtLocationParentReplica.builder()
                .childId(locationId)
                .parentId(parentId)
                .parentType(parentType)
                .build()));
        service.recomputeAncestors(locationId);
    }

    @Test
    @DisplayName("a location the replica does not hold answers EMPTY, which the scope check reads as deny")
    void unknownLocationIsEmpty() {
        assertThat(service.ancestorsOf(UNKNOWN)).isEqualTo(AncestorSets.EMPTY);
    }

    @Test
    @DisplayName("inclusive-of-self: a root with no parents is its own sole ancestor on both dimensions")
    void inclusiveOfSelf() {
        replicate(HQ, Map.of());

        AncestorSets hq = service.ancestorsOf(HQ);
        assertThat(hq.financial()).containsExactly(HQ);
        assertThat(hq.other()).containsExactly(HQ);
    }

    @Test
    @DisplayName("FINANCIAL and OTHER edges are never crossed into each other's set")
    void dimensionsStaySeparate() {
        replicate(FIN_ROOT, Map.of());
        replicate(HQ, Map.of());
        replicate(REGION, Map.of(HQ, "HEADQUARTERS"));
        replicate(FIN_MID, Map.of(FIN_ROOT, "FINANCIAL"));
        replicate(SHOP, Map.of(REGION, "DISTRICT", FIN_MID, "FINANCIAL"));

        AncestorSets shop = service.ancestorsOf(SHOP);
        assertThat(shop.other()).containsExactlyInAnyOrder(SHOP, REGION, HQ);
        assertThat(shop.financial()).containsExactlyInAnyOrder(SHOP, FIN_MID, FIN_ROOT);
    }

    @Test
    @DisplayName("re-parenting a mid-level node rewrites the sets of every replicated descendant")
    void reparentingPropagates() {
        replicate(HQ, Map.of());
        replicate(HQ_B, Map.of());
        replicate(REGION, Map.of(HQ, "REGION"));
        replicate(SHOP, Map.of(REGION, "PHYSICAL"));
        assertThat(service.ancestorsOf(SHOP).other()).containsExactlyInAnyOrder(SHOP, REGION, HQ);

        replicate(REGION, Map.of(HQ_B, "REGION"));

        assertThat(service.ancestorsOf(SHOP).other()).containsExactlyInAnyOrder(SHOP, REGION, HQ_B);
        assertThat(service.ancestorsOf(SHOP).other()).doesNotContain(HQ);
    }

    @Test
    @DisplayName("a parent arriving after its child is counted from the edge, then completed when its own fact lands")
    void parentArrivingAfterChild() {
        replicate(SHOP, Map.of(FIN_MID, "FINANCIAL"));
        assertThat(service.ancestorsOf(SHOP).financial()).containsExactlyInAnyOrder(SHOP, FIN_MID);

        replicate(FIN_ROOT, Map.of());
        replicate(FIN_MID, Map.of(FIN_ROOT, "FINANCIAL"));

        assertThat(service.ancestorsOf(SHOP).financial()).containsExactlyInAnyOrder(SHOP, FIN_MID, FIN_ROOT);
    }
}
