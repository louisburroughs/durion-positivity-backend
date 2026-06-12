package com.positivity.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.client.PersonClient;
import com.positivity.location.internal.dto.LocationDescendantResponseDTO;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.internal.repository.LocationParentRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.LocationTypeRepository;
import com.positivity.location.internal.service.LocationServiceImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the descendants traversal in {@link LocationServiceImpl}.
 *
 * Issue: CAP-214 #655
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocationDescendantsServiceTest")
class LocationDescendantsServiceTest {

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LocationParentRepository locationParentRepository;

    @Mock
    private LocationTypeRepository locationTypeRepository;

    @Mock
    private PersonClient personClient;

    @InjectMocks
    private LocationServiceImpl locationService;

    private static Location location(UUID id, String name) {
        return Location.builder()
                .id(id)
                .name(name)
                .code("CODE-" + name)
                .status("ACTIVE")
                .build();
    }

    private static LocationParent edge(Location parent, Location child) {
        return LocationParent.builder()
                .parent(parent)
                .child(child)
                .parentType(ParentType.PHYSICAL)
                .build();
    }

    /** Stubs the batched finder to serve edges from an adjacency map. */
    private void stubEdges(Map<UUID, List<LocationParent>> edgesByParent) {
        when(locationParentRepository.findByParent_IdInAndParentType(anyCollection(), eq(ParentType.PHYSICAL)))
                .thenAnswer(invocation -> {
                    Collection<UUID> parentIds = invocation.getArgument(0);
                    List<LocationParent> edges = new ArrayList<>();
                    for (UUID parentId : parentIds) {
                        edges.addAll(edgesByParent.getOrDefault(parentId, List.of()));
                    }
                    return edges;
                });
    }

    @Test
    @DisplayName("#655 - three-level PHYSICAL hierarchy returns descendants with depth and parentId")
    void getDescendantsDto_threeLevelHierarchy_returnsDepthAndParentId() {
        Location root = location(UUID.randomUUID(), "Root");
        Location childA = location(UUID.randomUUID(), "ChildA");
        Location grandchild = location(UUID.randomUUID(), "Grandchild");
        Location greatGrandchild = location(UUID.randomUUID(), "GreatGrandchild");

        when(locationRepository.existsById(root.getId())).thenReturn(true);
        stubEdges(Map.of(
                root.getId(), List.of(edge(root, childA)),
                childA.getId(), List.of(edge(childA, grandchild)),
                grandchild.getId(), List.of(edge(grandchild, greatGrandchild))));

        List<LocationDescendantResponseDTO> result = locationService.getDescendantsDto(root.getId(), "PHYSICAL");

        assertThat(result).hasSize(3);

        assertThat(result.get(0).getId()).isEqualTo(childA.getId());
        assertThat(result.get(0).getParentId()).isEqualTo(root.getId());
        assertThat(result.get(0).getDepth()).isEqualTo(1);
        assertThat(result.get(0).getName()).isEqualTo("ChildA");
        assertThat(result.get(0).getCode()).isEqualTo("CODE-ChildA");
        assertThat(result.get(0).getStatus()).isEqualTo("ACTIVE");

        assertThat(result.get(1).getId()).isEqualTo(grandchild.getId());
        assertThat(result.get(1).getParentId()).isEqualTo(childA.getId());
        assertThat(result.get(1).getDepth()).isEqualTo(2);

        assertThat(result.get(2).getId()).isEqualTo(greatGrandchild.getId());
        assertThat(result.get(2).getParentId()).isEqualTo(grandchild.getId());
        assertThat(result.get(2).getDepth()).isEqualTo(3);
    }

    @Test
    @DisplayName("#655 - unknown locationId throws 404")
    void getDescendantsDto_unknownLocation_throwsNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(locationRepository.existsById(unknownId)).thenReturn(false);

        assertThatThrownBy(() -> locationService.getDescendantsDto(unknownId, "PHYSICAL"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("#655 - invalid parentType throws 400")
    void getDescendantsDto_invalidParentType_throwsBadRequest() {
        assertThatThrownBy(() -> locationService.getDescendantsDto(UUID.randomUUID(), "NOT_A_TYPE"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("#655 - synthetic cycle terminates and yields each location once")
    void getDescendantsDto_syntheticCycle_terminates() {
        Location nodeA = location(UUID.randomUUID(), "NodeA");
        Location nodeB = location(UUID.randomUUID(), "NodeB");

        when(locationRepository.existsById(nodeA.getId())).thenReturn(true);
        stubEdges(Map.of(
                nodeA.getId(), List.of(edge(nodeA, nodeB)),
                nodeB.getId(), List.of(edge(nodeB, nodeA))));

        List<LocationDescendantResponseDTO> result = locationService.getDescendantsDto(nodeA.getId(), "PHYSICAL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(nodeB.getId());
        assertThat(result.get(0).getDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("#655 - traversal depth is capped at 20")
    void getDescendantsDto_deepChain_depthCappedAtTwenty() {
        List<Location> chain = new ArrayList<>();
        for (int i = 0; i <= 25; i++) {
            chain.add(location(UUID.randomUUID(), "Level" + i));
        }
        Map<UUID, List<LocationParent>> edges = new HashMap<>();
        for (int i = 0; i < 25; i++) {
            edges.put(chain.get(i).getId(), List.of(edge(chain.get(i), chain.get(i + 1))));
        }

        when(locationRepository.existsById(chain.get(0).getId())).thenReturn(true);
        stubEdges(edges);

        List<LocationDescendantResponseDTO> result =
                locationService.getDescendantsDto(chain.get(0).getId(), "PHYSICAL");

        assertThat(result).hasSize(20);
        assertThat(result.get(result.size() - 1).getDepth()).isEqualTo(20);
    }

    @Test
    @DisplayName("#655 - location without descendants returns empty list")
    void getDescendantsDto_noDescendants_returnsEmptyList() {
        UUID rootId = UUID.randomUUID();
        when(locationRepository.existsById(rootId)).thenReturn(true);
        stubEdges(Map.of());

        assertThat(locationService.getDescendantsDto(rootId, "PHYSICAL")).isEmpty();
    }

    @Test
    @DisplayName("#655 - null parentType defaults to PHYSICAL")
    void getDescendantsDto_nullParentType_defaultsToPhysical() {
        UUID rootId = UUID.randomUUID();
        when(locationRepository.existsById(rootId)).thenReturn(true);
        stubEdges(Map.of());

        locationService.getDescendantsDto(rootId, null);

        verify(locationParentRepository).findByParent_IdInAndParentType(eq(List.of(rootId)), eq(ParentType.PHYSICAL));
    }
}
