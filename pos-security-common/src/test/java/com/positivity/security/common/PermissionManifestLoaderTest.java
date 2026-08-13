package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PermissionManifestLoader}, which was at 0% coverage.
 *
 * <p>
 * Each service declares its permissions in a YAML manifest that this loader
 * reads at startup and registers with pos-security-service (ADR-0025). Every
 * failure mode here is a startup failure by design, and that is the property
 * worth pinning: a manifest that is malformed, empty, missing or contains a
 * duplicate name must stop the service rather than register a partial set. A
 * partially registered manifest is the worst outcome available — the service
 * comes up, most endpoints work, and the few whose permissions were dropped fail
 * closed for every user with a 403 that looks like an ordinary authorization
 * decision.
 *
 * <p>
 * The loader is deliberately strict about types too. YAML will happily give a
 * number or a nested map where a string was meant, and coercing those would let
 * a typo become a live permission name.
 */
@DisplayName("PermissionManifestLoader — startup manifest parsing")
class PermissionManifestLoaderTest {

    @TempDir
    Path tempDir;

    private ClassLoader originalClassLoader;
    private final List<URLClassLoader> openedLoaders = new ArrayList<>();

    @BeforeEach
    void captureClassLoader() {
        originalClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @AfterEach
    void restoreClassLoader() throws IOException {
        // ClassPathResource resolves through the thread context loader, so these tests have to
        // swap it. Leaving it swapped would hand a temp-directory loader to every later test in
        // the JVM, and leaving the loaders open holds their file handles.
        Thread.currentThread().setContextClassLoader(originalClassLoader);
        for (URLClassLoader loader : openedLoaders) {
            loader.close();
        }
        openedLoaders.clear();
    }

    /**
     * Writes a manifest into a temp directory and exposes it on the classpath, so
     * the loader is exercised through its real ClassPathResource lookup rather
     * than through a seam added for testing.
     */
    private String manifest(String content) throws IOException {
        String name = "test-permissions-" + Math.abs(content.hashCode()) + ".yaml";
        Files.writeString(tempDir.resolve(name), content);
        URLClassLoader loader = new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, originalClassLoader);
        openedLoaders.add(loader);
        Thread.currentThread().setContextClassLoader(loader);
        return name;
    }

    private static final String VALID = """
            domain: crm
            serviceName: pos-customer
            version: "2.1"
            permissions:
              - name: crm:party:view
                description: View party records
              - name: crm:party:edit
                description: Edit party records
            """;

    @Test
    @DisplayName("a well-formed manifest is parsed into domain, service, version and permissions")
    void validManifestIsParsed() throws IOException {
        var loaded = PermissionManifestLoader.loadFromClasspath(manifest(VALID));

        assertThat(loaded.domain()).isEqualTo("crm");
        assertThat(loaded.serviceName()).isEqualTo("pos-customer");
        assertThat(loaded.version()).isEqualTo("2.1");
        assertThat(loaded.permissions())
                .extracting(PermissionDefinition::name)
                .containsExactly("crm:party:view", "crm:party:edit");
        assertThat(loaded.permissions().getFirst().description()).isEqualTo("View party records");
    }

    @Test
    @DisplayName("a missing version defaults to 1.0 rather than failing")
    void missingVersionDefaults() throws IOException {
        var loaded = PermissionManifestLoader.loadFromClasspath(manifest("""
                domain: crm
                serviceName: pos-customer
                permissions:
                  - name: crm:party:view
                """));

        // Version is the one optional field: a manifest written before versioning existed must
        // still load, so this defaults rather than throwing.
        assertThat(loaded.version()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("a blank version is treated as absent")
    void blankVersionDefaults() throws IOException {
        var loaded = PermissionManifestLoader.loadFromClasspath(manifest("""
                domain: crm
                serviceName: pos-customer
                version: "   "
                permissions:
                  - name: crm:party:view
                """));

        assertThat(loaded.version()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("a description is optional and comes back as null")
    void descriptionIsOptional() throws IOException {
        var loaded = PermissionManifestLoader.loadFromClasspath(manifest("""
                domain: crm
                serviceName: pos-customer
                permissions:
                  - name: crm:party:view
                """));

        assertThat(loaded.permissions().getFirst().description()).isNull();
    }

    @Test
    @DisplayName("a non-string description is dropped rather than coerced")
    void nonStringDescriptionIsDropped() throws IOException {
        var loaded = PermissionManifestLoader.loadFromClasspath(manifest("""
                domain: crm
                serviceName: pos-customer
                permissions:
                  - name: crm:party:view
                    description: 42
                """));

        // Descriptions are documentation, so a wrong type degrades to absent rather than
        // failing startup — the opposite policy from `name`, which is load-bearing.
        assertThat(loaded.permissions().getFirst().description()).isNull();
    }

    @Test
    @DisplayName("a manifest that is not on the classpath fails startup")
    void missingManifestFailsStartup() {
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath("no-such-manifest.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found on classpath");
    }

    @Test
    @DisplayName("an empty manifest file fails startup")
    void emptyManifestFailsStartup() throws IOException {
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest("# nothing here\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @ParameterizedTest(name = "a manifest missing {0} fails startup")
    @ValueSource(strings = {"domain", "serviceName"})
    @DisplayName("domain and serviceName are both required")
    void requiredHeaderFields(String omitted) throws IOException {
        String content =
                """
                domain: crm
                serviceName: pos-customer
                permissions:
                  - name: crm:party:view
                """.lines().filter(line -> !line.startsWith(omitted + ":")).reduce("", (a, b) -> a + b + "\n");

        // Both identify the manifest's owner at the registry. Registering permissions under the
        // wrong domain would hand them to the wrong service's roles.
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest(content)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(omitted);
    }

    @ParameterizedTest(name = "a {0} domain fails startup")
    @ValueSource(strings = {"'   '", "42"})
    @DisplayName("a blank or non-string domain is rejected rather than coerced")
    void blankOrNonStringDomainRejected(String value) throws IOException {
        String content = """
                domain: %s
                serviceName: pos-customer
                permissions:
                  - name: crm:party:view
                """.formatted(value);

        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest(content)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domain");
    }

    @Test
    @DisplayName("a manifest with no permissions list fails startup")
    void missingPermissionsListFailsStartup() throws IOException {
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest("""
                        domain: crm
                        serviceName: pos-customer
                        """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-empty permissions list");
    }

    @Test
    @DisplayName("a permissions list that is empty fails startup")
    void emptyPermissionsListFailsStartup() throws IOException {
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest("""
                        domain: crm
                        serviceName: pos-customer
                        permissions: []
                        """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-empty permissions list");
    }

    @Test
    @DisplayName("a permissions value that is not a list fails startup")
    void nonListPermissionsFailsStartup() throws IOException {
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest("""
                        domain: crm
                        serviceName: pos-customer
                        permissions: crm:party:view
                        """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-empty permissions list");
    }

    @Test
    @DisplayName("a permission entry that is not a map is reported with its position")
    void scalarPermissionEntryIsReportedByIndex() throws IOException {
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest("""
                        domain: crm
                        serviceName: pos-customer
                        permissions:
                          - name: crm:party:view
                          - crm:party:edit
                        """)))
                .isInstanceOf(IllegalStateException.class)
                // The index is what makes this actionable: a manifest can be hundreds of lines,
                // and "entry #2" is the difference between a one-minute fix and a bisect.
                .hasMessageContaining("#2")
                .hasMessageContaining("must be a map");
    }

    @ParameterizedTest(name = "a permission entry with a {0} name fails startup")
    @ValueSource(strings = {"missing", "blank", "numeric"})
    @DisplayName("every permission entry must carry a non-empty string name")
    void permissionNameIsRequired(String kind) throws IOException {
        String entry =
                switch (kind) {
                    case "missing" -> "    description: no name here";
                    case "blank" -> "    name: '   '";
                    default -> "    name: 42";
                };
        String content = """
                domain: crm
                serviceName: pos-customer
                permissions:
                  -
                %s
                """.formatted(entry);

        // The name is the authority string a @PreAuthorize will compare against. A numeric or
        // blank one would register something no controller can ever match.
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest(content)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-empty 'name'");
    }

    @Test
    @DisplayName("a duplicate permission name fails startup rather than silently collapsing")
    void duplicateNameFailsStartup() throws IOException {
        assertThatThrownBy(() -> PermissionManifestLoader.loadFromClasspath(manifest("""
                        domain: crm
                        serviceName: pos-customer
                        permissions:
                          - name: crm:party:view
                            description: View party records
                          - name: crm:party:view
                            description: Also view party records
                        """)))
                .isInstanceOf(IllegalStateException.class)
                // Deduplicating quietly would mean whichever description won was arbitrary, and
                // the manifest would stop matching what was registered.
                .hasMessageContaining("Duplicate permission name")
                .hasMessageContaining("crm:party:view");
    }

    @Test
    @DisplayName("the returned permission list is immutable")
    void returnedListIsImmutable() throws IOException {
        var loaded = PermissionManifestLoader.loadFromClasspath(manifest(VALID));

        // The manifest is read once at startup and shared; a caller that could mutate it would
        // be editing what every later reader sees.
        assertThatThrownBy(() -> loaded.permissions().add(PermissionDefinition.of("crm:party:delete")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(loaded.permissions()).hasSize(2);
    }
}
