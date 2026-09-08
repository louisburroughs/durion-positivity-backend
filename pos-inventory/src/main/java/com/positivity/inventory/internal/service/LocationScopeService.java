package com.positivity.inventory.internal.service;

import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScope.Reach;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * The module's one door to the caller's location scope for service-side decisions (ADR-0061 §3,
 * #1872): a <em>gate</em> for a location the request names, and the <em>reach</em> an unfiltered
 * list is narrowed to.
 *
 * <p>Everything here reads {@link SecurityContextHelper#locationScope()} — the scope the gateway
 * filter decoded from the {@code X-Loc-*} headers — and expands it over this module's own replica
 * through {@link LocationHierarchyService}. Funnelling the reads through one bean keeps the
 * services free of security-context plumbing and lets their unit tests substitute a mock whose
 * defaults (an empty {@link Optional}, a no-op gate) are exactly the pre-rollout behaviour.
 *
 * <p>Endpoints guarded by {@code hasAnyAuthority(a, b)} pass every alternate: the caller is denied
 * only when none of the alternates they hold covers the location, and an unfiltered list is
 * narrowed only when every held alternate is scoped (one global grant means the whole list, as
 * before).
 *
 * <h2>A caller who holds none of the alternates</h2>
 *
 * <p>Both decisions are read off the scope attached to a permission the caller <em>holds</em>, so
 * when they hold none of the alternates there is no caller scope to read and neither decision
 * applies: {@link #require} passes and {@link #reachOf} returns "no narrowing". That cannot weaken
 * a gated endpoint, because every HTTP path into these methods sits behind a {@code @PreAuthorize}
 * naming the same alternates — passing it guarantees at least one is held, so an empty held list is
 * unreachable from a request. It is reachable only with no HTTP caller at all: the internal system
 * actors that share these read paths (the {@code beforeCommit} snapshot reads in
 * {@link InventoryFactPublisher}, schedulers) run with whatever authentication the triggering
 * command had and carry no location claims of their own. Denying them is not enforcement — the
 * denial happens inside the command's transaction and takes the business write down with it
 * (#1887 regression) — so this class refuses to manufacture one.
 */
@Service
@RequiredArgsConstructor
public class LocationScopeService {

    private static final String STORAGE_LOCATION_ID = "storageLocationId";
    private static final String SITE_ID = "siteId";

    private final LocationHierarchyService locationHierarchyService;

    /**
     * Gate: the caller must hold at least one of {@code permissions} that covers
     * {@code locationId}.
     *
     * <p>A {@code null} location — a record that carries none — is treated like an unknown one:
     * a caller whose held permissions are location-scoped is denied (fail closed), an unscoped or
     * global caller passes, exactly as {@link LocationScope#covers(String, String)} decides for an
     * unparseable id.
     *
     * <p>A caller holding none of the alternates has no scope to check and is not denied here (see
     * the class javadoc): behind {@code @PreAuthorize} that state cannot arise, and off the HTTP
     * path there is no caller whose reach could be exceeded.
     *
     * @param locationId the location the request acts on, or {@code null} when the resource has
     *     none
     * @param permissions the permission alternates the endpoint's {@code @PreAuthorize} accepts;
     *     at least one
     * @throws LocationScopeDeniedException when the caller holds an alternate and no held alternate
     *     covers the location
     */
    public void require(@Nullable UUID locationId, @NonNull String... permissions) {
        requireAtLeastOne(permissions);
        LocationScope scope = SecurityContextHelper.locationScope();
        String location = locationId == null ? "" : locationId.toString();
        List<String> held = held(permissions);
        if (held.isEmpty()) {
            return;
        }
        for (String permission : held) {
            if (scope.covers(permission, location)) {
                return;
            }
        }
        throw new LocationScopeDeniedException(held.get(0), location);
    }

    /**
     * Narrowing for a list whose location filter is optional.
     *
     * <ul>
     *   <li>{@code locationId} named: it is gated with {@link #require} and the answer is
     *       {@link Optional#empty()} — the caller's own filter is the restriction.</li>
     *   <li>No filter, caller unscoped or globally granted on some held alternate: empty — list
     *       everything, unchanged.</li>
     *   <li>No filter, every held alternate scoped: the union of the inclusive descendant sets of
     *       each assigned node on each dimension the alternates are scoped on. An empty set means
     *       "show nothing", never "show everything" — callers must not collapse the two.</li>
     * </ul>
     *
     * @param locationId the optional filter the caller supplied
     * @param permissions the permission alternates the endpoint's {@code @PreAuthorize} accepts
     * @return the site-level set to restrict the query to, or empty when no restriction applies
     * @throws LocationScopeDeniedException when a named filter is outside the caller's reach
     */
    public @NonNull Optional<Set<UUID>> narrowTo(@Nullable UUID locationId, @NonNull String... permissions) {
        if (locationId != null) {
            require(locationId, permissions);
            return Optional.empty();
        }
        return reachOf(permissions);
    }

    /**
     * The caller's reach for an unfiltered list: see {@link #narrowTo} for the three outcomes.
     *
     * <p>A caller holding none of the alternates is not narrowed, the same way {@link #require}
     * does not deny them (see the class javadoc): behind {@code @PreAuthorize} that state cannot
     * arise, and off the HTTP path there is no caller whose reach the list should be cut to.
     *
     * @param permissions the permission alternates the endpoint's {@code @PreAuthorize} accepts
     * @return the site-level set to restrict the query to, or empty when no restriction applies
     */
    public @NonNull Optional<Set<UUID>> reachOf(@NonNull String... permissions) {
        requireAtLeastOne(permissions);
        LocationScope scope = SecurityContextHelper.locationScope();
        List<String> held = held(permissions);
        if (held.isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> reachable = new LinkedHashSet<>();
        for (String permission : held) {
            Optional<Reach> reach = scope.reach(permission);
            if (reach.isEmpty()) {
                return Optional.empty();
            }
            for (UUID node : reach.get().nodes()) {
                for (Dimension dimension : reach.get().dimensions()) {
                    reachable.addAll(locationHierarchyService.descendantsOf(node, dimension));
                }
            }
        }
        return Optional.of(reachable);
    }

    /**
     * The JPA predicate a narrowed query restricts itself with: the row's location is one of the
     * reachable sites, or a storage location replicated under one of them. Bins are admitted by
     * their {@code site_id} through a subquery rather than expanded into the {@code IN} list, so the
     * bound parameters stay at site granularity however many bins a site has.
     *
     * <p>Never call with an empty set — an empty reach is an empty result decided before the query
     * (see {@link #narrowTo}), not an {@code IN ()} handed to the database.
     *
     * @param attribute the entity attribute holding the location id
     * @param locationIds the non-empty site-level reach
     * @param <T> the entity type
     * @return the predicate
     */
    public static <T> @NonNull Specification<T> withinLocations(
            @NonNull String attribute, @NonNull Set<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            throw new IllegalArgumentException("withinLocations requires a non-empty reach");
        }
        return (root, query, cb) -> {
            if (query == null) {
                return root.get(attribute).in(locationIds);
            }
            Subquery<UUID> bins = query.subquery(UUID.class);
            Root<ExtStorageLocationReplica> bin = bins.from(ExtStorageLocationReplica.class);
            bins.select(bin.get(STORAGE_LOCATION_ID)).where(bin.get(SITE_ID).in(locationIds));
            return cb.or(
                    root.get(attribute).in(locationIds), root.get(attribute).in(bins));
        };
    }

    private static List<String> held(String... permissions) {
        List<String> held = new ArrayList<>(permissions.length);
        for (String permission : permissions) {
            if (SecurityContextHelper.hasAuthority(permission)) {
                held.add(permission);
            }
        }
        return held;
    }

    private static void requireAtLeastOne(String... permissions) {
        if (permissions.length == 0) {
            throw new IllegalArgumentException("At least one permission is required");
        }
    }
}
