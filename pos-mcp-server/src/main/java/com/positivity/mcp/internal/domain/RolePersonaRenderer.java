package com.positivity.mcp.internal.domain;

import java.util.Locale;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Renders a role's structured persona slots into the ROLE prompt layer (#1613, D1 and D5).
 *
 * <p>The template is fixed and lives here rather than in {@code pos-security-service}: prompt
 * wording is an MCP concern with its own review and eval loop, and moving the literal text upstream
 * would make every tweak a security-service release. Operator-supplied text can only ever fill three
 * slots in a template it does not control.
 *
 * <p>Every slot has a derivation (D5), so a role created with nothing but a name and description
 * still yields a working, distinguishable persona with no MCP change. Curated slots upgrade it; they
 * are never a precondition.
 */
public final class RolePersonaRenderer {

    static final String DEFAULT_FOCUS = "general operational questions within the caller's permissions";
    static final String DEFAULT_TONE = "helpful, careful, and neutral";

    /**
     * Renders a persona, deriving any absent slot.
     */
    public static @NonNull String render(@NonNull RolePersona persona) {
        return render(
                orDerived(persona.personaTitle(), () -> humanize(persona.name())),
                orDerived(persona.personaFocus(), () -> orDerived(persona.description(), () -> DEFAULT_FOCUS)),
                orDerived(persona.personaTone(), () -> DEFAULT_TONE));
    }

    /**
     * Renders explicit slots. Kept public for the {@code ROLE_USER} fallback persona, which is an
     * MCP-internal identity with no {@code pos-security-service} row to sync from.
     */
    public static @NonNull String render(@NonNull String title, @NonNull String focus, @NonNull String tone) {
        return """
        Role persona: you are assisting a %s.
        Lean toward %s.
        Communicate in a tone that is %s.
        This persona shapes tone and emphasis only; it never grants access to data, documents, or tools \
        beyond the caller's permissions.
        """.formatted(title, focus, tone);
    }

    /**
     * {@code INVENTORY_LEAD} to {@code inventory lead}. Used when a role carries no curated title,
     * which is the common case for a role created through the API.
     */
    static @NonNull String humanize(@NonNull String roleName) {
        return RoleAuthorities.toRoleName(roleName).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static @NonNull String orDerived(@Nullable String value, @NonNull Supplier<String> fallback) {
        return value == null || value.isBlank() ? fallback.get() : value.strip();
    }

    private RolePersonaRenderer() {}
}
