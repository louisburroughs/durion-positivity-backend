package com.positivity.security.common;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * The one thing {@link LocationScope} needs from the module it runs in: the materialised,
 * inclusive-of-self ancestor sets of a location on each hierarchy dimension (ADR-0061 §2, #1870).
 *
 * <p>Each adopting module provides exactly one bean, delegating to its own
 * {@code LocationHierarchyService} over its own {@code ext_location} replica (#1878). The check
 * is a set intersection against that replica; nothing here may call pos-location per request.
 *
 * <p>Two contract points the check relies on:
 *
 * <ul>
 *   <li>A location the replica does not hold answers {@link AncestorSets#EMPTY}. The check then
 *       denies — a stale or partial replica must never grant.</li>
 *   <li>Both sets include the location itself, so a directly assigned node matches by the same
 *       intersection rule as an ancestor with no special case.</li>
 * </ul>
 *
 * <p>When no bean is present in a module, {@link LocationScope} denies every scoped permission
 * and logs once; it never treats the missing resolver as "unrestricted".
 */
@FunctionalInterface
public interface LocationAncestorResolver {

    /**
     * The inclusive-of-self ancestor sets of a location on both dimensions.
     *
     * @param locationId the location a scope check is evaluating
     * @return both sets; {@link AncestorSets#EMPTY} for a location unknown to the replica
     */
    @NonNull
    AncestorSets ancestorsOf(@NonNull UUID locationId);
}
