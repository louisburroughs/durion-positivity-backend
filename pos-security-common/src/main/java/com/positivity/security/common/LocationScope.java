package com.positivity.security.common;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The caller's location reach for one request, and the one shared answer to "may this caller
 * exercise permission {@code P} at location {@code L}?" (ADR-0061 §2–§3, #1870).
 *
 * <p>Built by {@link GatewayAuthoritiesFilter} from the three {@code X-Loc-*} headers the gateway
 * derives from the access token, and read by controllers through
 * {@link SecurityContextHelper#locationScope()}. Existing {@code @PreAuthorize} annotations keep
 * answering "may this caller do X"; this class answers "…here", and is applied only where a
 * request names a location.
 *
 * <h2>Decision table</h2>
 *
 * <table>
 *   <caption>{@link #covers(String, String)}</caption>
 *   <tr><th>bitset state</th><th>nodes</th><th>result</th></tr>
 *   <tr><td>claims absent (pre-rollout / unscoped legacy token)</td><td>—</td>
 *       <td>allow — today's behaviour, so adoption stays per-module</td></tr>
 *   <tr><td>{@code P} in neither set</td><td>any</td><td>allow — the grant is global</td></tr>
 *   <tr><td>{@code P} in a set</td><td>absent</td><td>deny — fail closed</td></tr>
 *   <tr><td>{@code P} in {@code financialScoped}</td><td>intersects {@code ancestorsOf(L).financial}</td>
 *       <td>allow</td></tr>
 *   <tr><td>{@code P} in {@code otherScoped}</td><td>intersects {@code ancestorsOf(L).other}</td>
 *       <td>allow</td></tr>
 *   <tr><td>{@code P} in one or both</td><td>disjoint from the corresponding set(s)</td><td>deny</td></tr>
 *   <tr><td>any scoped {@code P}</td><td>{@code L} unknown to the resolver, or not a UUID</td>
 *       <td>deny — a stale replica must not grant</td></tr>
 * </table>
 *
 * <p>A permission in both sets passes if <em>either</em> dimension covers. Ancestor sets are
 * inclusive of self, so a directly assigned node matches by the same rule as an ancestor. The
 * resolver is the module's own replica; there is no per-request call to pos-location. When the
 * module provides no {@link LocationAncestorResolver} bean, every scoped permission is denied and
 * a warning is logged once — absence of wiring must never read as unrestricted reach.
 *
 * <h2>Narrowing rather than gating</h2>
 *
 * <p>{@link #covers} and {@link #require} answer for one named location. A list endpoint whose
 * location parameter is <em>optional</em> has a second shape: when the caller names no location,
 * the result set is narrowed to the caller's reach instead of denied. {@link #reach(String)}
 * exposes what such an endpoint needs — the dimension(s) the permission is scoped on and the
 * assigned nodes — and leaves the descendant expansion to the module's own replica, which is
 * where the hierarchy lives. It is read-only: the decision table above is unchanged.
 *
 * <p>Immutable and safe to share; one instance lives in the authentication details for the
 * duration of a request.
 */
public final class LocationScope {

    private static final Logger log = LoggerFactory.getLogger(LocationScope.class);

    /** One warning per JVM for a module that checks scope without providing a resolver bean. */
    private static final AtomicBoolean MISSING_RESOLVER_WARNED = new AtomicBoolean();

    private static final LocationScope UNSCOPED = new LocationScope(Set.of(), Set.of(), null, false, null);

    private final Set<String> financialScoped;
    private final Set<String> otherScoped;
    private final @Nullable Set<UUID> nodes;
    private final boolean claimsPresent;
    private final @Nullable LocationAncestorResolver resolver;

    private LocationScope(
            Set<String> financialScoped,
            Set<String> otherScoped,
            @Nullable Set<UUID> nodes,
            boolean claimsPresent,
            @Nullable LocationAncestorResolver resolver) {
        this.financialScoped = Collections.unmodifiableSet(new LinkedHashSet<>(financialScoped));
        this.otherScoped = Collections.unmodifiableSet(new LinkedHashSet<>(otherScoped));
        this.nodes = nodes == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
        this.claimsPresent = claimsPresent;
        this.resolver = resolver;
    }

    /**
     * The scope of a caller whose token carries no scope claims at all. Permissive by design: it
     * is the pre-rollout shape, and treating it as anything else would make adoption a flag day.
     *
     * @return a scope for which {@link #covers} is always {@code true}
     */
    public static @NonNull LocationScope unscoped() {
        return UNSCOPED;
    }

    /**
     * Builds a scope from decoded header material.
     *
     * @param financialScoped plain permission names scoped on the {@code FINANCIAL} dimension
     * @param otherScoped plain permission names scoped on the {@code OTHER} dimension
     * @param nodes the caller's assigned nodes; empty when the {@code X-Loc-Scope} header was
     *     absent or malformed — which denies every scoped permission
     * @param claimsPresent whether the token carried the scope claims at all; {@code false} makes
     *     the scope permissive regardless of the other arguments
     * @param resolver the module's ancestor-set resolver, or {@code null} when the module provides
     *     none — every scoped permission is then denied
     * @return an immutable scope
     */
    public static @NonNull LocationScope of(
            @NonNull Set<String> financialScoped,
            @NonNull Set<String> otherScoped,
            @NonNull Optional<Set<UUID>> nodes,
            boolean claimsPresent,
            @Nullable LocationAncestorResolver resolver) {
        return new LocationScope(financialScoped, otherScoped, nodes.orElse(null), claimsPresent, resolver);
    }

    /**
     * @return whether the token carried the scope claims; {@code false} is the pre-rollout shape
     */
    public boolean claimsPresent() {
        return claimsPresent;
    }

    /**
     * @return plain permission names scoped on the {@code FINANCIAL} dimension (immutable)
     */
    public @NonNull Set<String> financialScoped() {
        return financialScoped;
    }

    /**
     * @return plain permission names scoped on the {@code OTHER} dimension (immutable)
     */
    public @NonNull Set<String> otherScoped() {
        return otherScoped;
    }

    /**
     * @return the caller's assigned nodes, or empty when the claim was absent (fail closed)
     */
    public @NonNull Optional<Set<UUID>> nodes() {
        return Optional.ofNullable(nodes);
    }

    /**
     * What a location-scoped permission reaches, for an endpoint that narrows an unfiltered list
     * to the caller's locations rather than gating one named location (ADR-0061 §2–§3).
     *
     * <p>The reach is {@code nodes} plus every replicated descendant of each node on each of
     * {@code dimensions} — equivalently, every replicated location whose inclusive ancestor set on
     * one of those dimensions intersects {@code nodes}. The expansion is the module's, over its own
     * replica; this record only carries the inputs.
     *
     * @param dimensions the dimension(s) the permission is scoped on; never empty, and both when
     *     the permission was granted by a {@code FINANCIAL}-scoped role and an {@code OTHER}-scoped
     *     role, in which case either reach counts
     * @param nodes the caller's assigned nodes; <em>empty</em> when the {@code loc_scope} claim was
     *     absent, so a caller expanding it reaches nothing and fails closed rather than open
     */
    public record Reach(
            @NonNull Set<Dimension> dimensions, @NonNull Set<UUID> nodes) {

        public Reach {
            if (dimensions.isEmpty()) {
                throw new IllegalArgumentException("A Reach must be scoped on at least one dimension");
            }
            dimensions = Collections.unmodifiableSet(EnumSet.copyOf(dimensions));
            nodes = Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
        }
    }

    /**
     * The caller's reach for a permission, when that permission is location-scoped for them.
     *
     * <table>
     *   <caption>{@link #reach(String)}</caption>
     *   <tr><th>state</th><th>result</th></tr>
     *   <tr><td>claims absent (pre-rollout token)</td><td>empty — the grant is unrestricted</td></tr>
     *   <tr><td>{@code P} in neither bitset</td><td>empty — the grant is global</td></tr>
     *   <tr><td>{@code P} in one bitset, nodes present</td><td>that dimension and the nodes</td></tr>
     *   <tr><td>{@code P} in both bitsets, nodes present</td><td>both dimensions and the nodes</td></tr>
     *   <tr><td>{@code P} in a bitset, nodes absent</td><td>present, with <em>no</em> nodes — fail closed</td></tr>
     * </table>
     *
     * <p>An empty result means "do not narrow"; a present result with no nodes means "narrow to
     * nothing". Callers must not collapse the two.
     *
     * @param permission the permission being exercised, with or without the {@code PERM_} prefix
     * @return the reach to narrow to, or empty when the permission needs no narrowing
     */
    public @NonNull Optional<Reach> reach(@NonNull String permission) {
        if (!claimsPresent) {
            return Optional.empty();
        }
        String plain = plainPermission(permission);
        boolean financial = financialScoped.contains(plain);
        boolean other = otherScoped.contains(plain);
        if (!financial && !other) {
            return Optional.empty();
        }
        Set<Dimension> dimensions = EnumSet.noneOf(Dimension.class);
        if (financial) {
            dimensions.add(Dimension.FINANCIAL);
        }
        if (other) {
            dimensions.add(Dimension.OTHER);
        }
        return Optional.of(new Reach(dimensions, nodes == null ? Set.of() : nodes));
    }

    /**
     * Whether a permission is location-scoped for this caller on either dimension. A permission
     * that is not scoped is global and needs no location check.
     *
     * @param permission a permission name, with or without the {@code PERM_} prefix
     * @return true when the permission appears in either scope bitset
     */
    public boolean isScoped(@NonNull String permission) {
        String plain = plainPermission(permission);
        return financialScoped.contains(plain) || otherScoped.contains(plain);
    }

    /**
     * Applies the decision table to permission {@code P} at location {@code L}.
     *
     * @param permission the permission being exercised, with or without the {@code PERM_} prefix
     * @param locationId the requested location id; a value that is not a UUID denies any scoped
     *     permission and is irrelevant to a global one
     * @return true when the caller may exercise the permission at that location
     */
    public boolean covers(@NonNull String permission, @NonNull String locationId) {
        if (!claimsPresent) {
            return true;
        }
        String plain = plainPermission(permission);
        boolean financial = financialScoped.contains(plain);
        boolean other = otherScoped.contains(plain);
        if (!financial && !other) {
            return true;
        }
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        UUID location = parseUuid(locationId);
        if (location == null) {
            return false;
        }
        if (resolver == null) {
            warnMissingResolverOnce();
            return false;
        }
        AncestorSets ancestors = resolver.ancestorsOf(location);
        return (financial && intersects(nodes, ancestors.financial()))
                || (other && intersects(nodes, ancestors.other()));
    }

    /**
     * {@link #covers(String, String)} for a location already parsed as a UUID.
     *
     * @param permission the permission being exercised
     * @param locationId the requested location
     * @return true when the caller may exercise the permission at that location
     */
    public boolean covers(@NonNull String permission, @NonNull UUID locationId) {
        return covers(permission, locationId.toString());
    }

    /**
     * {@link #covers(String, String)} as a guard: throws when the caller is not covered.
     *
     * @param permission the permission being exercised
     * @param locationId the requested location id
     * @throws LocationScopeDeniedException when {@link #covers} is false
     */
    public void require(@NonNull String permission, @NonNull String locationId) {
        if (!covers(permission, locationId)) {
            throw new LocationScopeDeniedException(plainPermission(permission), locationId);
        }
    }

    /**
     * {@link #require(String, String)} for a location already parsed as a UUID.
     *
     * @param permission the permission being exercised
     * @param locationId the requested location
     * @throws LocationScopeDeniedException when {@link #covers} is false
     */
    public void require(@NonNull String permission, @NonNull UUID locationId) {
        require(permission, locationId.toString());
    }

    private static String plainPermission(String permission) {
        return permission.startsWith(GatewaySecurityConstants.PERMISSION_PREFIX)
                ? permission.substring(GatewaySecurityConstants.PERMISSION_PREFIX.length())
                : permission;
    }

    private static @Nullable UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean intersects(Set<UUID> assigned, Set<UUID> ancestors) {
        for (UUID node : assigned) {
            if (ancestors.contains(node)) {
                return true;
            }
        }
        return false;
    }

    private static void warnMissingResolverOnce() {
        if (MISSING_RESOLVER_WARNED.compareAndSet(false, true)) {
            log.warn("Location-scoped permission checked but no LocationAncestorResolver bean is present;"
                    + " every scoped permission is denied until the module provides one (ADR-0061 §3)");
        }
    }

    /** Test hook: lets a test observe the once-only warning regardless of execution order. */
    static void resetMissingResolverWarningForTests() {
        MISSING_RESOLVER_WARNED.set(false);
    }

    @Override
    public String toString() {
        return "LocationScope{claimsPresent=" + claimsPresent
                + ", financialScoped=" + financialScoped.size()
                + ", otherScoped=" + otherScoped.size()
                + ", nodes=" + (nodes == null ? "absent" : String.valueOf(nodes.size()))
                + ", resolver=" + (resolver == null ? "absent" : "present")
                + '}';
    }
}
