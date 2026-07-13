package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtLocationParentReplica;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Site storage-location topology and location-descendant queries served from the
 * {@code ext_storage_location} and {@code ext_location_parent} replicas (ADR-0044 §6, #892).
 * Replaces the retired synchronous {@code StorageLocationTopologyClient}; the record shapes are
 * pinned by the CAP-214 #655 contract tests and unchanged.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageLocationTopologyService {

    private final LocationRefRepository locationRefRepository;
    private final ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;
    private final ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    /**
     * Fetches the complete flat storage-location topology for a site.
     *
     * @throws LocationNotFoundException when the site is not in the location replica
     */
    @NonNull
    public List<StorageLocationNode> fetchSiteTopology(@NonNull UUID siteId) {
        requireKnownLocation(siteId);
        return extStorageLocationReplicaRepository.findBySiteId(siteId).stream()
                .map(replica -> new StorageLocationNode(
                        replica.getStorageLocationId(),
                        replica.getName(),
                        replica.getType(),
                        replica.getStatus(),
                        replica.getParentStorageLocationId()))
                .toList();
    }

    /**
     * Fetches the flat list of descendant locations of a parent location along the given typed
     * parent chain (CAP-214 #655 descendants contract) by walking the replicated edges level by
     * level, exactly like the owner's traversal.
     *
     * @param parentType pos-location {@code ParentType} name, e.g. {@code PHYSICAL}
     * @throws LocationNotFoundException when the location is not in the location replica
     */
    @NonNull
    public List<LocationDescendant> fetchDescendants(@NonNull UUID locationId, @NonNull String parentType) {
        requireKnownLocation(locationId);
        List<LocationDescendant> descendants = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        visited.add(locationId);
        List<UUID> frontier = List.of(locationId);
        int depth = 0;
        while (!frontier.isEmpty()) {
            depth++;
            List<ExtLocationParentReplica> edges =
                    extLocationParentReplicaRepository.findByParentIdInAndParentType(frontier, parentType);
            List<UUID> next = new ArrayList<>();
            Map<UUID, LocationRefEntity> children = childRefsById(edges);
            for (ExtLocationParentReplica edge : edges) {
                if (!visited.add(edge.getChildId())) {
                    continue;
                }
                next.add(edge.getChildId());
                LocationRefEntity ref = children.get(edge.getChildId());
                descendants.add(new LocationDescendant(
                        edge.getChildId(),
                        ref == null ? null : ref.getName(),
                        ref == null ? null : ref.getCode(),
                        ref == null ? null : ref.getStatus(),
                        edge.getParentId(),
                        depth));
            }
            frontier = next;
        }
        return descendants;
    }

    private void requireKnownLocation(@NonNull UUID locationId) {
        if (locationRefRepository.findByLocationId(locationId).isEmpty()) {
            throw new LocationNotFoundException(locationId);
        }
    }

    private Map<UUID, LocationRefEntity> childRefsById(@NonNull List<ExtLocationParentReplica> edges) {
        List<UUID> childIds =
                edges.stream().map(ExtLocationParentReplica::getChildId).toList();
        Map<UUID, LocationRefEntity> byId = new HashMap<>();
        if (childIds.isEmpty()) {
            return byId;
        }
        locationRefRepository.findByLocationIdIn(childIds).forEach(ref -> byId.put(ref.getLocationId(), ref));
        return byId;
    }

    /**
     * Consumer-side projection of the pos-location topology contract. Fields pinned by the
     * CAP-214 #655 contract tests: id, name, type, status, parentStorageLocationId.
     */
    public record StorageLocationNode(UUID id, String name, String type, String status, UUID parentStorageLocationId) {}

    /**
     * Consumer-side projection of the pos-location descendants contract (CAP-214 #655): id, name,
     * code, status, parentId, depth.
     */
    public record LocationDescendant(UUID id, String name, String code, String status, UUID parentId, int depth) {}
}
