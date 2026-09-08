package com.positivity.domainevents.location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;

/**
 * Pure ancestor/descendant closure over the typed location-parent edges carried on
 * {@link LocationUpdatedV1#parents()} (ADR-0061 §1–§2, issue #1878).
 *
 * <p>Location scope is assigned to a node and covers that node and every descendant, evaluated at
 * check time inside each consuming service against a <em>locally replicated</em> ancestor set —
 * never by calling pos-location per request. The hierarchy dimension is a property of the role
 * ({@code roles.location_hierarchy}), so every replica needs two materialised sets per location:
 *
 * <ul>
 *   <li>{@link Dimension#FINANCIAL} walks only the {@code FINANCIAL} parent chain — one parent per
 *       location, so the closure is a chain.</li>
 *   <li>{@link Dimension#OTHER} walks the union of every non-financial parent type — pos-location's
 *       {@code ParentType} has eight values, so seven: {@code HOME_OFFICE}, {@code HEADQUARTERS},
 *       {@code REGION}, {@code DISTRICT}, {@code PHYSICAL}, {@code ORGANIZATIONAL} and
 *       {@code SHIPPING}. Up to seven distinct parents per location, so the closure is a DAG and
 *       the walk dedupes with a visited set. (ADR-0061 §2 counts six; it predates the
 *       {@code SHIPPING} constant. The split is "FINANCIAL versus everything else".)</li>
 * </ul>
 *
 * <p>Both closures are <b>inclusive of the start node</b>, so a directly assigned node matches by
 * the same {@code loc_scope ∩ ancestors(L, dim) ≠ ∅} rule as an ancestor with no special case.
 *
 * <p>This class is deliberately free of Spring, JPA and any consumer's entity types: the walk is
 * expressed over a caller-supplied adjacency function so pos-people, pos-invoice and pos-workorder
 * share one implementation without a cross-module import (ADR-0026). It is the same reason
 * {@link com.positivity.domainevents.ReplicaVersionGuard} lives here.
 *
 * <p><b>Termination.</b> pos-location guarantees no equivalent of a cycle check on the location
 * tree today (ADR-0061 §2), so every walk is bounded twice: a visited set makes a cycle
 * terminate, and {@link #MAX_DEPTH} caps how far a legitimate-looking graph is followed. A walk
 * that hits the cap returns what it has collected so far and reports {@link Closure#truncated()};
 * the missing far ancestors err toward <em>deny</em> at the scope check, which is the safe
 * direction. Results are deterministic: parents are visited in {@code (parentType, parentId)}
 * order and the returned sets preserve breadth-first insertion order.
 */
public final class LocationAncestry {

    /**
     * Upper bound on hierarchy depth followed by any walk. An organisational hierarchy deeper than
     * this is not a legitimate state; the cap exists so a corrupted replica cannot turn a fact
     * into an unbounded traversal.
     */
    public static final int MAX_DEPTH = 64;

    private static final Comparator<LocationUpdatedV1.ParentRef> DETERMINISTIC_EDGE_ORDER = Comparator.comparing(
                    LocationUpdatedV1.ParentRef::parentType)
            .thenComparing(edge -> edge.parentId().toString());

    private static final Comparator<UUID> DETERMINISTIC_ID_ORDER = Comparator.comparing(UUID::toString);

    private LocationAncestry() {}

    /**
     * The two hierarchy dimensions a location-scoped role may traverse (ADR-0061 §2). Each maps to
     * the pos-location {@code ParentType} names whose edges it follows.
     */
    public enum Dimension {
        /** The {@code FINANCIAL} parent chain — accounting and general-manager roles. */
        FINANCIAL(Set.of("FINANCIAL")),

        /** The union of the seven non-financial parent types — every other role. */
        OTHER(Set.of("HOME_OFFICE", "HEADQUARTERS", "REGION", "DISTRICT", "PHYSICAL", "ORGANIZATIONAL", "SHIPPING"));

        private final Set<String> parentTypes;

        Dimension(Set<String> parentTypes) {
            this.parentTypes = parentTypes;
        }

        /**
         * Whether an edge of the given owner {@code ParentType} name is followed on this dimension.
         *
         * @param parentType the owner's {@code ParentType} name as carried on the fact
         * @return true when this dimension traverses that edge type
         */
        public boolean traverses(@NonNull String parentType) {
            return parentTypes.contains(parentType);
        }

        /**
         * The owner {@code ParentType} names this dimension traverses.
         *
         * @return an immutable set of parent-type names
         */
        public @NonNull Set<String> parentTypes() {
            return parentTypes;
        }
    }

    /**
     * Result of one bounded walk.
     *
     * @param ids the ids reached, in deterministic breadth-first order (immutable)
     * @param truncated true when the walk stopped at {@link #MAX_DEPTH} before exhausting the
     *     graph, so {@code ids} may be incomplete
     */
    public record Closure(@NonNull Set<UUID> ids, boolean truncated) {
        public Closure {
            ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        }
    }

    /**
     * The two materialised ancestor sets a replica stores per location, both inclusive of the
     * location itself. This is the shape a scope check consumes (issue #1870).
     *
     * @param financial ancestors along the {@link Dimension#FINANCIAL} chain, inclusive of self
     * @param other ancestors along the {@link Dimension#OTHER} union, inclusive of self
     */
    public record AncestorSets(
            @NonNull Set<UUID> financial, @NonNull Set<UUID> other) {

        /** The sets for a location the replica does not know — the caller treats absent as deny. */
        public static final AncestorSets EMPTY = new AncestorSets(Set.of(), Set.of());

        public AncestorSets {
            financial = Collections.unmodifiableSet(new LinkedHashSet<>(financial));
            other = Collections.unmodifiableSet(new LinkedHashSet<>(other));
        }

        /**
         * The set for one dimension.
         *
         * @param dimension which hierarchy to answer for
         * @return the inclusive-of-self ancestor set on that dimension
         */
        public @NonNull Set<UUID> on(@NonNull Dimension dimension) {
            return dimension == Dimension.FINANCIAL ? financial : other;
        }

        /**
         * Whether both sets are empty, i.e. the location is unknown to the replica.
         *
         * @return true when neither dimension holds any id
         */
        public boolean isEmpty() {
            return financial.isEmpty() && other.isEmpty();
        }
    }

    /**
     * Walks upward from {@code locationId} following only the edges {@code dimension} traverses.
     *
     * <p>{@code parentsOf} answers the stored direct parent edges of a location; it must return an
     * empty collection for a location with no stored edges (including one the replica has not
     * received yet — its id is still included as an ancestor because the child's edge names it,
     * and its own ancestors are picked up when its fact arrives and triggers a recompute).
     *
     * @param locationId the start node, always included in the result
     * @param dimension the hierarchy dimension to follow
     * @param parentsOf adjacency: a location's stored direct parent edges (any type; filtered here)
     * @return the inclusive-of-self ancestor closure and whether it was depth-truncated
     */
    public static @NonNull Closure ancestors(
            @NonNull UUID locationId,
            @NonNull Dimension dimension,
            @NonNull Function<UUID, ? extends Collection<LocationUpdatedV1.ParentRef>> parentsOf) {
        return walk(locationId, node -> {
            List<LocationUpdatedV1.ParentRef> edges = new ArrayList<>(parentsOf.apply(node));
            edges.sort(DETERMINISTIC_EDGE_ORDER);
            List<UUID> next = new ArrayList<>(edges.size());
            for (LocationUpdatedV1.ParentRef edge : edges) {
                if (dimension.traverses(edge.parentType())) {
                    next.add(edge.parentId());
                }
            }
            return next;
        });
    }

    /**
     * Both inclusive-of-self ancestor sets for one location — what a replica materialises on each
     * {@code location.location.updated} fact.
     *
     * @param locationId the location to compute for
     * @param parentsOf adjacency: a location's stored direct parent edges
     * @return the FINANCIAL and OTHER closures; consult {@link #ancestors} directly when the
     *     truncation flag is needed
     */
    public static @NonNull AncestorSets ancestorSets(
            @NonNull UUID locationId,
            @NonNull Function<UUID, ? extends Collection<LocationUpdatedV1.ParentRef>> parentsOf) {
        return new AncestorSets(
                ancestors(locationId, Dimension.FINANCIAL, parentsOf).ids(),
                ancestors(locationId, Dimension.OTHER, parentsOf).ids());
    }

    /**
     * Walks downward from {@code locationId} across edges of <em>every</em> type, returning the
     * ids whose ancestor sets can be affected by a change to {@code locationId}'s own edges.
     * Exclusive of the start node.
     *
     * @param locationId the node whose subtree is wanted
     * @param childrenOf adjacency: ids of locations holding a stored edge to the given parent
     * @return the descendant closure (start node excluded) and whether it was depth-truncated
     */
    public static @NonNull Closure descendants(
            @NonNull UUID locationId, @NonNull Function<UUID, ? extends Collection<UUID>> childrenOf) {
        Closure inclusive = walk(locationId, node -> {
            List<UUID> next = new ArrayList<>(childrenOf.apply(node));
            next.sort(DETERMINISTIC_ID_ORDER);
            return next;
        });
        Set<UUID> exclusive = new LinkedHashSet<>(inclusive.ids());
        exclusive.remove(locationId);
        return new Closure(exclusive, inclusive.truncated());
    }

    /**
     * Bounded breadth-first walk. The visited set guarantees termination on a cyclic graph; the
     * depth cap bounds a pathological acyclic one. A node is never expanded twice, so a DAG with
     * overlapping branches (the OTHER union) dedupes naturally.
     */
    private static Closure walk(UUID start, Function<UUID, List<UUID>> next) {
        Set<UUID> visited = new LinkedHashSet<>();
        visited.add(start);
        Deque<UUID> frontier = new ArrayDeque<>();
        frontier.add(start);
        int depth = 0;
        while (!frontier.isEmpty()) {
            if (depth == MAX_DEPTH) {
                return new Closure(visited, true);
            }
            int levelSize = frontier.size();
            for (int i = 0; i < levelSize; i++) {
                UUID node = frontier.poll();
                for (UUID neighbour : next.apply(node)) {
                    if (visited.add(neighbour)) {
                        frontier.add(neighbour);
                    }
                }
            }
            depth++;
        }
        return new Closure(visited, false);
    }
}
