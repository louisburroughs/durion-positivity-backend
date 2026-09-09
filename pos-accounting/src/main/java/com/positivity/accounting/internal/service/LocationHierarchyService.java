package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtLocationParentReplica;
import com.positivity.accounting.internal.entity.ExtLocationReplica;
import com.positivity.accounting.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.accounting.internal.repository.ExtLocationReplicaRepository;
import com.positivity.domainevents.location.LocationAncestry;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Closure;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.security.common.LocationAncestorResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialises and serves the location-scope ancestor sets on this module's {@code ext_location}
 * replica (ADR-0061 §1–§2, issues #1878 and #1885).
 *
 * <p>Everything here reads this module's own replica rows; there is no per-request call to
 * pos-location. {@link #ancestorsOf} answers the two inclusive-of-self ancestor sets a scope check
 * intersects with the caller's assigned nodes, answering {@link AncestorSets#EMPTY} for a location
 * the replica does not hold — the check treats that as deny, so failing closed happens there and
 * not at ingestion. {@link #recomputeAncestors} rebuilds the sets for a location <em>and every
 * replicated descendant</em> after its edges change: re-parenting a mid-level node invalidates
 * everything beneath it, and a parent whose fact arrives after its children's must push its own
 * ancestry down to them; both are the same subtree walk.
 *
 * <p>The closure itself is the shared, pure {@link LocationAncestry}; this class only supplies the
 * adjacency from {@link ExtLocationParentReplica} rows and writes the result back.
 *
 * <p>It is also this module's {@link LocationAncestorResolver} (ADR-0061 §3): the one bean
 * {@code pos-security-common}'s {@code LocationScope.covers} needs to evaluate a location-scoped
 * permission here. Without a resolver every scoped permission is denied by design, so this
 * implementation is the switch that turns location-scope enforcement on for this module. It lives
 * on the service rather than in a {@code config} bean because {@code internal.config} already
 * depends on the service slice and the reverse edge would close a package cycle under the module's
 * ArchUnit rule — the same shape pos-inventory, pos-people and pos-workorder landed on.
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
     * @return both dimensions' sets; {@link AncestorSets#EMPTY} when the replica does not hold the
     *     location
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
     * Resolves the accounting {@code locationId} GL dimension value — the owner's short location
     * code, e.g. {@code LOC-107} — to the location id the ancestor sets are keyed on (#1885).
     *
     * <p>An unknown code answers empty, which the caller turns into a denial for a location-scoped
     * caller and leaves alone for a global one: a code the replica cannot place must never grant.
     *
     * @param locationCode the GL dimension value naming a location
     * @return the replicated location id, or empty when the replica does not hold the code
     */
    @Transactional(readOnly = true)
    public @NonNull Optional<UUID> locationIdForCode(@NonNull String locationCode) {
        return extLocationReplicaRepository.findByCode(locationCode).map(ExtLocationReplica::getLocationId);
    }

    /**
     * Recomputes the ancestor sets of {@code locationId} and of every replicated descendant
     * reachable through the stored edges (any type), writing each back to its replica row.
     *
     * <p>A descendant that has an edge in the replica but no {@code ext_location} row yet is
     * skipped — its sets are computed when its own fact lands. A parent named by an edge but not
     * yet replicated is still counted as an ancestor (the edge names it), and its own ancestry is
     * folded in when its fact arrives and triggers this method for its subtree.
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
}
