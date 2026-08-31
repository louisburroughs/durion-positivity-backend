package com.positivity.mcp.internal.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * An immutable view of every role's persona, replacing the hardcoded {@code MCP_ROLE_PRIORITY} list
 * and the per-role {@code ROLE_*_PROMPT_NAME} constants (#1613, D2 and D6).
 *
 * <p>Swapped atomically by the sync so a request never observes a half-updated set. Every role-keyed
 * artifact — resolution priority, persona text, the agent warm-up set — is derived from this one
 * object, which is what makes a role created after a release visible to the assistant without a Java
 * edit.
 */
public final class RolePersonaSnapshot {

    /**
     * Rank ascending with nulls last, then name, matching the upstream projection's ordering (D2).
     * Sorted here as well as upstream: ordering is what decides which persona a multi-role caller
     * gets, and it should not depend on a transport preserving list order.
     */
    private static final Comparator<RolePersona> BY_RANK_THEN_NAME = Comparator.comparing(
                    RolePersona::mcpPersonaRank, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(RolePersona::name);

    // Declared after the comparator on purpose: the constructor sorts with it, so building EMPTY
    // first would read a null comparator and fail class initialization.
    private static final RolePersonaSnapshot EMPTY = new RolePersonaSnapshot(Instant.EPOCH, List.of());

    private final Instant generatedAt;
    private final List<RolePersona> personas;
    private final List<String> rankedAuthorities;
    private final Map<String, String> textByAuthority;
    private final Set<String> ineligibleAuthorities;

    private RolePersonaSnapshot(Instant generatedAt, List<RolePersona> personas) {
        this.generatedAt = generatedAt;
        this.personas = List.copyOf(personas);

        List<String> ranked = new java.util.ArrayList<>();
        Map<String, String> text = new LinkedHashMap<>();
        Set<String> ineligible = new LinkedHashSet<>();

        personas.stream().sorted(BY_RANK_THEN_NAME).forEach(persona -> {
            String authority = RoleAuthorities.toAuthority(persona.name());
            if (persona.mcpPersonaEligible()) {
                ranked.add(authority);
                text.put(authority, RolePersonaRenderer.render(persona));
            } else {
                ineligible.add(authority);
            }
        });

        this.rankedAuthorities = List.copyOf(ranked);
        this.textByAuthority = Map.copyOf(text);
        this.ineligibleAuthorities = Set.copyOf(ineligible);
    }

    public static @NonNull RolePersonaSnapshot of(@NonNull Instant generatedAt, @NonNull List<RolePersona> personas) {
        return new RolePersonaSnapshot(generatedAt, personas);
    }

    /**
     * The state before the first successful sync. Resolution falls back for every caller, which is
     * degraded but safe: the ROLE layer is persona-only and grants nothing.
     */
    public static @NonNull RolePersonaSnapshot empty() {
        return EMPTY;
    }

    /**
     * The caller's highest-ranked eligible role, or {@code fallback} when they hold none.
     *
     * <p>An unranked role still wins over the fallback — it sorts after every ranked role but is
     * present — so a role created today without a rank gets its own persona rather than the generic
     * one (D2).
     */
    public @NonNull String resolvePrimaryRole(@NonNull Set<String> callerAuthorities, @NonNull String fallback) {
        return rankedAuthorities.stream()
                .filter(callerAuthorities::contains)
                .findFirst()
                .orElse(fallback);
    }

    /** The rendered ROLE layer for an authority, empty when the role is unknown or ineligible. */
    public @NonNull Optional<String> personaText(@NonNull String authority) {
        return Optional.ofNullable(textByAuthority.get(authority));
    }

    /**
     * Roles to pre-build agents for (D6), capped so an operator creating hundreds of roles cannot
     * blow up agent prebuild. The cap keeps the highest-ranked roles, which are the ones a caller is
     * most likely to resolve to.
     */
    public @NonNull List<String> preloadableRoleIdentifiers(int max) {
        return max >= rankedAuthorities.size() ? rankedAuthorities : List.copyOf(rankedAuthorities.subList(0, max));
    }

    /**
     * Whether this role is excluded from persona resolution by design, as opposed to simply unknown.
     * The distinction is what lets the fallback metric separate a deliberate exclusion from a sync
     * gap that should alert.
     */
    public boolean isIneligible(@NonNull String authority) {
        return ineligibleAuthorities.contains(authority);
    }

    /** Whether the snapshot has heard of this role at all, eligible or not. */
    public boolean isKnown(@NonNull String authority) {
        return textByAuthority.containsKey(authority) || ineligibleAuthorities.contains(authority);
    }

    public @NonNull List<String> rankedAuthorities() {
        return rankedAuthorities;
    }

    /**
     * The personas this snapshot was built from, ineligible ones included. Exposed so a single-role
     * on-miss fetch can be merged in by rebuilding — a new role can land anywhere in the rank order,
     * so it cannot simply be appended.
     */
    public @NonNull List<RolePersona> personas() {
        return personas;
    }

    public @NonNull Instant generatedAt() {
        return generatedAt;
    }

    /** Eligible roles only — the ones that can actually assemble a ROLE layer. */
    public int roleCount() {
        return rankedAuthorities.size();
    }

    public boolean isEmpty() {
        return rankedAuthorities.isEmpty() && ineligibleAuthorities.isEmpty();
    }
}
