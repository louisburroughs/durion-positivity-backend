package com.positivity.bulkloader.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.controller.BulkLoadJobController;
import com.positivity.bulkloader.internal.controller.FileUploadController;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * The platform operator can actually run the documented platform bulk load (ADR-0062 §7, plan WS8).
 *
 * <p>{@code docs/OPERATIONS_RUNBOOK.md} → "Bulk loading into a tenant" tells an operator to load the
 * role template with a {@code PLATFORM_ADMIN} token. Every endpoint that load touches — create job,
 * upload, process, poll — is gated on a {@code bulkImport:*} authority, and {@code PLATFORM_ADMIN}
 * is seeded by pos-security-service, in another module, from a file nothing in this module compiles
 * against. Nothing but this test connects the two: without it the seed can lose a grant, or a
 * controller can start requiring a new one, and the only symptom is a 403 in front of an operator
 * halfway through an alpha cutover.
 *
 * <p>The required side is read from the {@code @PreAuthorize} annotations themselves rather than
 * listed here, so a new endpoint on either controller is covered the moment it is written.
 */
class PlatformOperatorGrantsTest {

    /**
     * pos-security-service's platform bootstrap seed. Reached by relative path because the two
     * modules share a reactor but not a classpath — pos-bulk-loader must not depend on
     * pos-security-service. Surefire runs with the module directory as its working directory.
     */
    private static final Path PLATFORM_SEED = Path.of(
            "..", "pos-security-service", "src", "main", "resources", "db", "migration", "R__seed_tenant_template.sql");

    /** {@code ('PLATFORM_ADMIN', 'some:permission:name')}, the seed's grant-tuple shape. */
    private static final Pattern GRANT_TUPLE =
            Pattern.compile("\\(\\s*'PLATFORM_ADMIN'\\s*,\\s*'([a-zA-Z][\\w.-]*(?::[\\w.:-]+)+)'\\s*\\)");

    /** {@code hasAuthority('x')} inside a {@code @PreAuthorize} expression. */
    private static final Pattern HAS_AUTHORITY = Pattern.compile("hasAuthority\\(\\s*'([^']+)'\\s*\\)");

    /** Line comments, so a tuple quoted in the seed's prose is not read as a grant. */
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n]*");

    @Test
    @DisplayName("PLATFORM_ADMIN holds every authority the bulk-load endpoints enforce")
    void platformAdminHoldsWhatTheLoaderRequires() throws IOException {
        Set<String> required = requiredAuthorities(BulkLoadJobController.class, FileUploadController.class);
        assertThat(required)
                .as("both bulkImport families are enforced somewhere on these controllers")
                .contains(BulkImportPermissions.UPLOAD_EXECUTE, BulkImportPermissions.STATUS_READ);

        Set<String> granted = platformAdminGrants();
        assertThat(granted)
                .as("the platform bootstrap seed still grants the platform families it owns")
                .contains("platform:tenant:provision");
        assertThat(granted)
                .as(
                        "PLATFORM_ADMIN must hold every authority the documented platform role-template load goes"
                                + " through, or every one of those calls is a 403 before a pack is read (%s)",
                        PLATFORM_SEED)
                .containsAll(required);
    }

    @Test
    @DisplayName("the platform operator also holds the role-ingest authorities the loaded packs reach")
    void platformAdminHoldsTheRoleIngestAuthorities() throws IOException {
        // The two security packs the runbook names post to pos-security-service's
        // /v1/roles/bulk-ingest and /v1/roles/permissions/bulk-ingest, which enforce these. Named
        // as literals because those constants live in a module this one may not depend on; the
        // permission *name* is the contract either way, since that is what the catalog stores.
        assertThat(platformAdminGrants())
                .as("security/roles.csv and security/role-permissions.csv would 403 in the owning service")
                .contains("security:role:create", "security:role:edit");
    }

    private static Set<String> platformAdminGrants() throws IOException {
        assertThat(PLATFORM_SEED)
                .as("the platform bootstrap seed moved; this pin is reading nothing")
                .exists();
        String sql = SQL_LINE_COMMENT.matcher(Files.readString(PLATFORM_SEED)).replaceAll("");
        Set<String> grants = new TreeSet<>();
        Matcher matcher = GRANT_TUPLE.matcher(sql);
        while (matcher.find()) {
            grants.add(matcher.group(1));
        }
        assertThat(grants)
                .as("no PLATFORM_ADMIN grant tuples parsed; the seed's shape changed")
                .isNotEmpty();
        return grants;
    }

    private static Set<String> requiredAuthorities(Class<?>... controllers) {
        return Arrays.stream(controllers)
                .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods()))
                .map(PlatformOperatorGrantsTest::authority)
                .filter(authority -> authority != null)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String authority(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        if (preAuthorize == null) {
            return null;
        }
        Matcher matcher = HAS_AUTHORITY.matcher(preAuthorize.value());
        return matcher.find() ? matcher.group(1) : null;
    }
}
