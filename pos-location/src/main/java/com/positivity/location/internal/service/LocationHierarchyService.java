package com.positivity.location.internal.service;

import com.positivity.domainevents.location.LocationAncestry;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Closure;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.repository.LocationParentRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.security.common.LocationAncestorResolver;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the location-scope ancestor sets straight off this module's own {@link LocationParent}
 * edges (ADR-0061 §1–§2, issue #1872).
 *
 * <p>pos-location is the system of record for the location tree, so unlike every consuming module
 * it keeps no {@code ext_location} replica and materialises nothing: {@link #ancestorsOf} walks
 * the live edges on each call. The walk itself is the shared, pure {@link LocationAncestry}
 * closure; this class only supplies the adjacency (a child's stored parent edges, any type —
 * the closure filters by {@link Dimension}) and the existence check.
 *
 * <p>This is the module's {@link LocationAncestorResolver} bean. The service implements the SPI
 * directly rather than being wrapped by a bean in {@code internal.config}, because
 * {@code internal.service} already depends on {@code internal.config} and the reverse edge would
 * be a package cycle (ArchUnit {@code packages_should_be_free_of_cycles}).
 *
 * <p>Two contract points the scope check relies on (see {@link LocationAncestorResolver}):
 *
 * <ul>
 *   <li>An id with no {@code location} row answers {@link AncestorSets#EMPTY}; the check then
 *       denies. Fail closed happens there, not here.</li>
 *   <li>Both sets include the location itself, so a directly assigned node matches by the same
 *       intersection rule as an ancestor.</li>
 * </ul>
 *
 * <p>Bounded like every {@link LocationAncestry} walk: a visited set terminates a pre-existing
 * cycle and {@link LocationAncestry#MAX_DEPTH} caps a pathological graph, in which case the walk
 * logs and returns what it reached — the missing far ancestors err toward deny.
 *
 * <p>Deliberately <em>not</em> shared with {@code LocationServiceImpl#wouldCreateCycle}: the
 * cycle guard walks one {@code ParentType} at a time (a chain, ADR-0016) and must reject a
 * pre-existing cycle it meets, whereas a scope dimension is the union of up to seven parent types
 * (a DAG) and treats a cycle as merely terminated. The two answer different questions over the
 * same edges.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationHierarchyService implements LocationAncestorResolver {

    private final LocationRepository locationRepository;
    private final LocationParentRepository locationParentRepository;

    /**
     * The inclusive-of-self ancestor sets of a location on both dimensions, computed from the
     * stored {@link LocationParent} edges.
     *
     * @param locationId the location a scope check is evaluating
     * @return both dimensions' sets; {@link AncestorSets#EMPTY} when no such location exists
     */
    @Override
    @Transactional(readOnly = true)
    public @NonNull AncestorSets ancestorsOf(@NonNull UUID locationId) {
        if (!locationRepository.existsById(locationId)) {
            return AncestorSets.EMPTY;
        }
        Closure financial = LocationAncestry.ancestors(locationId, Dimension.FINANCIAL, this::parentsOf);
        Closure other = LocationAncestry.ancestors(locationId, Dimension.OTHER, this::parentsOf);
        if (financial.truncated() || other.truncated()) {
            log.warn(
                    "Ancestor walk for locationId={} hit MAX_DEPTH={}; the sets are incomplete and err toward deny",
                    locationId,
                    LocationAncestry.MAX_DEPTH);
        }
        return new AncestorSets(financial.ids(), other.ids());
    }

    private List<LocationUpdatedV1.ParentRef> parentsOf(UUID childId) {
        return locationParentRepository.findByChild_Id(childId).stream()
                .map(edge -> new LocationUpdatedV1.ParentRef(
                        edge.getParent().getId(), edge.getParentType().name()))
                .toList();
    }
}
