package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtLocationParentReplica;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * PROXIMITY ordering (odoo-parity H1, issue #1037): candidate locations
 * ordered by topology distance to the selection's reference location,
 * nearest first — the "same zone first" rule.
 *
 * <p>Distance definition (documented): the number of hops between the
 * reference location and the candidate in the replicated location tree,
 * computed by breadth-first search from the reference over the union of
 * <ul>
 *   <li>{@code ext_storage_location} bin parent links
 *       ({@code parentStorageLocationId}), traversed in both directions, and
 *   <li>typed {@code ext_location_parent} edges (all parent types),
 *       traversed in both directions.
 * </ul>
 * Two bins in the same zone are two hops apart (bin → zone → bin) and beat
 * any bin in a sibling zone (four hops). The reference location itself is
 * distance zero.
 *
 * <p>Tie-breakers (deterministic): equal distances order by ascending
 * location id; candidates unreachable within {@link #MAX_DEPTH} hops sort
 * last, also by ascending location id. The engine never dispatches here
 * without a reference location (it falls back to FIFO).
 */
@Component
@RequiredArgsConstructor
public class ProximitySourcingStrategy implements SourcingOrderingStrategy {

    /** BFS depth cap; deeper nodes are treated as unreachable. */
    static final int MAX_DEPTH = 16;

    private final ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;
    private final ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    @Override
    public @NonNull SourcingStrategy strategy() {
        return SourcingStrategy.PROXIMITY;
    }

    @Override
    public @NonNull List<SourcingCandidate> order(@NonNull SourcingSelection selection) {
        UUID reference = Objects.requireNonNull(selection.referenceLocationId(), "referenceLocationId");
        if (selection.candidates().size() < 2) {
            return List.copyOf(selection.candidates());
        }

        Set<UUID> targets = new HashSet<>();
        selection.candidates().forEach(candidate -> targets.add(candidate.locationId()));
        Map<UUID, Integer> distance = bfsDistances(reference, targets);

        return selection.candidates().stream()
                .sorted(Comparator.comparing((SourcingCandidate candidate) ->
                                distance.getOrDefault(candidate.locationId(), Integer.MAX_VALUE))
                        .thenComparing(SourcingCandidate::locationId))
                .toList();
    }

    /**
     * Level-by-level BFS from the reference over the combined parent/child
     * edge set, with batched repository lookups per frontier. Stops when all
     * targets are found, the frontier empties, or {@link #MAX_DEPTH} is hit.
     */
    private Map<UUID, Integer> bfsDistances(UUID reference, Set<UUID> targets) {
        Map<UUID, Integer> distance = new HashMap<>();
        Set<UUID> visited = new HashSet<>();
        visited.add(reference);
        if (targets.contains(reference)) {
            distance.put(reference, 0);
        }

        List<UUID> frontier = List.of(reference);
        int depth = 0;
        while (!frontier.isEmpty() && depth < MAX_DEPTH && distance.size() < targets.size()) {
            depth++;
            List<UUID> next = new ArrayList<>();
            for (UUID neighbor : neighborsOf(frontier)) {
                if (!visited.add(neighbor)) {
                    continue;
                }
                next.add(neighbor);
                if (targets.contains(neighbor)) {
                    distance.put(neighbor, depth);
                }
            }
            frontier = next;
        }
        return distance;
    }

    private Set<UUID> neighborsOf(List<UUID> frontier) {
        Set<UUID> neighbors = new HashSet<>();
        // Bin tree: parents of the frontier...
        for (ExtStorageLocationReplica bin : extStorageLocationReplicaRepository.findAllById(frontier)) {
            if (bin.getParentStorageLocationId() != null) {
                neighbors.add(bin.getParentStorageLocationId());
            }
        }
        // ...and children of the frontier.
        for (ExtStorageLocationReplica child :
                extStorageLocationReplicaRepository.findByParentStorageLocationIdIn(frontier)) {
            neighbors.add(child.getStorageLocationId());
        }
        // Typed location edges, both directions, all parent types.
        for (ExtLocationParentReplica edge : extLocationParentReplicaRepository.findByChildIdIn(frontier)) {
            neighbors.add(edge.getParentId());
        }
        for (ExtLocationParentReplica edge : extLocationParentReplicaRepository.findByParentIdIn(frontier)) {
            neighbors.add(edge.getChildId());
        }
        return neighbors;
    }
}
