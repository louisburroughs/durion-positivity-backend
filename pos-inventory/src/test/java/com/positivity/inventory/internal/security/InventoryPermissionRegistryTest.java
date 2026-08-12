package com.positivity.inventory.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link InventoryPermissionRegistry} and the
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
@DisplayName("InventoryPermissionRegistry — naming and catalog correspondence")
class InventoryPermissionRegistryTest {

    /** {@code domain:resource:action}, lower snake_case, per CLAUDE.md. */
    private static final Pattern PERMISSION_NAME = Pattern.compile("^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$");

    private static List<String> declaredConstants() {
        List<String> names = new ArrayList<>();
        for (Field field : InventoryPermissionRegistry.class.getDeclaredFields()) {
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
        try (InputStream in = InventoryPermissionRegistryTest.class.getResourceAsStream("/permissions.yaml")) {
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
    @DisplayName("every declared constant is a well-formed inventory permission name")
    void constantsFollowTheConvention() {
        List<String> names = declaredConstants();

        assertThat(names).isNotEmpty();
        assertThat(names).allSatisfy(name -> {
            assertThat(name).matches(PERMISSION_NAME);
            // The domain segment is what scopes these against every other module's catalog.
            assertThat(name).startsWith("inventory:");
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
    @DisplayName("every authority any controller enforces is present in the registered catalog")
    void everyEnforcedAuthorityIsRegistered() throws Exception {
        Set<String> enforced = enforcedAuthorities();
        assertThat(enforced)
                .as("expected to find @PreAuthorize authorities to check")
                .isNotEmpty();

        // This is the direction that matters. An authority a controller enforces but the
        // catalog never registers is granted to nobody: the endpoint fails closed for every
        // user, at runtime, with nothing failing at build time. The check deliberately scans
        // for the enforced *string* rather than for constants, because this module enforces
        // most of its authorities as literals (only 29 of 54 have a constant) — a
        // constants-only check would inspect a third of the real surface and call it clean.
        assertThat(catalogNames()).containsAll(enforced);
    }

    /**
     * Authorities enforced anywhere in main sources via
     * {@code @PreAuthorize("hasAuthority('...')")}, whether written as a literal or as a
     * constant reference resolved at compile time (literals only — constant references are
     * covered by {@link #everyConstantIsRegistered()}).
     */
    private static Set<String> enforcedAuthorities() throws Exception {
        Pattern hasAuthority = Pattern.compile("hasAuthority\\(\\s*['\"]([a-z0-9_:.-]+)['\"]\\s*\\)");
        Set<String> found = new LinkedHashSet<>();
        Path root = Path.of("src", "main", "java");
        if (!Files.isDirectory(root)) {
            return found;
        }
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = hasAuthority.matcher(Files.readString(file));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return found;
    }
}
