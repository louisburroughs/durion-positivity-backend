package com.positivity.marketing.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link MarketingPermissionRegistry} and the
 * {@code permissions.yaml} catalog it mirrors (ADR-0025, plan wave 1c).
 *
 * <p>
 * Nothing in the compiler connects these two. The constants are what controllers
 * enforce with {@code @PreAuthorize("hasAuthority(...)")}; the YAML is what gets
 * registered with pos-security-service as the authority catalog. If a constant
 * has no catalog entry, the permission is enforced but never granted to anyone —
 * the endpoint fails closed and locks every user out, at runtime, with no build
 * error. If a catalog entry has no constant, an authority exists that nothing
 * checks.
 *
 * <p>
 * So the contract tested here is both directions of that correspondence, plus
 * the {@code domain:resource:action} naming convention — a typo in a name is
 * exactly the same lock-out, and equally invisible to the compiler.
 */
@DisplayName("MarketingPermissionRegistry — naming and catalog correspondence")
class MarketingPermissionRegistryTest {

    /** {@code domain:resource:action}, lower snake_case, per CLAUDE.md. */
    private static final Pattern PERMISSION_NAME = Pattern.compile("^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$");

    private static List<String> declaredConstants() {
        List<String> names = new ArrayList<>();
        for (Field field : MarketingPermissionRegistry.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    names.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("Unreadable permission constant: " + field.getName(), e);
                }
            }
        }
        return names;
    }

    /**
     * Reads the {@code name:} values straight out of the YAML rather than through a
     * parser: the file is a flat catalog, and a dependency-free read keeps this test
     * honest about what the shipped resource literally contains.
     */
    private static Set<String> catalogNames() throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (InputStream in = MarketingPermissionRegistryTest.class.getResourceAsStream("/permissions.yaml")) {
            assertThat(in).as("permissions.yaml must ship on the classpath").isNotNull();
            for (String line : new String(in.readAllBytes()).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- name:")) {
                    names.add(trimmed.substring("- name:".length()).trim().replace("\"", ""));
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("every declared constant is a well-formed marketing permission name")
    void constantsFollowTheConvention() {
        List<String> names = declaredConstants();

        assertThat(names).isNotEmpty();
        assertThat(names).allSatisfy(name -> {
            assertThat(name).matches(PERMISSION_NAME);
            // The domain segment is what scopes these against every other module's catalog.
            assertThat(name).startsWith("marketing:");
        });
    }

    @Test
    @DisplayName("declares no duplicate permission strings")
    void constantsAreUnique() {
        List<String> names = declaredConstants();

        // Two constants for one string is a rename half-done; the loser silently stops
        // matching whatever the catalog registered.
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every constant a controller can enforce is present in the registered catalog")
    void everyConstantIsRegistered() throws Exception {
        Set<String> catalog = catalogNames();

        // A constant with no catalog entry is enforced but never granted: the endpoint
        // fails closed for everyone, at runtime, with nothing failing at build time.
        assertThat(catalog).containsAll(declaredConstants());
    }

    @Test
    @DisplayName("the catalog registers nothing that no constant enforces")
    void catalogHasNoOrphans() throws Exception {
        // The reverse drift: an authority registered in security-service that no code
        // checks. Harmless at runtime but it rots the catalog and misleads whoever
        // assigns roles.
        assertThat(catalogNames()).containsExactlyInAnyOrderElementsOf(new LinkedHashSet<>(declaredConstants()));
    }
}
