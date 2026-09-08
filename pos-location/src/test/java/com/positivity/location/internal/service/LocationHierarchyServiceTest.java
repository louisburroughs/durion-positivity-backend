package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.internal.repository.LocationParentRepository;
import com.positivity.location.internal.repository.LocationRepository;
import java.util.ArrayList;
import java.util.HashMap;
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
 * {@link LocationHierarchyService} as this module's {@code LocationAncestorResolver} (ADR-0061,
 * #1872): the ancestor sets a scope check intersects are computed live from the owner's own
 * {@code location_parent} edges, per dimension, inclusive of self, and bounded.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocationHierarchyService.ancestorsOf (ADR-0061, #1872)")
class LocationHierarchyServiceTest {

    private static final UUID SITE = id("0a");
    private static final UUID REGION = id("10");
    private static final UUID DISTRICT = id("20");
    private static final UUID HQ = id("30");
    private static final UUID FIN_A = id("40");
    private static final UUID FIN_B = id("50");
    private static final UUID LEAF = id("60");
    private static final UUID UNKNOWN = id("ff");

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LocationParentRepository locationParentRepository;

    private LocationHierarchyService service;

    private final Map<UUID, List<LocationParent>> edges = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new LocationHierarchyService(locationRepository, locationParentRepository);
    }

    private void stubEdges() {
        when(locationParentRepository.findByChild_Id(any()))
                .thenAnswer(invocation -> edges.getOrDefault(invocation.getArgument(0), List.of()));
    }

    private void edge(UUID child, UUID parent, ParentType type) {
        edges.computeIfAbsent(child, ignored -> new ArrayList<>())
                .add(LocationParent.builder()
                        .child(Location.builder().id(child).build())
                        .parent(Location.builder().id(parent).build())
                        .parentType(type)
                        .build());
    }

    private static UUID id(String suffix) {
        return UUID.fromString("019200cc-0000-7000-8000-0000000000" + suffix);
    }

    @Test
    @DisplayName("FINANCIAL follows only FINANCIAL edges; OTHER follows the seven other types and dedupes a DAG")
    void walksEachDimensionOverItsOwnEdgeTypes() {
        when(locationRepository.existsById(SITE)).thenReturn(true);
        edge(SITE, REGION, ParentType.REGION);
        edge(SITE, DISTRICT, ParentType.DISTRICT);
        edge(SITE, FIN_A, ParentType.FINANCIAL);
        edge(REGION, HQ, ParentType.HEADQUARTERS);
        edge(DISTRICT, HQ, ParentType.ORGANIZATIONAL);
        edge(FIN_A, FIN_B, ParentType.FINANCIAL);
        edge(FIN_B, HQ, ParentType.REGION); // a non-financial edge above the financial chain: not followed
        stubEdges();

        AncestorSets sets = service.ancestorsOf(SITE);

        assertThat(sets.financial()).containsExactlyInAnyOrder(SITE, FIN_A, FIN_B);
        assertThat(sets.other()).containsExactlyInAnyOrder(SITE, REGION, DISTRICT, HQ);
    }

    @Test
    @DisplayName("a location with no edges answers itself on both dimensions (inclusive of self)")
    void leafIsItsOwnAncestor() {
        when(locationRepository.existsById(LEAF)).thenReturn(true);
        stubEdges();

        AncestorSets sets = service.ancestorsOf(LEAF);

        assertThat(sets.financial()).containsExactly(LEAF);
        assertThat(sets.other()).containsExactly(LEAF);
        assertThat(sets.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("an id with no location row answers EMPTY without touching the edges (caller fails closed)")
    void unknownLocationAnswersEmpty() {
        when(locationRepository.existsById(UNKNOWN)).thenReturn(false);

        AncestorSets sets = service.ancestorsOf(UNKNOWN);

        assertThat(sets).isSameAs(AncestorSets.EMPTY);
        assertThat(sets.isEmpty()).isTrue();
        verify(locationParentRepository, never()).findByChild_Id(any());
    }

    @Test
    @DisplayName("a chain deeper than MAX_DEPTH is cut at the cap and the far ancestors are left out (err toward deny)")
    void depthGuardTruncatesPathologicalChain() {
        int chainLength = LocationAncestry.MAX_DEPTH + 5;
        List<UUID> chain = new ArrayList<>();
        for (int i = 0; i <= chainLength; i++) {
            chain.add(UUID.fromString(String.format("019200dd-0000-7000-8000-%012x", i)));
        }
        UUID start = chain.get(0);
        when(locationRepository.existsById(start)).thenReturn(true);
        for (int i = 0; i < chainLength; i++) {
            edge(chain.get(i), chain.get(i + 1), ParentType.FINANCIAL);
        }
        stubEdges();

        AncestorSets sets = service.ancestorsOf(start);

        assertThat(sets.financial()).hasSize(LocationAncestry.MAX_DEPTH + 1);
        assertThat(sets.financial()).contains(start, chain.get(LocationAncestry.MAX_DEPTH));
        assertThat(sets.financial()).doesNotContain(chain.get(chainLength));
        assertThat(sets.other()).containsExactly(start);
    }

    @Test
    @DisplayName("a pre-existing cycle on a dimension terminates and answers the nodes on it")
    void cycleTerminates() {
        when(locationRepository.existsById(SITE)).thenReturn(true);
        edge(SITE, REGION, ParentType.PHYSICAL);
        edge(REGION, SITE, ParentType.SHIPPING);
        stubEdges();

        AncestorSets sets = service.ancestorsOf(SITE);

        assertThat(sets.other()).containsExactlyInAnyOrder(SITE, REGION);
        assertThat(sets.financial()).containsExactly(SITE);
    }
}
