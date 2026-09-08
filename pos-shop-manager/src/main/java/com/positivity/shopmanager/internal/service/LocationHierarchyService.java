package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.location.LocationAncestry;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Closure;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.shopmanager.internal.entity.ExtLocationParentReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialises and serves the location-scope ancestor sets on the {@code ext_location} replica
 * (ADR-0061 §1–§2, issue #1872); a copy of pos-people's service of the same name.
 *
 * <p>Two responsibilities, both over this module's own replica rows and never over a call to
 * pos-location:
 *
 * <ul>
 *   <li>{@link #ancestorsOf} answers the two inclusive-of-self ancestor sets a scope check
 *       intersects with the caller's assigned nodes. An unknown location answers
 *       {@link AncestorSets#EMPTY}, which the check treats as deny — fail closed happens there,
 *       not at ingestion. This is the module's {@link LocationAncestorResolver} bean: the service
 *       implements the SPI directly rather than being wrapped by a bean in {@code internal.config},
 *       because {@code internal.service} already depends on {@code internal.config} and the reverse
 *       edge would be a package cycle.</li>
 *   <li>{@link #descendantsOf} is the downward mirror for the <em>narrowing</em> case: an
 *       optional-location list endpoint called without a location restricts its result to the
 *       caller's reach, which is the assigned nodes plus every replicated descendant on the
 *       dimension the permission is scoped on. No endpoint in this module narrows today — every
 *       location parameter here names the resource being acted on — but the walk is the same code
 *       the other modules use and is kept so a future list endpoint does not re-derive it.</li>
 *   <li>{@link #recomputeAncestors} rebuilds the sets for a location <em>and every replicated
 *       descendant</em> after its edges change. Re-parenting a mid-level node invalidates the sets
 *       of everything beneath it, and a parent whose fact arrives after its children's must push
 *       its own ancestry down to them; both are the same subtree walk.</li>
 * </ul>
 *
 * <p>The closure itself is the shared, pure {@link LocationAncestry}; this class only supplies the
 * adjacency from {@link ExtLocationParentReplica} rows and writes the result back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationHierarchyService implements LocationAncestorResolver {

    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    /**
     * The materialised ancestor sets for a location, inclusive of the location itself.
     *
     * @param locationId the location a scope check is evaluating
     * @return both dimensions' sets; {@link AncestorSets#EMPTY} when the replica does not hold
     *     the location
     */
    @Override
    @Transactional(readOnly = true)
    public @NonNull AncestorSets ancestorsOf(@NonNull UUID locationId) {
        return extLocationReplicaRepository
                .findById(locationId)
                .map(row -> new AncestorSets(row.getFinancialAncestorIds(), row.getOtherAncestorIds()))
                .orElse(AncestorSets.EMPTY);
    }

    /**
     * The replicated locations beneath {@code locationId} on one dimension, inclusive of the
     * location itself — exactly the set of {@code L} for which {@code locationId ∈ ancestors(L,
     * dimension)}.
     *
     * <p>Only edges whose {@code parent_type} the dimension traverses are followed: a
     * {@code FINANCIAL}-only edge never widens an {@code OTHER} reach, and vice versa. The start
     * node is included only when the replica holds it, mirroring {@link #ancestorsOf}: a node the
     * replica does not know reaches nothing.
     *
     * <p>Bounded like the ancestor walk: a visited set terminates a cycle and
     * {@link LocationAncestry#MAX_DEPTH} caps a pathological graph, in which case the walk logs and
     * returns what it reached — erring toward showing less, never more.
     *
     * @param locationId the node to expand
     * @param dimension the hierarchy dimension whose edges are followed downward
     * @return the inclusive descendant set, in deterministic breadth-first order; empty when the
     *     replica does not hold {@code locationId}
     */
    @Transactional(readOnly = true)
    public @NonNull Set<UUID> descendantsOf(@NonNull UUID locationId, @NonNull Dimension dimension) {
        if (!extLocationReplicaRepository.existsById(locationId)) {
            return Set.of();
        }
        Closure subtree = LocationAncestry.descendants(locationId, parent -> childrenOf(parent, dimension));
        if (subtree.truncated()) {
            log.warn(
                    "Descendant walk from locationId={} on {} hit MAX_DEPTH={}; reach beyond the cap is not included",
                    locationId,
                    dimension,
                    LocationAncestry.MAX_DEPTH);
        }
        Set<UUID> reach = new LinkedHashSet<>();
        reach.add(locationId);
        reach.addAll(subtree.ids());
        return reach;
    }

    /**
     * Recomputes the ancestor sets of {@code locationId} and of every replicated descendant
     * reachable through the stored edges (any type), writing each back to its replica row.
     *
     * <p>A descendant that has an edge in the replica but no {@code ext_location} row yet is
     * skipped — its sets are computed when its own fact lands. A parent named by an edge but not
     * yet replicated is still counted as an ancestor (the edge names it), and its own ancestry is
     * folded in when its fact arrives and triggers this method for its subtree. Ingestion never
     * fails on an unknown parent; only the scope check fails closed.
     *
     * <p>Intended to run inside the consumer's apply transaction so the row update, edge
     * replacement and recompute commit together.
     *
     * @param locationId the location whose edges (or existence) just changed
     */
    @Transactional
    public void recomputeAncestors(@NonNull UUID locationId) {
        Closure subtree = LocationAncestry.descendants(locationId, this::childrenOf);
        if (subtree.truncated()) {
            log.warn(
                    "Descendant walk from locationId={} hit MAX_DEPTH={}; ancestor sets beyond the cap were not refreshed",
                    locationId,
                    LocationAncestry.MAX_DEPTH);
        }
        Set<UUID> targets = new LinkedHashSet<>();
        targets.add(locationId);
        targets.addAll(subtree.ids());
        for (UUID target : targets) {
            extLocationReplicaRepository.findById(target).ifPresent(this::materialise);
        }
    }

    private void materialise(ExtLocationReplica row) {
        Closure financial = LocationAncestry.ancestors(row.getLocationId(), Dimension.FINANCIAL, this::parentsOf);
        Closure other = LocationAncestry.ancestors(row.getLocationId(), Dimension.OTHER, this::parentsOf);
        if (financial.truncated() || other.truncated()) {
            log.warn(
                    "Ancestor walk for locationId={} hit MAX_DEPTH={}; the stored sets are incomplete and err toward deny",
                    row.getLocationId(),
                    LocationAncestry.MAX_DEPTH);
        }
        row.setFinancialAncestorIds(new LinkedHashSet<>(financial.ids()));
        row.setOtherAncestorIds(new LinkedHashSet<>(other.ids()));
        extLocationReplicaRepository.save(row);
    }

    private List<LocationUpdatedV1.ParentRef> parentsOf(UUID childId) {
        return extLocationParentReplicaRepository.findByChildId(childId).stream()
                .map(edge -> new LocationUpdatedV1.ParentRef(edge.getParentId(), edge.getParentType()))
                .toList();
    }

    private List<UUID> childrenOf(UUID parentId) {
        return extLocationParentReplicaRepository.findByParentId(parentId).stream()
                .map(ExtLocationParentReplica::getChildId)
                .toList();
    }

    private List<UUID> childrenOf(UUID parentId, Dimension dimension) {
        return extLocationParentReplicaRepository.findByParentId(parentId).stream()
                .filter(edge -> dimension.traverses(edge.getParentType()))
                .map(ExtLocationParentReplica::getChildId)
                .toList();
    }
}
